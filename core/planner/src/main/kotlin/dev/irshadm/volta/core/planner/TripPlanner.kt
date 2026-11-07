package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.ChargeCurvePoint
import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.NetworkPreference
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import kotlin.math.ceil

/** A DC charger found near the route. */
data class Charger(
    val id: String,
    val name: String,
    val position: LatLng,
    val maxKw: Double,
    /** Distance from the route start to where you leave the route for this charger. */
    val alongRouteM: Double,
    /** Extra distance of the round trip off the route (0 when it's on the route). */
    val detourM: Double = 0.0,
    val connectorCount: Int? = null,
    val availableCount: Int? = null,
    val network: String? = null,
    val address: String? = null,
    /** True when Google lists it as open 24 hours; null when unknown. */
    val open24h: Boolean? = null,
)

data class Stop(
    val charger: Charger,
    val arriveSoc: Double,
    val departSoc: Double,
    val chargeMinutes: Double,
    /** Set when the stop doubles as a meal break (charge while you eat). */
    val meal: Meal? = null,
    val mealPlace: String? = null,
)

enum class Meal(val label: String) { Breakfast("Breakfast"), Lunch("Lunch"), Dinner("Dinner") }

sealed interface PlanOutcome {
    /** Destination reachable; [stops] may be empty. */
    data class Ok(val stops: List<Stop>, val arrivalSoc: Double) : PlanOutcome

    /** Can't be done with the chargers found: battery hits the buffer at [strandedAtM]. */
    data class Unreachable(val stops: List<Stop>, val strandedAtM: Double, val reason: String) : PlanOutcome
}

data class TripPlan(
    val outcome: PlanOutcome,
    /** SoC along the route including jumps at charging stops — for the elevation chart. */
    val socTrace: List<SocPoint>,
    val distanceM: Double,
    val driveMinutes: Double,
    val energy: EnergyBreakdown,
    val usableCapacityKwh: Double,
) {
    val chargeMinutes: Double get() = when (outcome) {
        is PlanOutcome.Ok -> outcome.stops.sumOf { it.chargeMinutes }
        is PlanOutcome.Unreachable -> outcome.stops.sumOf { it.chargeMinutes }
    }

    /** Battery % the terrain adds (climbs minus regen) — "Ghat section: +11 %". */
    val terrainPercent: Double get() = energy.terrainKwh / usableCapacityKwh * 100.0
}

object ChargeTime {
    /** Power available at [soc] from the vehicle's DC curve (linear interpolation). */
    fun curveKw(curve: List<ChargeCurvePoint>, soc: Double): Double {
        if (soc <= curve.first().socPercent) return curve.first().powerKw
        if (soc >= curve.last().socPercent) return curve.last().powerKw
        val hi = curve.indexOfFirst { it.socPercent >= soc }
        val a = curve[hi - 1]
        val b = curve[hi]
        val t = (soc - a.socPercent) / (b.socPercent - a.socPercent)
        return a.powerKw + (b.powerKw - a.powerKw) * t
    }

    /** Minutes to charge from [fromSoc] to [toSoc] on a charger delivering at most [chargerKw]. */
    fun minutes(vehicle: VehicleProfile, fromSoc: Double, toSoc: Double, chargerKw: Double): Double {
        if (toSoc <= fromSoc) return 0.0
        val step = 0.5
        var soc = fromSoc
        var hours = 0.0
        val kwhPerStep = vehicle.usableCapacityKwh * step / 100.0
        while (soc < toSoc) {
            val kw = minOf(curveKw(vehicle.dcCurve, soc + step / 2), chargerKw).coerceAtLeast(1.0)
            hours += kwhPerStep / kw
            soc += step
        }
        return hours * 60.0
    }
}

/**
 * Greedy planner: range check plus greedy stop selection (drive as far as the buffer allows, charge
 * just enough). [OptimalPlanner] runs a time-optimal search over the same inputs.
 */
class TripPlanner(
    private val vehicle: VehicleProfile,
    private val settings: TripSettings,
    private val chargerPrefs: ChargerPreferences = ChargerPreferences(),
) {
    private val model = EnergyModel(vehicle)

    /** Time-optimal options (fastest, fewer stops, preferred). When nothing reaches, one Unreachable plan explains why. */
    fun options(points: List<ProfilePoint>, startSoc: Double, chargers: List<Charger>, driveMinutes: Double): List<PlanOption> {
        require(points.size >= 2) { "Route needs at least two points" }
        val allowed = chargers.filter { it.network !in chargerPrefs.avoidedNetworks }
        val found = OptimalPlanner(vehicle, settings, chargerPrefs).options(points, startSoc, allowed, driveMinutes)
        return found.ifEmpty { listOf(PlanOption(PlanGoal.Fastest, greedy(points, startSoc, allowed, driveMinutes))) }
    }

    /** The fastest option. */
    fun plan(points: List<ProfilePoint>, startSoc: Double, chargers: List<Charger>, driveMinutes: Double): TripPlan = options(points, startSoc, chargers, driveMinutes).first().plan

    /** Greedy "drive as far as the buffer allows" — kept to describe where an impossible trip strands you. */
    private fun greedy(points: List<ProfilePoint>, startSoc: Double, chargers: List<Charger>, driveMinutes: Double): TripPlan {
        val total = points.last().distanceM
        val minCharger = settings.minArrivalAtChargerPercent.toDouble()
        val minDest = settings.minArrivalAtDestinationPercent.toDouble()
        val maxTarget = settings.maxChargeTargetPercent.toDouble()
        val candidates = chargers
            .filter { it.alongRouteM in 1.0..<total && it.detourM <= settings.maxDetourKm * 2_000.0 }
            .filter { chargerPrefs.networkPreference != NetworkPreference.ONLY || it.network in chargerPrefs.preferredNetworks }
            .sortedBy { it.alongRouteM }

        val trace = ArrayList<SocPoint>()
        val stops = ArrayList<Stop>()
        var fromIndex = 0
        var soc = startSoc
        while (true) {
            val leg = points.subList(fromIndex, points.size)
            val sim = model.simulate(leg, soc)
            val arrival = sim.last().socPercent
            if (arrival >= minDest) {
                trace += sim
                return TripPlan(PlanOutcome.Ok(stops, arrival), trace, total, driveMinutes, model.breakdown(points), vehicle.usableCapacityKwh)
            }
            // Where would we fall below the charger buffer?
            val limitM = sim.firstOrNull { it.socPercent < minCharger }?.distanceM ?: total
            val reachable = candidates.filter { c ->
                c.alongRouteM > sim.first().distanceM + 1 && c.alongRouteM <= limitM &&
                    arrivalAt(sim, c) >= minCharger
            }
            val pick = reachable.maxWithOrNull(
                compareBy<Charger> { preferenceScore(it) }.thenBy { it.alongRouteM },
            ).let { best ->
                // Prefer the furthest reachable charger; only let a preferred network win if it's not much earlier.
                val furthest = reachable.maxByOrNull { it.alongRouteM }
                if (best != null && furthest != null && furthest.alongRouteM - best.alongRouteM > 30_000) furthest else best
            }
            if (pick == null) {
                trace += sim.takeWhile { it.distanceM <= limitM }
                return TripPlan(
                    PlanOutcome.Unreachable(stops, limitM, "No reachable DC charger before the battery reaches $minCharger %"),
                    trace,
                    total,
                    driveMinutes,
                    model.breakdown(points),
                    vehicle.usableCapacityKwh,
                )
            }
            val arrive = arrivalAt(sim, pick)
            val idx = points.indexOfFirst { it.distanceM >= pick.alongRouteM }.coerceAtLeast(fromIndex + 1)
            // Charge just enough to finish (or reach the next stop) with buffer, capped at the target.
            val dropToEnd = arrive - arrivalFromHere(points, idx, arrive)
            val needToFinish = dropToEnd + minDest + FINISH_MARGIN
            val depart = minOf(maxTarget, maxOf(arrive + 5.0, roundUp5(needToFinish))).coerceAtMost(100.0)
            if (depart <= arrive + 0.5) {
                trace += sim
                return TripPlan(
                    PlanOutcome.Unreachable(stops, limitM, "Charge target $maxTarget % is too low to continue"),
                    trace,
                    total,
                    driveMinutes,
                    model.breakdown(points),
                    vehicle.usableCapacityKwh,
                )
            }
            stops += Stop(pick, arrive, depart, ChargeTime.minutes(vehicle, arrive, depart, pick.maxKw))
            trace += sim.takeWhile { it.distanceM < pick.alongRouteM }
            trace += SocPoint(points[idx].distanceM, arrive, points[idx].elevationM)
            soc = depart
            fromIndex = idx
            if (stops.size > 20) error("Planner did not converge")
        }
    }

    /** SoC at the charger, including half the detour driven at city efficiency. */
    private fun arrivalAt(sim: List<SocPoint>, c: Charger): Double {
        val atExit = sim.lastOrNull { it.distanceM <= c.alongRouteM }?.socPercent ?: sim.first().socPercent
        val detourKwh = c.detourM / 2 / 1000.0 / vehicle.cityKmPerKwh
        return atExit - detourKwh / vehicle.usableCapacityKwh * 100.0
    }

    /** Arrival SoC at the destination if leaving point [idx] with [soc]. */
    private fun arrivalFromHere(points: List<ProfilePoint>, idx: Int, soc: Double): Double = model.simulate(points.subList(idx, points.size), soc, capSoc = 1_000.0).last().socPercent

    private fun preferenceScore(c: Charger): Int = when {
        chargerPrefs.networkPreference == NetworkPreference.PREFER && c.network in chargerPrefs.preferredNetworks -> 1
        else -> 0
    }

    private fun roundUp5(x: Double) = ceil(x / 5.0) * 5.0

    private companion object {
        /** Extra % on top of the destination buffer when deciding how much to charge. */
        const val FINISH_MARGIN = 3.0
    }
}
