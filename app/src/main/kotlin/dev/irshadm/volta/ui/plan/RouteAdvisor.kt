package dev.irshadm.volta.ui.plan

import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.planner.Charger
import dev.irshadm.volta.core.planner.MealRules
import dev.irshadm.volta.core.planner.PlanOutcome
import dev.irshadm.volta.core.planner.Stop
import dev.irshadm.volta.data.maps.PlannedTrip
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One heads-up shown above the plan. */
data class Advice(val kind: Kind, val text: String, val stop: Int? = null) {
    /** In priority order: the list is sorted by this and cut to the top few. */
    enum class Kind { Battery, Gap, Night, Climb, Break, Dark, Meal }
}

/**
 * The few things worth knowing before you go, worked out from the route and plan (no AI): low battery,
 * long stretches without DC chargers, night stops that may be closed,
 * the big climb, long legs without a break, arriving on hill roads after dark, and meal stops the planner
 * couldn't place. Meals that were placed show on their stop cards, so they aren't repeated here.
 * Tips about what happens at or right after a stop carry its index in [Advice.stop] and show on that
 * stop's card; [advise] keeps the trip-wide ones (at most [MAX]).
 */
object RouteAdvisor {
    private const val MAX = 4
    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
    private fun time(ms: Long) = clock.format(Date(ms)).lowercase()
    private fun minuteOfDay(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    fun advise(trip: PlannedTrip, startSoc: Double, vehicle: VehicleProfile): List<Advice> = all(trip, startSoc, vehicle).filter { it.stop == null }.take(MAX)

    /** Stop index → the tips to show on that stop's card. */
    fun perStop(trip: PlannedTrip, startSoc: Double, vehicle: VehicleProfile): Map<Int, List<Advice>> = all(trip, startSoc, vehicle).filter { it.stop != null }.groupBy { it.stop!! }.mapValues { it.value.take(2) }

    private fun all(trip: PlannedTrip, startSoc: Double, vehicle: VehicleProfile): List<Advice> {
        val out = ArrayList<Advice>()
        val plan = trip.plan
        val stops: List<Stop> = when (val o = plan.outcome) {
            is PlanOutcome.Ok -> o.stops
            is PlanOutcome.Unreachable -> o.stops
        }
        val total = trip.route.distanceM
        val names = listOf("the start") + stops.map { it.charger.name.take(28) } + listOf(trip.destination.name)
        val bounds = listOf(0.0) + stops.map { it.charger.alongRouteM } + listOf(total)
        val rules = MealRules()

        // Clock at each stop and at the destination, and minutes driven on each leg.
        // Same per-leg drive times as the timeline (from the profile's speeds).
        val legMinutes = bounds.zipWithNext { a, b ->
            trip.profile.filter { it.distanceM in a..b }.zipWithNext { p, q -> (q.distanceM - p.distanceM) / 1000 / q.speedKmh * 60 }.sum()
        }
        val arriveAt = ArrayList<Long>()
        var t = trip.departAt.toDouble()
        legMinutes.forEachIndexed { i, m ->
            t += m * 60_000
            arriveAt += t.toLong()
            stops.getOrNull(i)?.let { t += maxOf(it.chargeMinutes, if (it.meal != null) rules.mealMinutes.toDouble() else 0.0) * 60_000 }
        }

        // Battery: low start, low arrival.
        (plan.outcome as? PlanOutcome.Ok)?.let { o ->
            if (o.arrivalSoc < 20) out += Advice(Advice.Kind.Battery, "You arrive with ${o.arrivalSoc.roundToInt()} % — find a charger near ${trip.destination.name} for the way back.")
        }
        if (startSoc < 30 && stops.isNotEmpty()) out += Advice(Advice.Kind.Battery, "Leaving with ${startSoc.roundToInt()} % adds a stop — charging at home first would save time.")

        // The longest stretch without any DC charger near the route.
        val along = (listOf(0.0) + trip.chargers.map(Charger::alongRouteM).sorted() + listOf(total))
        along.zipWithNext().maxByOrNull { it.second - it.first }?.let { (a, b) ->
            if (b - a >= 70_000) out += Advice(Advice.Kind.Gap, "${((b - a) / 1000).roundToInt()} km with no DC charger (km ${(a / 1000).roundToInt()}–${(b / 1000).roundToInt()}). Leave that stretch with a healthy charge.")
        }

        // Stops reached at night that aren't known to be 24-hour.
        stops.forEachIndexed { i, s ->
            if (rules.isNight(minuteOfDay(arriveAt[i])) && s.charger.open24h != true) {
                out += Advice(Advice.Kind.Night, "You get here at ${time(arriveAt[i])} and it isn't listed as 24-hour — check it's open.", i)
            }
        }

        // Real climbs into the hills (net rise, not the ups and downs of rolling roads): on the first leg it's
        // trip-wide, after a stop it goes on that stop's card.
        for (i in 0 until bounds.size - 1) {
            val pts = trip.profile.filter { it.distanceM in bounds[i]..bounds[i + 1] }
            val start = pts.firstOrNull()?.elevationM ?: continue
            val top = pts.maxOf { it.elevationM }
            val rise = top - start
            if (rise < 300) continue
            val climbPct = (vehicle.massKg * 9.81 * rise / 3.6e6 / vehicle.driveEfficiency / vehicle.usableCapacityKwh * 100).roundToInt()
            val to = "%,d".format(top.roundToInt())
            out += if (i == 0) {
                Advice(Advice.Kind.Climb, "The road climbs to $to m on the first leg — about $climbPct % of the battery for the climb alone.")
            } else {
                Advice(Advice.Kind.Climb, "Don't skip this one — the road climbs to $to m next (+${"%,d".format(rise.roundToInt())} m, about $climbPct % of the battery).", i - 1)
            }
        }

        // Long legs with no stop: before the first stop it's trip-wide, otherwise on the stop that starts the leg.
        legMinutes.forEachIndexed { i, m ->
            if (m < 150) return@forEachIndexed
            out += if (i == 0) {
                Advice(Advice.Kind.Break, "${duration(m)} of driving to ${names[1]} without a stop — take a short break on the way.")
            } else {
                Advice(Advice.Kind.Break, "${duration(m)} to ${names[i + 1]} after this — take a short break on the way.", i - 1)
            }
        }

        // Arriving on hill roads after dark.
        val hilly = trip.profile.maxOfOrNull { it.elevationM }?.let { it > 600 } == true
        val end = arriveAt.last()
        val h = minuteOfDay(end)
        if (hilly && (h >= 18 * 60 + 45 || h < 6 * 60) && minuteOfDay(trip.departAt) in 6 * 60..17 * 60) {
            out += Advice(Advice.Kind.Dark, "You arrive around ${time(end)}, after dark, on hill roads — leave earlier if you can.")
        }

        // Meals the planner couldn't place.
        trip.notes.forEach { out += Advice(Advice.Kind.Meal, it) }
        return out.sortedBy { it.kind.ordinal }
    }
}
