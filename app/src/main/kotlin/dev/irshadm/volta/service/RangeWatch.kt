package dev.irshadm.volta.service

import android.content.Context
import dev.irshadm.volta.core.model.EnergySettings
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.data.db.TripDao
import dev.irshadm.volta.data.maps.GoogleMapsClient
import kotlin.math.roundToInt

/**
 * The charge guardian for everyday drives, when no trip was planned. While the car is being driven (checked with
 * the background car check, about every 15 minutes) it compares:
 *  - this drive's km/kWh with your usual → "Using more than usual today" (normal alert);
 *  - range left with the way home → "Charge before heading home", naming the closest fast charger (act-now chime).
 * Without a plan it can't know where you're going, so home is the yardstick: the trip you always make eventually.
 * Each warning fires at most once per drive, and stays quiet near home or with a comfortable margin.
 */
object RangeWatch {
    private const val PREFS = "range_watch"

    /** Road distance ≈ straight line × this. */
    private const val ROAD_FACTOR = 1.3

    /** Warn while there's still this much margin over the way home. */
    private const val MARGIN_KM = 15.0

    suspend fun evaluate(context: Context, v: VehicleStatus, trips: TripDao, energy: EnergySettings, vehicle: VehicleProfile, maps: GoogleMapsClient) {
        if (v.ignitionOn?.value != true || !Alerts.isOn(context, "rangewatch")) return
        val drive = v.ignitionChangedAtMillis ?: return
        val soc = v.soc?.value?.socPercent ?: return
        val car = v.gps?.value?.let { LatLng(it.latitude, it.longitude) } ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Your usual efficiency: the last 30 days of logged drives (all of them, total km / total kWh).
        val recent = trips.finished(300).filter { now - it.startedAt < 30 * 86_400_000L }
        val km = recent.sumOf { t -> if (t.endOdometerKm != null && t.startOdometerKm != null) t.endOdometerKm - t.startOdometerKm else 0.0 }
        val kwh = recent.sumOf { it.usedKwh ?: 0.0 }
        val usual = if (km >= 30 && kwh >= 3) km / kwh else (vehicle.cityKmPerKwh + vehicle.highwayKmPerKwh) / 2

        // This drive so far (the drive logger keeps extending the current trip while you're moving).
        val current = trips.lastCarTrip()?.takeIf { it.startedAt >= drive - 10 * 60_000L && it.endedAt != null && now - it.endedAt < 40 * 60_000L }
        val driveKm = current?.let { t -> if (t.endOdometerKm != null && t.startOdometerKm != null) t.endOdometerKm - t.startOdometerKm else null }
        val driveKwh = current?.usedKwh
        val driveEff = if (driveKm != null && driveKwh != null && driveKm >= 10 && driveKwh >= 1) driveKm / driveKwh else null

        val pace = minOf(usual, driveEff ?: usual)
        val kmLeft = soc / 100 * vehicle.usableCapacityKwh * pace
        val homeKm = if (energy.hasHome) Geo.distanceM(car, LatLng(energy.homeLat!!, energy.homeLng!!)) / 1000 * ROAD_FACTOR else null
        val homeText = homeKm?.takeIf { it > 3 }?.let { "; home is ${it.roundToInt()} km away" } ?: ""

        fun once(key: String) = prefs.getLong(key, -1) != drive && run {
            prefs.edit().putLong(key, drive).apply()
            true
        }

        if (driveEff != null && driveEff < usual * 0.8 && once("eff")) {
            Notifications.alert(
                context,
                "Using more than usual today",
                "%.1f km/kWh on this drive vs your usual %.1f. About %d km left%s.".format(driveEff, usual, kmLeft.roundToInt(), homeText),
            )
        }

        val short = if (homeKm != null) homeKm > 3 && kmLeft < homeKm + MARGIN_KM else kmLeft < 30
        if (short && once("home")) {
            val charger = runCatching { maps.nearestFastCharger(car) }.getOrNull()
            val where = charger?.let { " ${it.name} is ${"%.0f".format(Geo.distanceM(car, it.position) / 1000)} km away." } ?: ""
            val title = if (homeKm != null) "Charge before heading home" else "Range is getting low"
            Notifications.alert(
                context,
                title,
                "About ${kmLeft.roundToInt()} km left$homeText.$where",
                Notifications.Level.Urgent,
                listOfNotNull(charger?.let { Notifications.navigate(context, "Navigate there", it.position) }),
            )
        }
    }
}
