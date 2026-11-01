package dev.irshadm.volta.service

import android.content.Context
import dev.irshadm.volta.core.model.VehicleStatus
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Friendly battery-care tips for the Windsor's LFP battery, which wants different habits from phones and
 * older EVs: a full charge about once a week keeps its % reading accurate (LFP voltage barely moves in the
 * middle, so the car needs to see "full" now and then), 80–90 % is fine day to day, and it shouldn't sit very
 * low, or full in the heat, for days. At most one tip at a time: shown under the charge bar, and sent as a
 * notification at most once a day each (Settings → Alerts → Battery care).
 */
object BatteryCare {
    data class Tip(val key: String, val title: String, val text: String)

    private const val PREFS = "battery_care"
    private const val DAY = 86_400_000L
    const val FULL_EVERY_DAYS = 7

    /** Remembers when the battery was last full, and since when it has been low or sitting full. Call on every reading. */
    fun record(context: Context, v: VehicleStatus, now: Long = System.currentTimeMillis()) {
        val soc = v.soc?.value?.socPercent ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = prefs.edit()
        if (!prefs.contains("watching_since")) e.putLong("watching_since", now)
        if (soc >= 99) e.putLong("last_full", now)
        val plugged = v.pluggedIn?.value == true
        if (soc < 20 && !plugged) { if (!prefs.contains("low_since")) e.putLong("low_since", now) } else e.remove("low_since")
        if (soc >= 98 && v.charging?.value != true) { if (!prefs.contains("full_since")) e.putLong("full_since", now) } else e.remove("full_since")
        e.apply()
    }

    /**
     * The one tip worth showing now, or null. [outsideC] from the weather; [usualDailyKm] from your drives
     * (null when there isn't enough history); [kmPerKwh] and [capacityKwh] turn that into a % for tonight.
     */
    fun tip(context: Context, v: VehicleStatus?, outsideC: Double?, usualDailyKm: Double?, kmPerKwh: Double, capacityKwh: Double,
            now: Long = System.currentTimeMillis()): Tip? {
        val soc = v?.soc?.value?.socPercent ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val plugged = v.pluggedIn?.value == true
        val charging = v.charging?.value == true
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

        prefs.getLong("low_since", 0).takeIf { it > 0 && now - it > DAY }?.let {
            return Tip("low", "Charge soon", "It's been under 20 % for over a day. Plug in when you can; LFP batteries don't like sitting low.")
        }
        if (!plugged && hour in 19..23 && soc < 35) {
            val need = usualDailyKm?.let { it / kmPerKwh / capacityKwh * 100 }
            val text = if (need != null && soc - need < 20) "You're at ${soc.roundToInt()} %. Charging overnight to 90 % covers tomorrow's usual ${usualDailyKm.roundToInt()} km with plenty left."
            else "You're at ${soc.roundToInt()} %. A night on the charger gets you back to a comfortable 80–90 %."
            return Tip("tonight", "Plug in tonight?", text)
        }
        prefs.getLong("full_since", 0).takeIf { it > 0 && now - it > 36 * 3_600_000L && (outsideC ?: 0.0) >= 32 }?.let {
            return Tip("heat", "Sitting full in the heat", "It's been at 100 % for ${((now - it) / DAY).coerceAtLeast(1)} days at ${outsideC!!.roundToInt()}° outside. Fine now and then; if you're not driving soon, stop at 90 % next time.")
        }
        val lastFull = prefs.getLong("last_full", 0)
        val since = maxOf(lastFull, prefs.getLong("watching_since", now))
        if (!charging && now - since > FULL_EVERY_DAYS * DAY) {
            val ago = if (lastFull > 0) " Last full: ${(now - lastFull) / DAY} days ago." else ""
            return Tip("full", "Time for a full charge", "Charge to 100 % once this week so the battery reading stays accurate.$ago")
        }
        return null
    }

    /** Sends the current tip as a gentle notification, each kind at most once a day. */
    fun notify(context: Context, tip: Tip?, now: Long = System.currentTimeMillis()) {
        if (tip == null || !Alerts.isOn(context, "batterycare")) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = now / DAY
        if (prefs.getLong("told_${tip.key}", -1) == day) return
        Notifications.alert(context, tip.title, tip.text, Notifications.Level.Quiet)
        prefs.edit().putLong("told_${tip.key}", day).apply()
    }

    /** Average km a day over the last two weeks of logged drives, or null with less than a week of history. */
    fun usualDailyKm(trips: List<dev.irshadm.volta.data.db.TripEntity>, now: Long = System.currentTimeMillis()): Double? {
        val recent = trips.filter { now - it.startedAt < 14 * DAY }
        val first = recent.minOfOrNull { it.startedAt } ?: return null
        val days = ((now - first) / DAY.toDouble()).coerceAtLeast(1.0)
        if (days < 7) return null
        val km = recent.sumOf { t -> if (t.endOdometerKm != null && t.startOdometerKm != null) t.endOdometerKm - t.startOdometerKm else 0.0 }
        return km / days
    }
}
