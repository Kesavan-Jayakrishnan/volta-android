package dev.irshadm.volta.ui.plan

import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.core.planner.PlanOutcome
import dev.irshadm.volta.core.planner.Stop
import dev.irshadm.volta.data.maps.MealSpot
import dev.irshadm.volta.data.maps.PlannedTrip

/**
 * The plan as places for the journey preview: the start, each stop with its clock times and meal spot, and the
 * destination, plus the route at full detail so any leg between them can be drawn on its own.
 * Clock times come from [schedule].
 */
class Journey(val trip: PlannedTrip, val startSoc: Double, val sched: Schedule) {

    class JStop(val index: Int, val stop: Stop, val atM: Double, val arriveAt: Double, val leaveAt: Double, val spot: MealSpot?) {
        val minutes: Double get() = (leaveAt - arriveAt) / 60_000
    }

    val points: List<LatLng>
    val dist: DoubleArray
    val totalM: Double = trip.route.distanceM

    val stops: List<JStop>
    val arrivalSoc: Double = (trip.plan.outcome as? PlanOutcome.Ok)?.arrivalSoc ?: 0.0
    val arriveAt: Double = sched.endAt

    init {
        val pts = ArrayList<LatLng>(trip.route.path.size)
        val ds = ArrayList<Double>(trip.route.path.size)
        trip.route.path.forEach { p ->
            if (pts.isEmpty()) { pts += p; ds += 0.0 }
            else { val d = Geo.distanceM(pts.last(), p); if (d > 0.5) { pts += p; ds += ds.last() + d } }
        }
        val scale = if (ds.last() > 0) totalM / ds.last() else 1.0
        points = pts
        dist = ds.map { it * scale }.toDoubleArray()

        stops = stopsOf(trip.plan.outcome).mapIndexed { i, s -> JStop(i, s, s.charger.alongRouteM, sched.arriveAt[i], sched.leaveAt[i], mealSpot(trip, s)) }
    }

    fun indexAt(m: Double): Int {
        var lo = 0; var hi = dist.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (dist[mid] <= m) lo = mid else hi = mid }
        return lo
    }

    fun positionAt(m: Double): LatLng {
        val i = indexAt(m); val j = (i + 1).coerceAtMost(points.lastIndex)
        val f = if (dist[j] > dist[i]) ((m - dist[i]) / (dist[j] - dist[i])).coerceIn(0.0, 1.0) else 0.0
        return LatLng(points[i].lat + (points[j].lat - points[i].lat) * f, points[i].lng + (points[j].lng - points[i].lng) * f)
    }

    /** The road from [fromM] to [toM] along the route. */
    fun pathBetween(fromM: Double, toM: Double): List<LatLng> {
        val a = indexAt(fromM); val b = indexAt(toM)
        return listOf(positionAt(fromM)) + (if (b > a) points.subList(a + 1, b + 1) else emptyList()) + positionAt(toM)
    }
}
