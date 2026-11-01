package dev.irshadm.volta.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import dev.irshadm.volta.MainActivity
import dev.irshadm.volta.QuickActionActivity
import dev.irshadm.volta.R
import dev.irshadm.volta.core.ismart.RemoteCommand
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.db.ChargeDao
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.service.Alerts
import dev.irshadm.volta.service.smart.Cooling
import dev.irshadm.volta.ui.home.CarAlert
import dev.irshadm.volta.ui.home.Tier
import dev.irshadm.volta.ui.home.carAlerts
import dev.irshadm.volta.ui.home.chargeMinutesLeft
import dev.irshadm.volta.ui.home.parked
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home-screen widgets: small (what the car is doing, range, charge) and medium (plus the AC pill with the cabin
 * temperature and Lock). They never read the car themselves: the app pushes [update] after every reading (opening
 * the app, the 15-minute background check, a live trip), which is why every widget ends its line with when the car
 * was last read. The buttons open the same confirm sheet as the app shortcuts, so a fingerprint is still needed;
 * the widget never sends a command on its own.
 */
object VoltaWidget {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun widgetIsmart(): IsmartRepository
        fun widgetSettings(): SettingsRepository
        fun widgetCharges(): ChargeDao
        fun widgetWeather(): dev.irshadm.volta.data.weather.WeatherClient
    }

    /** Redraws every Volta widget on the home screen from the latest car status. */
    suspend fun update(context: Context, status: VehicleStatus? = null) {
        val app = context.applicationContext
        val deps = EntryPointAccessors.fromApplication(app, Deps::class.java)
        val v = status ?: deps.widgetIsmart().status.value
        val profile = deps.widgetSettings().vehicleProfile.first()
        val mgr = AppWidgetManager.getInstance(app)
        mgr.getAppWidgetIds(ComponentName(app, VoltaWidgetSmall::class.java)).forEach { mgr.updateAppWidget(it, small(app, v, profile)) }
        val mediumIds = mgr.getAppWidgetIds(ComponentName(app, VoltaWidgetMedium::class.java))
        if (mediumIds.isNotEmpty()) {
            val weather = v?.gps?.value?.let { runCatching { deps.widgetWeather().now(it.latitude, it.longitude) }.getOrNull() }
            val temps = dev.irshadm.volta.data.weather.CabinModel.temps(v, weather)
            mediumIds.forEach { mgr.updateAppWidget(it, medium(app, v, profile, temps)) }
        }
    }

    /** Fire-and-forget [update] for callers that aren't suspending (the widget providers). */
    fun updateAsync(context: Context) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { update(context) } }
    }

    private fun rangeKm(v: VehicleStatus?, p: VehicleProfile): Int? = v?.soc?.value?.socPercent?.let {
        ((p.highwayRangeKm(it) + p.cityRangeKm(it)) / 2).roundToInt()
    }

    private fun clock(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).lowercase()

    /** "7:45 pm" today, "Mon 7:45 pm" this week, "21 Oct" before that. */
    private fun since(at: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val fmt = when { sameDay -> "h:mm a"; (now.timeInMillis - at) < 6 * 86_400_000L -> "EEE h:mm a"; else -> "d MMM" }
        return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(at)).replace("AM", "am").replace("PM", "pm")
    }

    /** When the car was last read: the newest timestamp among the fields the widget shows. */
    private fun readAt(v: VehicleStatus): Long? =
        listOfNotNull(v.soc?.atMillis, v.locked?.atMillis, v.ignitionOn?.atMillis, v.charging?.atMillis, v.climateRunning?.atMillis).maxOrNull()

    /** "read 3:12 pm": the one thing a car widget must never leave out, since the numbers are only as fresh as this. */
    private fun readLine(v: VehicleStatus): String? = readAt(v)?.let { "read " + since(it) }

    private fun openApp(context: Context) = PendingIntent.getActivity(context, 40, Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(context: Context, which: String, code: Int) = PendingIntent.getActivity(context, code,
        Intent(Intent.ACTION_VIEW, Uri.parse("volta://action/$which"), context, QuickActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // iOS-style system colours on the dark widget surface.
    private const val LABEL = 0xFFFFFFFF.toInt()
    private const val SECONDARY = 0xFF8E8E93.toInt()
    private const val CHAMPAGNE = 0xFFD6B98A.toInt()
    private const val GREEN = 0xFF30D158.toInt()
    private const val BLUE = 0xFF64D2FF.toInt()
    private const val ORANGE = 0xFFFF9F0A.toInt()
    private const val RED = 0xFFFF453A.toInt()

    /** The AC is running on a car that isn't moving: a pre-cool from the app, or the AC left on from the car. */
    private fun cooling(v: VehicleStatus) = v.climateRunning?.value == true && v.parked()

    /** The one line under the name: the most important thing right now, its icon and colour. */
    private data class State(val text: String, val icon: Int, val color: Int)

    private fun state(context: Context, v: VehicleStatus?): State {
        if (v == null) return State("Open Volta to connect", R.drawable.wg_warning, SECONDARY)
        val worst = carAlerts(v, System.currentTimeMillis(), Alerts.parkedDelayMin(context)).firstOrNull()
        return when {
            worst?.tier == Tier.Act -> State(worst.short, R.drawable.wg_warning, RED)
            v.charging?.value == true -> State("Charging", R.drawable.wg_bolt, GREEN)
            cooling(v) -> State(if (Cooling.session(context) != null) "Cooling" else "AC on", R.drawable.wg_snow, BLUE)
            v.pluggedIn?.value == true -> State("Plugged in", R.drawable.wg_bolt, SECONDARY)
            worst != null -> State(worst.short, R.drawable.wg_warning, ORANGE)
            v.ignitionOn?.value == true -> State("Driving", R.drawable.wg_car, SECONDARY)
            v.locked?.value == false -> State("Unlocked", R.drawable.wg_unlock, ORANGE)
            else -> State("Locked", R.drawable.wg_lock, SECONDARY)
        }
    }

    /** The same car photo as the dashboard: charging, lights, locked or unlocked. */
    private fun carImage(context: Context, v: VehicleStatus?): Int {
        val shot = when {
            v?.charging?.value == true -> "charging"
            v?.lightsOn?.value == true -> "lights"
            v?.locked?.value == false -> "unlocked"
            else -> "locked"
        }
        return context.resources.getIdentifier("car360_${shot}_1", "drawable", context.packageName).takeIf { it != 0 } ?: R.drawable.car360_locked_1
    }

    /** "106 km" with the unit smaller and grey, like an iOS widget's hero number. */
    private fun hero(number: String, unit: String): CharSequence = android.text.SpannableString("$number$unit").apply {
        setSpan(android.text.style.RelativeSizeSpan(0.5f), number.length, length, 0)
        setSpan(android.text.style.ForegroundColorSpan(SECONDARY), number.length, length, 0)
    }

    private fun RemoteViews.common(context: Context, v: VehicleStatus?) {
        val st = state(context, v)
        setTextViewText(R.id.state, st.text)
        setTextColor(R.id.state, st.color)
        setImageViewResource(R.id.state_icon, st.icon)
        setInt(R.id.state_icon, "setColorFilter", st.color)
        setImageViewResource(R.id.car, carImage(context, v))
        setOnClickPendingIntent(android.R.id.background, openApp(context))
    }

    private fun small(context: Context, v: VehicleStatus?, p: VehicleProfile) = RemoteViews(context.packageName, R.layout.widget_small).apply {
        val soc = v?.soc?.value?.socPercent
        common(context, v)
        setTextViewText(R.id.range, rangeKm(v, p)?.let { hero("$it", " km") } ?: "—")
        setTextViewText(R.id.sub, listOfNotNull(soc?.let { "${it.roundToInt()} %" }, v?.let(::readLine)).joinToString(" · "))
        bar(this, v, soc)
    }

    /**
     * What an act-now alert leaves for the line once its name is the headline: "Unlocked for 25 min" becomes
     * "for 25 min"; "Driver door open" stays whole. Then what tapping does about it.
     */
    private fun alertLine(a: CarAlert): List<String> {
        val why = if (a.text.startsWith(a.short)) a.text.removePrefix(a.short).trim().ifEmpty { null } else a.text
        val hint = when (a.fix) {
            RemoteCommand.Lock -> "tap the lock"
            RemoteCommand.WindowsClose -> "tap to close the windows"
            else -> "tap for details"
        }
        return listOfNotNull(why, hint)
    }

    /**
     * The medium widget. Headline: the one act-now alert if there is one (red), else Charging (green), Cooling / AC on
     * (blue), Plugged in, Driving or Parked (champagne). Under it range and charge, then a line that says the
     * detail for that state and always ends with when the car was last read. The car sits big on the right; the
     * pill shows the cabin / outside temperature behind a thermometer, or a snowflake while the AC runs; Lock hides
     * while driving. A warning button appears only for check-soon items (tyres, 12 V), since act-now ones own the
     * headline.
     */
    private fun medium(context: Context, v: VehicleStatus?, p: VehicleProfile, temps: dev.irshadm.volta.data.weather.Temps) =
        RemoteViews(context.packageName, R.layout.widget_medium).apply {
            val now = System.currentTimeMillis()
            val soc = v?.soc?.value?.socPercent
            val charging = v?.charging?.value == true
            val plugged = v?.pluggedIn?.value == true
            val driving = v?.ignitionOn?.value == true
            val acOn = v?.climateRunning?.value == true
            val cooling = v != null && cooling(v)
            val session = if (cooling) Cooling.session(context) else null
            val worst = v?.let { carAlerts(it, now, Alerts.parkedDelayMin(context)) }?.firstOrNull()
            val act = worst?.takeIf { it.tier == Tier.Act }

            val (headline, color) = when {
                v == null -> "Volta" to CHAMPAGNE
                act != null -> act.short to RED
                charging -> "Charging" to GREEN
                cooling -> (if (session != null) "Cooling" else "AC on") to BLUE
                plugged -> "Plugged in" to CHAMPAGNE
                driving -> "Driving" to CHAMPAGNE
                else -> "Parked" to CHAMPAGNE
            }
            setTextViewText(R.id.headline, headline)
            setTextColor(R.id.headline, color)
            setTextViewText(R.id.numbers, if (v == null) "Open Volta to connect" else listOfNotNull(rangeKm(v, p)?.let { "$it km" }, soc?.let { "${it.roundToInt()} %" }).joinToString(" · "))

            val detail: List<String> = when {
                v == null -> emptyList()
                act != null -> alertLine(act)
                charging -> listOf(chargeMinutesLeft(v, p.usableCapacityKwh)?.let { m ->
                    (if (m == 0) "Full" else "Full by ~${clock(now + m * 60_000L)}") +
                        (v.chargePowerKw?.value?.takeIf { it >= 0.5 }?.let { " · %.1f kW".format(it) } ?: "")
                } ?: "Charging")
                cooling -> listOf(session?.let { s -> "~${s.cabinNow(now).roundToInt()}° → ${Cooling.TARGET_C}° · ready ~${clock(s.startedAt + s.minutes * 60_000L)}" } ?: "AC running")
                plugged -> listOf("Not charging")
                driving -> listOf(v.speedKmh?.value?.takeIf { it > 3 }?.let { "${it.roundToInt()} km/h" } ?: "On the road")
                else -> listOfNotNull(v.ignitionChangedAtMillis?.let { "Since ${since(it)}" })
            }
            setTextViewText(R.id.detail, (detail + listOfNotNull(v?.let(::readLine))).joinToString(" · ").replaceFirstChar { it.uppercase() })
            setTextColor(R.id.detail, SECONDARY)
            setImageViewResource(R.id.car, carImage(context, v))

            // The pill: cabin / outside temperature (cabin estimated while parked, like the dashboard). A hot cabin is an
            // orange number behind a thermometer; the snowflake, in blue, means the AC is actually running.
            val cabin = temps.cabinC
            val hot = (cabin ?: 0.0) >= 35
            setImageViewResource(R.id.ac_icon, if (acOn) R.drawable.wg_snow else R.drawable.wg_thermo)
            setInt(R.id.ac_icon, "setColorFilter", if (acOn) BLUE else LABEL)
            setTextViewText(R.id.ac_cabin, cabin?.let { (if (temps.cabinEstimated) "~" else "") + "${it.roundToInt()}°" } ?: "AC")
            setTextColor(R.id.ac_cabin, when { acOn -> BLUE; hot -> ORANGE; else -> LABEL })
            setTextViewText(R.id.ac_outside, temps.outsideC?.let { "/ ${it.roundToInt()}°" } ?: "")
            setContentDescription(R.id.btn_ac, listOfNotNull(if (acOn) "AC running" else "Pre-cool",
                cabin?.let { "cabin ${it.roundToInt()}°" }, temps.outsideC?.let { "outside ${it.roundToInt()}°" }).joinToString(", "))
            setOnClickPendingIntent(R.id.btn_ac, action(context, "precool", 42))

            val locked = v?.locked?.value != false
            setViewVisibility(R.id.btn_lock, if (driving) View.GONE else View.VISIBLE)
            setImageViewResource(R.id.btn_lock, if (locked) R.drawable.wg_lock else R.drawable.wg_unlock)
            setInt(R.id.btn_lock, "setColorFilter", if (locked) LABEL else ORANGE)
            setContentDescription(R.id.btn_lock, if (locked) "Unlock" else "Lock")
            setOnClickPendingIntent(R.id.btn_lock, action(context, if (locked) "unlock" else "lock", 41))

            val check = worst?.takeIf { act == null }
            setViewVisibility(R.id.btn_warn, if (check == null) View.GONE else View.VISIBLE)
            if (check != null) {
                setInt(R.id.btn_warn, "setColorFilter", ORANGE)
                setContentDescription(R.id.btn_warn, check.text)
                setOnClickPendingIntent(R.id.btn_warn, openApp(context))
            }
            setOnClickPendingIntent(android.R.id.background, openApp(context))
        }

    /** Champagne normally, green while charging, orange under 20 % (the bar's fill is tinted). */
    private fun bar(views: RemoteViews, v: VehicleStatus?, soc: Double?) {
        val color = when {
            v?.charging?.value == true -> GREEN
            soc != null && soc < 20 -> ORANGE
            else -> CHAMPAGNE
        }
        if (android.os.Build.VERSION.SDK_INT >= 31)
            views.setColorStateList(R.id.bar, "setProgressTintList", android.content.res.ColorStateList.valueOf(color))
        views.setProgressBar(R.id.bar, 100, soc?.roundToInt() ?: 0, false)
    }
}

class VoltaWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = VoltaWidget.updateAsync(context)
}

class VoltaWidgetMedium : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = VoltaWidget.updateAsync(context)
}
