package dev.irshadm.volta.service

import android.content.Context
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.data.db.TripDao
import dev.irshadm.volta.data.trip.LocationProvider
import dev.irshadm.volta.service.smart.Routine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Alerts that need more than one reading: the car moving or speeding while your phone is elsewhere,
 * the check before a planned trip, slow tyre / 12 V trends, and whether tonight's charge will be done
 * by your usual morning departure. State lives in the same "alerts" prefs as [CarCheckWorker].
 */
object SmartAlerts {
    private const val PREFS = "alerts"
    private const val NEAR_M = 1_500.0
    const val SPEED_LIMIT_KMH = 100.0

    private fun clock(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).lowercase()

    suspend fun evaluate(context: Context, v: VehicleStatus, location: LocationProvider, trips: TripDao) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val car = v.gps?.value?.let { LatLng(it.latitude, it.longitude) }
        val driving = v.ignitionOn?.value == true || (v.speedKmh?.value ?: 0.0) > 5
        // Is the owner with the car? null = can't tell (no location), and then the "someone else" alerts stay quiet.
        val phone = if (driving) location.current(8_000)?.takeIf { System.currentTimeMillis() - it.atMillis < 30 * 60_000 } else null
        val ownerAway: Boolean? = if (car == null || phone == null) null else Geo.distanceM(car, phone.position) > NEAR_M

        // --- Car moved / switched on while you're away (anti-theft)
        if (!driving && car != null) {
            prefs.edit().putString("parked_at", "${car.lat},${car.lng}").putBoolean("moved", false).putLong("speed_journey", -1).apply()
        } else if (driving && ownerAway == true && Alerts.isOn(context, "moved") && !prefs.getBoolean("moved", false)) {
            val from = prefs.getString("parked_at", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { LatLng(it[0], it[1]) }
            val km = if (from != null && car != null) Geo.distanceM(from, car) / 1000 else null
            Notifications.alert(
                context,
                "Your car is being driven",
                "It's switched on" + (km?.takeIf { it >= 0.3 }?.let { " and %.1f km from where you parked".format(it) } ?: "") +
                    ", and your phone isn't near it. Tap to see where it is.",
                Notifications.Level.Urgent,
            )
            prefs.edit().putBoolean("moved", true).apply()
        }

        // --- Speeding while someone else drives (once per journey)
        val speed = v.speedKmh?.value
        val journey = v.journeyId?.value ?: 0L
        if (speed != null && speed > SPEED_LIMIT_KMH && ownerAway == true && Alerts.isOn(context, "speed") && prefs.getLong("speed_journey", -1) != journey) {
            Notifications.alert(context, "Driving fast", "Your car was doing ${speed.roundToInt()} km/h at ${clock(v.speedKmh!!.atMillis)}.", Notifications.Level.Urgent)
            prefs.edit().putLong("speed_journey", journey).apply()
        }

        preTrip(context, v)
        health(context, v)
        chargeByMorning(context, v, trips)
    }

    // --- Before a planned trip

    /** Called when a plan with a future departure is made; [needSoc] = the start % the plan assumes. */
    fun planTrip(context: Context, departAt: Long, destination: String, needSoc: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("trip_at", departAt).putString("trip_to", destination).putFloat("trip_soc", needSoc.toFloat()).putBoolean("trip_told", false).apply()
        CarCheckWorker.checkIn(context, (departAt - System.currentTimeMillis()) / 60_000 - 45, "pre-trip")
    }

    private suspend fun preTrip(context: Context, v: VehicleStatus) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong("trip_at", 0L).takeIf { it > 0 } ?: return
        val now = System.currentTimeMillis()
        if (now > at) {
            prefs.edit().remove("trip_at").apply()
            return
        }
        if (prefs.getBoolean("trip_told", false) || at - now > 50 * 60_000 || !Alerts.isOn(context, "pretrip")) return
        val soc = v.soc?.value?.socPercent ?: return
        val need = prefs.getFloat("trip_soc", 0f).toDouble()
        val to = prefs.getString("trip_to", null) ?: "your trip"
        val weather = v.gps?.value?.let {
            dagger.hilt.android.EntryPointAccessors.fromApplication(context, dev.irshadm.volta.service.smart.SmartDeps::class.java).smartWeather().now(it.latitude, it.longitude)
        }
        val cabin = dev.irshadm.volta.data.weather.CabinModel.temps(v, weather).cabinC
        val cool = (cabin ?: 0.0) >= 30
        val text = if (soc + 2 < need) {
            "Battery ${soc.roundToInt()} %, the plan assumes ${need.roundToInt()} % — plug in now, or re-plan from ${soc.roundToInt()} %."
        } else {
            "Battery ${soc.roundToInt()} % — you're set." + if (cool) " Cabin is about ${cabin!!.roundToInt()}°; pre-cool before you go?" else ""
        }
        Notifications.alert(context, "Leaving for $to at ${clock(at)}", text)
        prefs.edit().putBoolean("trip_told", true).apply()
    }

    // --- Slow trends: tyres losing pressure, 12 V weakening (quiet notifications)

    private fun health(context: Context, v: VehicleStatus) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
        val log = runCatching { JSONArray(prefs.getString("health_log", "[]")) }.getOrDefault(JSONArray())
        val days = (0 until log.length()).map { log.getJSONObject(it) }.toMutableList()
        // One reading per day, the first while parked (tyres cold, 12 V at rest).
        val t = v.tyrePsi?.value
        val volts = v.auxBatteryVolts?.value
        if (v.ignitionOn?.value != true && days.none { it.getInt("d") == day } && (t != null || volts != null)) {
            days += JSONObject().put("d", day).apply {
                t?.let {
                    put("fl", it.frontLeft)
                    put("fr", it.frontRight)
                    put("rl", it.rearLeft)
                    put("rr", it.rearRight)
                }
                volts?.let { put("v", it) }
            }
            while (days.size > 21) days.removeAt(0)
            prefs.edit().putString("health_log", JSONArray(days).toString()).apply()
        }
        if (days.size < 7) return
        val now = days.last()
        val weekAgo = days[days.size - 7]
        // A tyre down 3 psi or more in a week while the others hold steady = a slow puncture.
        if (Alerts.isOn(context, "trend") && now.has("fl") && weekAgo.has("fl")) {
            val names = mapOf("fl" to "Front left", "fr" to "Front right", "rl" to "Rear left", "rr" to "Rear right")
            val drop = names.keys.associateWith { weekAgo.getDouble(it) - now.getDouble(it) }
            drop.filter { (k, d) -> d >= 3 && drop.filterKeys { it != k }.values.all { it < 1.5 } }.keys.forEach { k ->
                weekly(prefs, "trend_tyre_$k", day) {
                    Notifications.alert(
                        context,
                        "${names[k]} tyre slowly losing air",
                        "Down ${drop[k]!!.roundToInt()} psi in a week (now ${now.getDouble(k).roundToInt()} psi). Worth checking for a puncture.",
                        Notifications.Level.Quiet,
                    )
                }
            }
        }
        // 12 V: this week's resting voltage clearly below last week's, and getting low.
        val vs = days.filter { it.has("v") }.map { it.getDouble("v") }
        if (Alerts.isOn(context, "trend") && vs.size >= 10) {
            val recent = vs.takeLast(3).average()
            val before = vs.dropLast(3).takeLast(7).average()
            if (recent < 12.3 && before - recent >= 0.2) {
                weekly(prefs, "trend_aux", day) {
                    Notifications.alert(
                        context,
                        "12 V battery getting weaker",
                        "Resting voltage %.1f V, down from %.1f V last week. Have it checked before it leaves you stranded.".format(recent, before),
                        Notifications.Level.Quiet,
                    )
                }
            }
        }
    }

    private inline fun weekly(prefs: android.content.SharedPreferences, key: String, day: Int, fire: () -> Unit) {
        if (day - prefs.getInt(key, -100) >= 7) {
            fire()
            prefs.edit().putInt(key, day).apply()
        }
    }

    // --- Will tonight's charge be done by your usual departure?

    private suspend fun chargeByMorning(context: Context, v: VehicleStatus, trips: TripDao) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val session = v.chargeStartedAtMillis ?: 0L
        if (v.charging?.value != true || !Alerts.isOn(context, "bymorning") || prefs.getLong("bymorning_session", -1) == session) return
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        if (hour in 5 until 19) return // only overnight charging
        val remaining = v.chargeMinutesRemaining?.value?.takeIf { it > 0 } ?: return
        val soc = v.soc?.value?.socPercent ?: return
        // The first usual departure of the next morning, from your drive history.
        val morning = Calendar.getInstance().apply {
            if (hour >= 19) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
        }
        val weekend = morning.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
        val slot = Routine.slots(trips.finished(300)).filter { it.weekend == weekend && it.minuteOfDay in 300..720 }.minByOrNull { it.minuteOfDay } ?: return
        val leaveAt = morning.timeInMillis + slot.minuteOfDay * 60_000L
        val doneAt = System.currentTimeMillis() + remaining * 60_000L
        prefs.edit().putLong("bymorning_session", session).apply()
        if (doneAt <= leaveAt) return // will be done in time: nothing to say
        val minutesAvailable = ((leaveAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val atLeave = (soc + (100 - soc) * minutesAvailable.toDouble() / remaining).coerceAtMost(100.0)
        Notifications.alert(
            context,
            "Charge won't finish by ${clock(leaveAt)}",
            "It needs until ${clock(doneAt)}. By your usual ${clock(leaveAt)} departure it will be about ${atLeave.roundToInt()} %.",
        )
    }
}
