package dev.irshadm.volta.data.trip

import android.content.Context
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.db.TripDao
import dev.irshadm.volta.data.db.TripEntity
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logs every drive the car reports (source = CAR), without the user starting a trip: whenever a
 * reading shows the odometer moved since the previous one, the stretch is saved — and extended while
 * the car keeps moving. A stop of more than [GAP_MS] (the odometer standing still) ends the drive: the car's
 * journey id can stay the same for a whole day, so it isn't enough on its own. Trips started from Plan
 * (source = APP) take precedence. Granularity is the polling interval, so two short drives between checks
 * become one entry.
 */
@Singleton
class DriveLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TripDao,
    private val settings: SettingsRepository,
    private val readings: dev.irshadm.volta.data.db.SocReadingDao,
) {
    suspend fun onReading(now: VehicleStatus, prev: VehicleStatus?) {
        val nowSoc = now.soc?.value ?: return
        val prevSoc = prev?.soc?.value ?: return
        val odo = nowSoc.odometerKm ?: return
        val prevOdo = prevSoc.odometerKm ?: return
        if (odo - prevOdo < MIN_KM) return
        // A planned trip in progress already records this drive (ignore ones abandoned >1 day ago).
        if (dao.openAppTrips(System.currentTimeMillis() - 86_400_000L) > 0) return

        val capacity = settings.vehicleProfile.first().usableCapacityKwh
        val journey = now.journeyId?.value?.takeIf { it != 0L }
        val endName = placeName(now)
        // Carry on the last drive only if the car hadn't been standing since it (prev = the last still reading).
        val existing = (journey?.let { dao.byJourney(it) } ?: dao.lastCarTrip())
            ?.takeIf { it.endOdometerKm != null && kotlin.math.abs(it.endOdometerKm - prevOdo) < 0.2 }
            ?.takeIf { t -> t.endedAt != null && prevSoc.measuredAtMillis - t.endedAt <= GAP_MS }
        if (existing != null) {
            val start = existing.destination?.substringBefore(" → ")
            dao.update(existing.copy(
                endedAt = nowSoc.measuredAtMillis, endSoc = nowSoc.socPercent, endOdometerKm = odo,
                usedKwh = used(existing.startSoc, nowSoc.socPercent, capacity),
                destination = listOfNotNull(start, endName).distinct().joinToString(" → ").ifEmpty { null },
                journeyId = journey ?: existing.journeyId,
            ))
        } else {
            val startName = placeName(prev)
            dao.insert(TripEntity(
                startedAt = startOf(prevSoc.measuredAtMillis, nowSoc.measuredAtMillis, odo - prevOdo, now.ignitionChangedAtMillis), endedAt = nowSoc.measuredAtMillis,
                destination = listOfNotNull(startName, endName).distinct().joinToString(" → ").ifEmpty { null },
                plannedDistanceM = null, startSoc = prevSoc.socPercent, endSoc = nowSoc.socPercent,
                startOdometerKm = prevOdo, endOdometerKm = odo, usedKwh = used(prevSoc.socPercent, nowSoc.socPercent, capacity),
                source = "CAR", journeyId = journey,
            ))
        }
    }

    /** When the last drive logged from the car ended, if any. */
    suspend fun lastDriveEnd(): Long? = dao.lastCarTrip()?.endedAt

    /**
     * One-off: rebuild drives from readings stored before the logger existed. Consecutive odometer
     * increases less than 45 min apart become one drive. No GPS was stored then, so they're unnamed.
     */
    suspend fun backfill() {
        val prefs = context.getSharedPreferences("drives", Context.MODE_PRIVATE)
        if (prefs.getBoolean("backfilled", false)) return
        if (dao.lastCarTrip() == null) {
            val capacity = settings.vehicleProfile.first().usableCapacityKwh
            val list = readings.allWithOdometer()
            var start: dev.irshadm.volta.data.db.SocReadingEntity? = null
            var end: dev.irshadm.volta.data.db.SocReadingEntity? = null
            suspend fun flush() {
                val s = start; val e = end
                if (s != null && e != null && e.odometerKm!! - s.odometerKm!! >= MIN_KM) dao.insert(TripEntity(
                    startedAt = s.measuredAtMillis, endedAt = e.measuredAtMillis, destination = null, plannedDistanceM = null,
                    startSoc = s.socPercent, endSoc = e.socPercent, startOdometerKm = s.odometerKm, endOdometerKm = e.odometerKm,
                    usedKwh = used(s.socPercent, e.socPercent, capacity), source = "CAR",
                ))
                start = null; end = null
            }
            list.zipWithNext { a, b -> a to b }.forEach { (a, b) ->
                val moved = b.odometerKm!! - a.odometerKm!! >= 0.1
                if (!moved) return@forEach
                if (end != null && a.measuredAtMillis - end!!.measuredAtMillis > 45 * 60_000L) flush()
                if (start == null) start = a
                end = b
            }
            flush()
        }
        prefs.edit().putBoolean("backfilled", true).apply()
    }

    /**
     * One-off repair: drives logged before the stop rule existed could swallow a whole day (parked overnight,
     * then several drives). Split every car-logged trip at stops longer than [GAP_MS], from the stored readings.
     */
    suspend fun resplit() {
        val prefs = context.getSharedPreferences("drives", Context.MODE_PRIVATE)
        if (prefs.getBoolean("resplit_v1", false)) return
        val capacity = settings.vehicleProfile.first().usableCapacityKwh
        for (t in dao.carTrips()) {
            val end = t.endedAt ?: continue
            val list = readings.withOdometerBetween(t.startedAt, end)
            val groups = ArrayList<Pair<dev.irshadm.volta.data.db.SocReadingEntity, dev.irshadm.volta.data.db.SocReadingEntity>>()
            var first: dev.irshadm.volta.data.db.SocReadingEntity? = null
            var last: dev.irshadm.volta.data.db.SocReadingEntity? = null
            list.zipWithNext().forEach { (a, b) ->
                if (b.odometerKm!! - a.odometerKm!! < 0.1) return@forEach
                if (last != null && a.measuredAtMillis - last!!.measuredAtMillis > GAP_MS) { groups += first!! to last!!; first = null }
                if (first == null) first = a
                last = b
            }
            if (first != null) groups += first!! to last!!
            val drives = groups.filter { (a, b) -> b.odometerKm!! - a.odometerKm!! >= MIN_KM }
            if (drives.size < 2) continue
            val from = t.destination?.substringBefore(" → ", "")?.ifEmpty { null }
            val to = t.destination?.substringAfter(" → ", "")?.ifEmpty { null }
            dao.delete(t.id)
            drives.forEachIndexed { i, (a, b) ->
                dao.insert(TripEntity(
                    startedAt = startOf(a.measuredAtMillis, b.measuredAtMillis, b.odometerKm!! - a.odometerKm!!, null), endedAt = b.measuredAtMillis,
                    destination = when (i) { 0 -> from; drives.lastIndex -> to; else -> null },   // only the ends are known
                    plannedDistanceM = null, startSoc = a.socPercent, endSoc = b.socPercent,
                    startOdometerKm = a.odometerKm, endOdometerKm = b.odometerKm, usedKwh = used(a.socPercent, b.socPercent, capacity), source = "CAR",
                ))
            }
        }
        prefs.edit().putBoolean("resplit_v1", true).apply()
    }

    /**
     * When a drive started. The reading before it can be hours old (parked overnight, phone asleep): then use
     * the car's own ignition time if it falls in between, or work back from the distance at ~25 km/h.
     */
    private fun startOf(prevAt: Long, nowAt: Long, km: Double, ignitionAt: Long?): Long {
        if (nowAt - prevAt <= GAP_MS) return prevAt
        ignitionAt?.takeIf { it in prevAt..nowAt }?.let { return it }
        return maxOf(prevAt, nowAt - (km / 25.0 * 3_600_000).toLong())
    }

    /** kWh used from the SoC drop; null if the battery went up (charged in between). */
    private fun used(startSoc: Double?, endSoc: Double, capacity: Double): Double? =
        startSoc?.let { (it - endSoc) }?.takeIf { it >= 0 }?.let { it * capacity / 100 }

    /** Saved place name, "Home", or the area from the phone's geocoder. */
    private suspend fun placeName(v: VehicleStatus): String? {
        val g = v.gps?.value ?: return null
        val here = LatLng(g.latitude, g.longitude)
        settings.savedPlaces.first().firstOrNull { it.contains(g.latitude, g.longitude) }?.let { return it.name }
        val e = settings.energySettings.first()
        if (e.hasHome && Geo.distanceM(LatLng(e.homeLat!!, e.homeLng!!), here) <= e.homeRadiusM) return "Home"
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                android.location.Geocoder(context).getFromLocation(g.latitude, g.longitude, 1)?.firstOrNull()?.let { it.subLocality ?: it.locality }
            }.getOrNull()
        }
    }

    private companion object {
        const val MIN_KM = 0.5
        /** Standing still longer than this between readings means the drive ended. */
        const val GAP_MS = 20 * 60_000L
    }
}
