package dev.irshadm.volta.data.maps

import android.content.Context
import dev.irshadm.volta.core.planner.Charger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** How a charge went, from the one-tap prompt after leaving a stop. */
enum class ChargeVerdict(val label: String) { Worked("Worked"), Waited("Had to wait"), Broken("Broken") }

/**
 * Your own history with each charger (kept on the phone): what you said after each charge there. Public maps
 * don't know that a charger has been broken for a week; this does, for the places you actually use.
 */
@Singleton
class ChargerFeedback @Inject constructor(@ApplicationContext private val context: Context) {
    data class Entry(val verdict: ChargeVerdict, val at: Long)

    private val prefs = context.getSharedPreferences("charger_feedback", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    /** Bumps on every change, so screens can re-read. */
    val version: StateFlow<Int> = _version

    fun history(chargerId: String): List<Entry> = runCatching {
        val a = JSONArray(prefs.getString(chargerId, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Entry(ChargeVerdict.valueOf(o.getString("v")), o.getLong("at")) } }
    }.getOrDefault(emptyList())

    fun add(chargerId: String, verdict: ChargeVerdict, at: Long = System.currentTimeMillis()) {
        val a = JSONArray(prefs.getString(chargerId, "[]")).put(JSONObject().put("v", verdict.name).put("at", at))
        // Keep the last 10.
        val trimmed = JSONArray().apply { for (i in maxOf(0, a.length() - 10) until a.length()) put(a.get(i)) }
        prefs.edit().putString(chargerId, trimmed.toString()).apply()
        _version.value++
    }

    /** Marked broken in the last two weeks, and not reported working since. The planner skips these. */
    fun recentlyBroken(chargerId: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = history(chargerId).lastOrNull() ?: return false
        return last.verdict == ChargeVerdict.Broken && now - last.at < 14 * DAY
    }

    companion object { const val DAY = 86_400_000L }
}

/** A 1–5 rating for a planned stop, why, and the stop to use if it lets you down. */
data class Confidence(val dots: Int, val reasons: List<String>, val backup: Charger?, val backupKm: Double?) {
    val label: String get() = when { dots >= 4 -> "Reliable"; dots == 3 -> "Good"; else -> "Unsure" }
    val unsure: Boolean get() = dots <= 2

    companion object {
        /**
         * Built from live free plugs, 24-hour opening, and your own history there. The backup is the nearest
         * other charger within 15 km along the route (before or after).
         */
        fun of(c: Charger, all: List<Charger>, history: List<ChargerFeedback.Entry>, now: Long = System.currentTimeMillis()): Confidence {
            var score = 3.0
            val why = ArrayList<String>()
            when {
                c.availableCount == null -> Unit
                c.availableCount == 0 -> score -= 1          // the card already shows "0 of 2 free"
                else -> score += 0.5
            }
            if (c.open24h == true) { score += 0.5; why += "24 h" }
            val worked = history.count { it.verdict == ChargeVerdict.Worked }
            val waited = history.count { it.verdict == ChargeVerdict.Waited && now - it.at < 60 * ChargerFeedback.DAY }
            val broken = history.lastOrNull { it.verdict == ChargeVerdict.Broken }
            if (worked > 0) { score += minOf(1.0, worked * 0.5); why += "worked for you ${times(worked)}" }
            if (waited > 0) { score -= 0.5; why += "you waited here ${times(waited)}" }
            if (broken != null && now - broken.at < 30 * ChargerFeedback.DAY && history.last().verdict == ChargeVerdict.Broken) {
                score -= 2; why += "you marked it broken ${daysAgo(now - broken.at)}"
            }
            val backup = all.filter { it.id != c.id && abs(it.alongRouteM - c.alongRouteM) < 15_000 }
                .minByOrNull { abs(it.alongRouteM - c.alongRouteM) }
            return Confidence(score.toInt().coerceIn(1, 5), why, backup, backup?.let { (it.alongRouteM - c.alongRouteM) / 1000 })
        }

        private fun times(n: Int) = if (n == 1) "once" else "$n times"
        private fun daysAgo(ms: Long) = (ms / ChargerFeedback.DAY).let { if (it == 0L) "today" else if (it == 1L) "yesterday" else "$it days ago" }
    }
}
