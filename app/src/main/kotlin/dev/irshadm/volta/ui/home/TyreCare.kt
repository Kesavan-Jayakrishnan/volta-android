package dev.irshadm.volta.ui.home

import android.content.Context
import dev.irshadm.volta.ui.components.Wheel
import dev.irshadm.volta.core.model.TyrePressures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.util.Calendar

/**
 * Tyres as something to check, not a warning: a wheel counts when it sits [GAP] psi or more under the placard
 * target. "Checked it" remembers the pressures you saw, and that wheel stays quiet until it drops further.
 * The trend comes from the daily health log the background check keeps (one cold reading a day).
 */
object TyreCare {
    private const val PREFS = "tyres"
    const val DEFAULT_TARGET = 33.0
    /** Under the target by this much is worth a look. */
    const val GAP = 2.0

    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so screens re-read. */
    val version: StateFlow<Int> = _version

    data class Check(val wheel: Wheel, val psi: Double, val gap: Double)
    data class Trend(val wheel: Wheel, val series: List<Double>, val dropPsi: Double, val days: Int, val since: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The placard pressure (Settings, or the door sticker); the same for all four on the Windsor. */
    fun target(context: Context): Double = prefs(context).getFloat("target", DEFAULT_TARGET.toFloat()).toDouble()

    fun setTarget(context: Context, psi: Double) {
        prefs(context).edit().putFloat("target", psi.coerceIn(24.0, 45.0).toFloat()).apply()
        _version.value++
    }

    /**
     * Wheels under the target that you haven't already looked at, lowest first. When the car itself flags tyre
     * pressure ([carFlag], its warning code 4) the lowest wheel is always included: "Checked it" quiets Volta's
     * own check, not the car's.
     */
    fun checks(context: Context, t: TyrePressures?, target: Double = target(context), carFlag: Boolean = false): List<Check> {
        if (t == null) return emptyList()
        val p = prefs(context)
        val own = Wheel.entries.mapNotNull { w ->
            val psi = w.psi(t)
            val gap = target - psi
            val ack = p.getFloat("ack_${w.name}", Float.NaN)
            val seen = !ack.isNaN() && psi >= ack - 0.5          // acknowledged at this pressure (or it has come back up)
            if (gap >= GAP && !seen) Check(w, psi, gap) else null
        }.sortedByDescending { it.gap }
        if (own.isNotEmpty() || !carFlag) return own
        val lowest = Wheel.entries.minBy { it.psi(t) }
        return listOf(Check(lowest, lowest.psi(t), target - lowest.psi(t)))
    }

    /** True when the car's own warning codes include low tyre pressure. */
    fun carFlag(codes: List<Int>?): Boolean = codes?.contains(4) == true

    /** "Checked it": remember today's pressures; a wheel speaks up again only if it drops another half psi. */
    fun acknowledge(context: Context, t: TyrePressures) {
        prefs(context).edit().apply { Wheel.entries.forEach { putFloat("ack_${it.name}", it.psi(t).toFloat()) } }.apply()
        _version.value++
    }

    /** Pressure over the last week per wheel, from the daily health log (empty when there's less than two days). */
    fun trends(context: Context): List<Trend> {
        val log = runCatching { JSONArray(context.getSharedPreferences("alerts", Context.MODE_PRIVATE).getString("health_log", "[]")) }.getOrDefault(JSONArray())
        val days = (0 until log.length()).map { log.getJSONObject(it) }.filter { it.has("fl") }.takeLast(8)
        if (days.size < 2) return emptyList()
        val first = days.first()
        val last = days.last()
        val span = (last.getInt("d") % 1000) - (first.getInt("d") % 1000)
        val since = Calendar.getInstance().apply { set(Calendar.DAY_OF_YEAR, first.getInt("d") % 1000) }
            .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.ENGLISH) ?: "last week"
        return Wheel.entries.map { w ->
            val key = when (w) { Wheel.FrontLeft -> "fl"; Wheel.FrontRight -> "fr"; Wheel.RearLeft -> "rl"; Wheel.RearRight -> "rr" }
            val series = days.map { it.getDouble(key) }
            Trend(w, series, series.first() - series.last(), span, since)
        }
    }
}
