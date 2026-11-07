package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile

/** Where the car is relative to the plan. */
data class Progress(
    val alongRouteM: Double,
    val offRouteM: Double,
    val expectedSoc: Double,
    val actualSoc: Double,
) {
    val deviation: Double get() = actualSoc - expectedSoc
}

object Replanner {
    /** Nearest route point to [position] (distance along the route and how far off it we are). */
    fun locate(profile: List<ProfilePoint>, path: List<RoutePoint>, position: LatLng): Pair<Double, Double> {
        val nearest = path.minBy { Geo.distanceM(it.position, position) }
        return nearest.distanceM to Geo.distanceM(nearest.position, position)
    }

    /** Expected SoC at [alongM] from the plan's SoC trace (the last point at or before it). */
    fun expectedSoc(plan: TripPlan, alongM: Double): Double = (plan.socTrace.lastOrNull { it.distanceM <= alongM } ?: plan.socTrace.first()).socPercent

    /**
     * re-plan from the current position. The remaining profile and the chargers ahead are
     * re-based so the remaining trip starts at 0 m; results are shifted back to whole-route distances.
     */
    fun replanFrom(
        vehicle: VehicleProfile,
        settings: TripSettings,
        prefs: ChargerPreferences,
        profile: List<ProfilePoint>,
        chargers: List<Charger>,
        alongM: Double,
        soc: Double,
        remainingDriveMinutes: Double,
    ): List<PlanOption> {
        val from = profile.indexOfLast { it.distanceM <= alongM }.coerceIn(0, profile.size - 2)
        val base = profile[from].distanceM
        val rest = profile.subList(from, profile.size).map { it.copy(distanceM = it.distanceM - base) }
        val ahead = chargers.filter { it.alongRouteM > alongM + 200 }.map { it.copy(alongRouteM = it.alongRouteM - base) }
        return TripPlanner(vehicle, settings, prefs).options(rest, soc, ahead, remainingDriveMinutes).map { o ->
            o.copy(plan = shift(o.plan, base, chargers))
        }
    }

    private fun shift(plan: TripPlan, base: Double, original: List<Charger>): TripPlan {
        fun orig(c: Charger) = original.firstOrNull { it.id == c.id } ?: c.copy(alongRouteM = c.alongRouteM + base)
        val outcome = when (val o = plan.outcome) {
            is PlanOutcome.Ok -> o.copy(stops = o.stops.map { it.copy(charger = orig(it.charger)) })
            is PlanOutcome.Unreachable -> o.copy(stops = o.stops.map { it.copy(charger = orig(it.charger)) }, strandedAtM = o.strandedAtM + base)
        }
        return plan.copy(outcome = outcome, socTrace = plan.socTrace.map { it.copy(distanceM = it.distanceM + base) })
    }
}
