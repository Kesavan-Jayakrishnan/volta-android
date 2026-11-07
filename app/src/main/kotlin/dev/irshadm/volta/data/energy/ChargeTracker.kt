package dev.irshadm.volta.data.energy

import dev.irshadm.volta.core.model.ChargeCategory
import dev.irshadm.volta.core.model.EnergySettings
import dev.irshadm.volta.core.model.SocReading
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.db.ChargeDao
import dev.irshadm.volta.data.db.ChargeSessionEntity
import dev.irshadm.volta.data.db.SocReadingDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns car readings into charging sessions. Plugged-in → open/extend a session;
 * unplugged → close it. A SoC rise with no session in between becomes an "inferred" session.
 *
 * The unplug isn't always seen (no read between unplugging and the next plug-in, or reads without the charge frame
 * carrying "plugged in" over a drive), which left the old session open and counted the next charge from its start
 * ("+32 %" for a 31 → 56 % charge). So an open session is checked against the car on every plugged-in read: if the
 * battery went down since, or the car says this charge started later, the old one is closed at its fullest and a
 * new one starts from the last reading before the car's start time.
 */
@Singleton
class ChargeTracker @Inject constructor(
    private val dao: ChargeDao,
    private val readings: SocReadingDao,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun onReading(status: VehicleStatus, previous: SocReading?) = mutex.withLock {
        val soc = status.soc?.value ?: return@withLock
        val e = settings.energySettings.first()
        val cap = settings.vehicleProfile.first().usableCapacityKwh
        val at = soc.measuredAtMillis
        val kwh = soc.batteryEnergyKwh ?: (soc.socPercent / 100 * cap)
        val power = status.chargePowerKw?.value ?: 0.0
        val pos = status.gps?.value?.let { LatLng(it.latitude, it.longitude) }
        // Switched on means driving, whatever a carried-over "plugged in" says.
        val plugged = if (status.ignitionOn?.value == true && status.charging?.value != true) false else status.pluggedIn?.value
        val carStart = status.chargeStartedAtMillis
        val open = dao.open()
        if (plugged == true && open != null && isStale(open, soc.socPercent, carStart)) {
            val begins = carStart ?: at
            val top = readings.highestBetween(open.startedAt, begins)
            dao.update(
                open.copy(
                    endedAt = top?.measuredAtMillis ?: open.startedAt,
                    endSoc = top?.socPercent ?: open.endSoc,
                    endKwh = top?.let { it.batteryEnergyKwh ?: (it.socPercent / 100 * cap) } ?: open.endKwh,
                ),
            )
            val before = readings.lastBefore(begins)
            val startSoc = startLevel(before?.socPercent, soc.socPercent)
            dao.insert(
                ChargeSessionEntity(
                    startedAt = begins, lat = pos?.lat, lng = pos?.lng, startSoc = startSoc,
                    startKwh = if (before != null && startSoc == before.socPercent) before.batteryEnergyKwh ?: (startSoc / 100 * cap) else kwh,
                    endSoc = soc.socPercent, endKwh = kwh, peakKw = power, category = classify(pos, power, e).name,
                ),
            )
            return@withLock
        }

        when {
            plugged == true && open == null -> {
                // The first plugged-in read can come well after the charge began: start from the car's start time.
                val before = carStart?.takeIf { it < at }?.let { readings.lastBefore(it) }
                val startSoc = startLevel(before?.socPercent, soc.socPercent)
                dao.insert(
                    ChargeSessionEntity(
                        startedAt = carStart?.takeIf { it < at } ?: at, lat = pos?.lat, lng = pos?.lng, startSoc = startSoc,
                        startKwh = if (before != null && startSoc == before.socPercent) before.batteryEnergyKwh ?: (startSoc / 100 * cap) else kwh,
                        endSoc = soc.socPercent, endKwh = kwh, peakKw = power, category = classify(pos, power, e).name,
                    ),
                )
            }

            plugged == true && open != null -> {
                val peak = maxOf(open.peakKw, power)
                val cat = if (open.userEdited) open.category else classify(open.lat?.let { LatLng(it, open.lng!!) } ?: pos, peak, e).name
                dao.update(open.copy(endSoc = soc.socPercent, endKwh = kwh, peakKw = peak, category = cat))
            }

            plugged == false && open != null -> dao.update(open.copy(endedAt = at, endSoc = soc.socPercent, endKwh = kwh))

            open == null && previous != null && soc.socPercent - previous.socPercent >= INFER_MIN_RISE && at > previous.measuredAtMillis -> {
                val hours = (at - previous.measuredAtMillis) / 3_600_000.0
                val addedKwh = (soc.socPercent - previous.socPercent) / 100 * cap
                val avgKw = if (hours > 0) addedKwh / hours else 0.0
                dao.insert(
                    ChargeSessionEntity(
                        startedAt = previous.measuredAtMillis, endedAt = at, lat = pos?.lat, lng = pos?.lng,
                        startSoc = previous.socPercent, endSoc = soc.socPercent, startKwh = previous.batteryEnergyKwh ?: (previous.socPercent / 100 * cap),
                        endKwh = kwh, peakKw = avgKw, category = classify(pos, avgKw, e).name, inferred = true,
                    ),
                )
            }
        }
    }

    private fun classify(pos: LatLng?, peakKw: Double, e: EnergySettings): ChargeCategory {
        if (pos != null && e.hasHome && Geo.distanceM(pos, LatLng(e.homeLat!!, e.homeLng!!)) <= e.homeRadiusM) return ChargeCategory.HOME
        return if (peakKw >= e.dcThresholdKw) ChargeCategory.PUBLIC_DC else ChargeCategory.PUBLIC_AC
    }

    internal companion object {
        const val INFER_MIN_RISE = 3.0

        /** A charge never lowers the battery; more than this drop means the car was driven since. */
        const val DROP = 1.5

        /** The car's own charge start this long after the open session's start means it's a new charge. */
        const val LATER_MS = 10 * 60_000L

        /** Whether [open] is an earlier charge whose end the app never saw. */
        fun isStale(open: ChargeSessionEntity, socNow: Double, carStartedAt: Long?): Boolean = socNow < (open.endSoc ?: socNow) - DROP || (carStartedAt != null && carStartedAt > open.startedAt + LATER_MS)

        /** Where a charge began: the last reading before it, unless that's above now (it was before a drive). */
        fun startLevel(lastBefore: Double?, socNow: Double): Double = lastBefore?.takeIf { it <= socNow } ?: socNow
    }
}
