package dev.irshadm.volta.service

import android.content.Context

/** Which background alerts are on (Settings → Alerts). Stored next to the alert state the worker keeps. */
object Alerts {
    data class Kind(val key: String, val label: String, val detail: String)

    val kinds = listOf(
        Kind("charging", "Charging finished or stopped", "When a charge completes, or stops early while still plugged in"),
        Kind("open", "Door, boot or bonnet open", "Something left open"),
        Kind("unlocked", "Left unlocked", "Parked and unlocked for longer than the wait time"),
        Kind("windows", "Window open", "Parked with a window open for longer than the wait time"),
        Kind("lefton", "Left switched on", "Parked with the car still on for longer than the wait time"),
        Kind("lights", "Headlights left on", "Headlights on while parked"),
        Kind("tyre", "Low tyre pressure", "Any tyre below 28 psi"),
        Kind("aux", "Weak 12 V battery", "Below 11.8 V"),
        Kind("low", "Low battery while parked", "Below 15 % and not plugged in"),
        Kind("moved", "Car moved or switched on", "Being driven while your phone isn't near it (anti-theft)"),
        Kind("speed", "Driving fast", "Above ${SmartAlerts.SPEED_LIMIT_KMH.toInt()} km/h while someone else drives"),
        Kind("pretrip", "Before a planned trip", "About 45 min before you leave: is the battery enough for the plan?"),
        Kind("bymorning", "Charge done by morning?", "Overnight charging that won't finish by your usual departure"),
        Kind("trend", "Tyre and 12 V trends", "A tyre slowly losing air, or the 12 V battery weakening (silent)"),
        Kind("rangewatch", "Range watch on everyday drives", "Using more than usual, or not enough left to get home: with the closest fast charger"),
        Kind("batterycare", "Battery care", "Gentle tips for a long battery life: a weekly full charge, plug in tonight, not sitting low or full in the heat"),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun isOn(context: Context, key: String) = key !in prefs(context).getStringSet("off", emptySet()).orEmpty()

    fun set(context: Context, key: String, on: Boolean) {
        val off = prefs(context).getStringSet("off", emptySet()).orEmpty().toMutableSet()
        if (on) off -= key else off += key
        prefs(context).edit().putStringSet("off", off).apply()
        if (key == "moved") CarCheckWorker.schedule(context) // 15 min while it's on, else 30
    }

    fun onCount(context: Context) = kinds.count { isOn(context, it.key) }

    /** How long the car must stay parked that way before "left unlocked / window open / left on" alerts fire. */
    val delayChoices = listOf(10, 15, 30)
    fun parkedDelayMin(context: Context) = prefs(context).getInt("parked_delay", 15)
    fun setParkedDelay(context: Context, minutes: Int) = prefs(context).edit().putInt("parked_delay", minutes).apply()
}
