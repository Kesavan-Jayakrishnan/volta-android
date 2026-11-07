package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.NetworkPreference
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import java.util.PriorityQueue
import kotlin.math.ceil

enum class PlanGoal(val label: String) { Fastest("Fastest"), FewerStops("Fewer stops"), Preferred("Preferred") }

data class PlanOption(val goal: PlanGoal, val plan: TripPlan)

/**
 * time-optimal stop selection.
 *
 * Dijkstra over (charger, departure-SoC) states. Driving time along the route is the same for
 * every plan, so the cost is charging time + detour time + a fixed per-stop overhead. Departure
 * levels are 5 % steps up to the charge target plus the exact "just enough" level, so the search
 * can trade one long stop for two short fast ones (charging slows above ~50 %).
 */
class OptimalPlanner(
    private val vehicle: VehicleProfile,
    private val settings: TripSettings,
    private val prefs: ChargerPreferences,
) {
    private val model = EnergyModel(vehicle)

    private data class Node(val charger: Int, val departSoc: Double)
    private data class Entry(val node: Node, val cost: Double, val stops: Int)

    /** Up to three distinct options (fastest, fewer stops, preferred network). Empty when nothing reaches. */
    fun options(points: List<ProfilePoint>, startSoc: Double, chargers: List<Charger>, driveMinutes: Double): List<PlanOption> {
        val out = ArrayList<PlanOption>()
        fun add(goal: PlanGoal, plan: TripPlan?) {
            if (plan == null) return
            val key = (plan.outcome as PlanOutcome.Ok).stops.map { it.charger.id to it.departSoc.toInt() }
            if (out.none { o -> (o.plan.outcome as PlanOutcome.Ok).stops.map { it.charger.id to it.departSoc.toInt() } == key }) out += PlanOption(goal, plan)
        }
        val usable = when (prefs.networkPreference) {
            NetworkPreference.ONLY -> chargers.filter { it.network in prefs.preferredNetworks }
            else -> chargers
        }
        add(PlanGoal.Fastest, search(points, startSoc, usable, driveMinutes, stopWeight = 0.0))
        add(PlanGoal.FewerStops, search(points, startSoc, usable, driveMinutes, stopWeight = 1_000.0))
        if (prefs.networkPreference == NetworkPreference.PREFER && prefs.preferredNetworks.isNotEmpty()) {
            add(PlanGoal.Preferred, search(points, startSoc, chargers.filter { it.network in prefs.preferredNetworks }, driveMinutes, 0.0))
        }
        return out
    }

    private fun search(points: List<ProfilePoint>, startSoc: Double, chargers: List<Charger>, driveMinutes: Double, stopWeight: Double): TripPlan? {
        val total = points.last().distanceM
        val cap = vehicle.usableCapacityKwh
        val minCharger = settings.minArrivalAtChargerPercent.toDouble()
        val minDest = settings.minArrivalAtDestinationPercent.toDouble()
        val maxTarget = settings.maxChargeTargetPercent.toDouble()
        val cum = cumulativeKwh(points)
        val list = chargers.filter { it.alongRouteM in 1.0..<total && it.detourM <= settings.maxDetourKm * 2_000.0 }.sortedBy { it.alongRouteM }
        val idx = IntArray(list.size) { j -> points.indexOfFirst { it.distanceM >= list[j].alongRouteM }.coerceAtLeast(1) }
        fun drop(a: Int, b: Int) = (cum[b] - cum[a]) / cap * 100.0
        fun halfDetour(j: Int) = list[j].detourM / 2 / 1000.0 / vehicle.cityKmPerKwh / cap * 100.0
        fun detourMin(j: Int) = list[j].detourM / 1000.0 / DETOUR_KMH * 60.0
        fun bonus(j: Int) = if (prefs.networkPreference == NetworkPreference.PREFER && list[j].network in prefs.preferredNetworks) PREFERRED_BONUS_MIN else 0.0
        val end = points.lastIndex

        // Direct drive?
        if (startSoc - drop(0, end) >= minDest) return build(points, startSoc, emptyList(), driveMinutes)

        val best = HashMap<Node, Double>()
        val prev = HashMap<Node, Pair<Node?, Double>>() // node -> (previous node, arrival SoC at this node)
        val queue = PriorityQueue<Entry>(compareBy { it.cost })
        var bestEnd: Pair<Node, Double>? = null
        var bestEndCost = Double.MAX_VALUE

        fun departures(j: Int, arrive: Double, toEnd: Double): List<Double> {
            val levels = sortedSetOf<Double>()
            var d = ceil((arrive + MIN_GAIN) / 5) * 5
            while (d <= maxTarget + 1e-9) {
                levels += d
                d += 5
            }
            val enough = ceil(toEnd + minDest + halfDetour(j) + FINISH_MARGIN)
            if (enough >= arrive + MIN_GAIN && enough <= maxTarget) levels += enough
            return levels.toList()
        }

        fun relax(from: Node?, fromCost: Double, fromStops: Int, depart: Double, fromIdx: Int, fromHalfDetour: Double) {
            // to destination
            val atEnd = depart - fromHalfDetour - drop(fromIdx, end)
            if (from != null && atEnd >= minDest && fromCost < bestEndCost) {
                bestEndCost = fromCost
                bestEnd = from to atEnd
            }
            for (k in list.indices) {
                if (idx[k] <= fromIdx || (from != null && k <= from.charger)) continue
                val arrive = depart - fromHalfDetour - drop(fromIdx, idx[k]) - halfDetour(k)
                if (arrive < minCharger) {
                    if (drop(fromIdx, idx[k]) > 100) break else continue
                }
                if (arrive > MAX_ARRIVE_SOC) continue // nobody stops to charge with half a battery left
                for (dep in departures(k, arrive, drop(idx[k], end))) {
                    val cost = fromCost + stopWeight + STOP_OVERHEAD_MIN + detourMin(k) +
                        ChargeTime.minutes(vehicle, arrive, dep, list[k].maxKw) - bonus(k)
                    val node = Node(k, dep)
                    if (cost < (best[node] ?: Double.MAX_VALUE)) {
                        best[node] = cost
                        prev[node] = from to arrive
                        queue += Entry(node, cost, fromStops + 1)
                    }
                }
            }
        }

        relax(null, 0.0, 0, startSoc, 0, 0.0)
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            if (e.cost > (best[e.node] ?: Double.MAX_VALUE) + 1e-9 || e.cost >= bestEndCost) continue
            relax(e.node, e.cost, e.stops, e.node.departSoc, idx[e.node.charger], halfDetour(e.node.charger))
        }
        val (last, _) = bestEnd ?: return null
        val chain = ArrayList<Stop>()
        var n: Node? = last
        while (n != null) {
            val (p, arrive) = prev.getValue(n)
            val c = list[n.charger]
            chain += Stop(c, arrive, n.departSoc, ChargeTime.minutes(vehicle, arrive, n.departSoc, c.maxKw))
            n = p
        }
        return build(points, startSoc, chain.reversed(), driveMinutes)
    }

    /** Re-simulates the chosen plan for the chart (SoC jumps at each stop). */
    private fun build(points: List<ProfilePoint>, startSoc: Double, stops: List<Stop>, driveMinutes: Double): TripPlan {
        val trace = ArrayList<SocPoint>()
        var from = 0
        var soc = startSoc
        for (s in stops) {
            val i = points.indexOfFirst { it.distanceM >= s.charger.alongRouteM }.coerceAtLeast(from + 1)
            val leg = model.simulate(points.subList(from, i + 1), soc)
            trace += leg.dropLast(1)
            trace += SocPoint(points[i].distanceM, s.arriveSoc, points[i].elevationM)
            from = i
            soc = s.departSoc
        }
        val last = model.simulate(points.subList(from, points.size), soc)
        trace += last
        val detourMin = stops.sumOf { it.charger.detourM / 1000.0 / DETOUR_KMH * 60.0 }
        return TripPlan(
            PlanOutcome.Ok(stops, last.last().socPercent),
            trace,
            points.last().distanceM,
            driveMinutes + detourMin,
            model.breakdown(points),
            vehicle.usableCapacityKwh,
        )
    }

    /** Cumulative energy (kWh) from the start to each point, same terrain rules as [EnergyModel]. */
    private fun cumulativeKwh(points: List<ProfilePoint>): DoubleArray {
        val sim = model.simulate(points, startSoc = 1_000.0, capSoc = 10_000.0)
        val cap = vehicle.usableCapacityKwh
        return DoubleArray(points.size) { k -> (1_000.0 - sim[k].socPercent) / 100.0 * cap }
    }

    companion object {
        const val STOP_OVERHEAD_MIN = 10.0 // pull in, park, plug in, start/pay, unplug
        const val MIN_GAIN = 10.0 // a stop must add at least this many %
        const val MAX_ARRIVE_SOC = 50.0
        const val DETOUR_KMH = 35.0
        const val PREFERRED_BONUS_MIN = 4.0
        const val FINISH_MARGIN = 3.0
    }
}
