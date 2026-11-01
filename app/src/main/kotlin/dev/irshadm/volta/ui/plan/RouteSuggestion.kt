package dev.irshadm.volta.ui.plan

import dev.irshadm.volta.data.maps.RouteChoice
import kotlin.math.roundToInt

/**
 * Which of Google's routes to suggest for an EV, and the facts behind it. Score = drive time plus what the
 * route costs the battery: climbing (about 3 min of extra charging per 100 m), and a long stretch with no
 * DC charger. Ties within 5 min go to fewer tolls, then the shorter route.
 */
data class RouteSuggestion(val index: Int, val name: String, val facts: List<String>, val fallback: String) {
    companion object {
        private fun name(c: RouteChoice, i: Int) = c.route.label ?: "route ${i + 1}"

        private fun score(c: RouteChoice): Double =
            c.route.durationS / 60 + c.climbM / 100 * 3 + if (c.longestGapKm >= 70) 20.0 else 0.0

        fun pick(routes: List<RouteChoice>): RouteSuggestion {
            val scores = routes.map(::score)
            val best = scores.min()
            val index = routes.indices.filter { scores[it] <= best + 5 }
                .sortedWith(compareBy({ routes[it].route.tolls }, { routes[it].route.distanceM })).first()
            val facts = routes.mapIndexed { i, c ->
                "${name(c, i)}: ${(c.route.distanceM / 1000).roundToInt()} km, ${duration(c.route.durationS / 60)} drive" +
                    (if (c.trafficDelayMin >= 5) " incl. ${c.trafficDelayMin.roundToInt()} min traffic" else "") +
                    ", ${"%,d".format(c.climbM.roundToInt())} m of climbing, highest point ${c.peakM.roundToInt()} m, " +
                    "${c.chargers.size} DC chargers, longest gap without a charger ${c.longestGapKm.roundToInt()} km, " +
                    if (c.route.tolls) "has tolls" else "no tolls"
            }
            return RouteSuggestion(index, name(routes[index], index), facts, fallback(routes, index))
        }

        /** A plain reason, used until (or instead of) Gemini Nano's. */
        private fun fallback(routes: List<RouteChoice>, i: Int): String {
            val p = routes[i]
            val others = routes.filterIndexed { j, _ -> j != i }
            val reasons = buildList {
                val fastestOther = others.minOf { it.route.durationS }
                val diff = (fastestOther - p.route.durationS) / 60
                when {
                    diff >= 5 -> add("${diff.roundToInt()} min quicker")
                    diff > -5 -> add("about as quick")
                }
                if (others.all { p.climbM < it.climbM * 0.8 }) add("less climbing, so easier on the battery")
                if (!p.route.tolls && others.any { it.route.tolls }) add("no tolls")
                if (others.any { it.longestGapKm >= 70 } && p.longestGapKm < 70) add("chargers all along the way")
            }
            return if (reasons.isEmpty()) "The best balance of drive time and battery use."
            else reasons.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        }
    }
}
