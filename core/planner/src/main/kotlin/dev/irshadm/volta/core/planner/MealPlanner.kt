package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile

/** Meal windows and the night rule, as minutes after midnight. */
data class MealRules(
    val breakfast: IntRange = 7 * 60..10 * 60,
    val lunch: IntRange = 12 * 60 + 30..14 * 60 + 30,
    val dinner: IntRange = 19 * 60 + 30..21 * 60 + 30,
    /** Stops reached at or after this (or before [nightUntil]) must be right on the road. */
    val nightFrom: Int = 21 * 60,
    val nightUntil: Int = 5 * 60,
    val mealMinutes: Int = 35,
    /** At night, chargers must be right on the road (round-trip detour at most this). */
    val nightMaxDetourM: Double = 600.0,
    /** To eat where there's food, a meal stop may move this far outside the window… */
    val foodSlackMinutes: Int = 45,
    /** …and this far along the road… */
    val foodMoveM: Double = 40_000.0,
    /** …arriving up to this many points under the charger buffer (never under 5 %). */
    val foodSocGrace: Double = 5.0,
) {
    fun isNight(minuteOfDay: Int) = minuteOfDay >= nightFrom || minuteOfDay < nightUntil
    val windows: List<Pair<Meal, IntRange>> get() = listOf(Meal.Breakfast to breakfast, Meal.Lunch to lunch, Meal.Dinner to dinner)
}

data class TimedPlan(val plan: TripPlan, val notes: List<String>)

/** A charger you'd reach during [meal]'s window, at [clock] (minutes after the departure day's midnight). */
data class MealCandidate(val charger: Charger, val meal: Meal, val clock: Double)

/** The user's meal choices for one route: eat at a chosen charger, or skip a meal (eat before leaving / on arrival). */
data class MealChoices(val at: Map<Meal, String> = emptyMap(), val skip: Set<Meal> = emptySet())

/**
 * Fits the time-optimal plan to a departure time:
 *  - When a meal window falls during the drive (and you didn't leave inside it), a charging stop in that
 *    window becomes a meal break (charge for at least [MealRules.mealMinutes]); if none is there, a charger
 *    in the window with food nearby is added as one. The extra charge often removes a later stop.
 *  - A meal stop with no restaurant moves to a charger nearby that has one, even a little outside the window or
 *    arriving a little under the buffer ([MealRules.foodSlackMinutes], [MealRules.foodMoveM], [MealRules.foodSocGrace]).
 *  - Stops reached at night ([MealRules.nightFrom] to [MealRules.nightUntil]) must be right on the road and
 *    not known to close; a 4 am start still gets breakfast and lunch once it's day.
 */
class MealPlanner(
    private val vehicle: VehicleProfile,
    private val settings: TripSettings,
    private val prefs: ChargerPreferences,
    private val rules: MealRules = MealRules(),
) {
    private val planner = TripPlanner(vehicle, settings, prefs)

    /** Minutes of driving from the start to [distanceM] at the profile's speeds. */
    private fun driveMinutesTo(points: List<ProfilePoint>, distanceM: Double, scale: Double): Double {
        var m = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (a.distanceM >= distanceM) break
            val d = minOf(b.distanceM, distanceM) - a.distanceM
            m += d / 1000.0 / ((a.speedKmh + b.speedKmh) / 2) * 60
        }
        return m * scale
    }

    /** Clock (minute of day, may exceed 1440) when you reach [distanceM] following [plan]. */
    private fun clockAt(points: List<ProfilePoint>, plan: TripPlan, departMin: Int, distanceM: Double, scale: Double): Double {
        val stopsBefore = stops(plan).filter { it.charger.alongRouteM < distanceM }.sumOf { it.chargeMinutes.coerceAtLeast(if (it.meal != null) rules.mealMinutes.toDouble() else 0.0) }
        return departMin + driveMinutesTo(points, distanceM, scale) + stopsBefore
    }

    private fun stops(p: TripPlan) = when (val o = p.outcome) { is PlanOutcome.Ok -> o.stops; is PlanOutcome.Unreachable -> o.stops }

    /**
     * Meal windows you'd be driving through (today's and tomorrow's, as absolute clock minutes). A window counts only
     * if you leave before it opens — leaving inside or after it means you've eaten — and the meal would fall at least
     * 30 min after leaving and 20 min before arriving (otherwise eat before you leave / after you arrive).
     */
    private fun windowsDuring(departMin: Int, arriveClock: Double): List<Pair<Meal, ClosedFloatingPointRange<Double>>> =
        listOf(0, 1440).flatMap { off -> rules.windows.map { (m, w) -> m to (w.first + off).toDouble()..(w.last + off).toDouble() } }
            .filter { (_, w) ->
                val mealClock = maxOf(w.start, departMin + 30.0)
                departMin < w.start && mealClock <= w.endInclusive && mealClock <= arriveClock - 20
            }

    /** At night, a charger must be right on the road and not known to close. */
    private fun nightOk(c: Charger) = c.detourM <= rules.nightMaxDetourM && c.open24h != false

    /** Chargers that a meal stop could use, for the caller to look for a restaurant nearby before [plan]. */
    fun mealCandidates(points: List<ProfilePoint>, startSoc: Double, chargers: List<Charger>, driveMinutes: Double, departMin: Int): List<MealCandidate> {
        val base = planner.plan(points, startSoc, chargers, driveMinutes)
        val scale = scale(points, driveMinutes)
        val windows = windowsDuring(departMin, clockAt(points, base, departMin, points.last().distanceM, scale))
        if (windows.isEmpty()) return emptyList()
        // Inside the window first, then the ones a meal stop could move to for food.
        return chargers.mapNotNull { c ->
            val t = clockAt(points, base, departMin, c.alongRouteM, scale)
            windows.firstOrNull { (_, w) -> t in w.widen() }?.let { (meal, w) -> MealCandidate(c, meal, t) to (t !in w) }
        }.sortedWith(compareBy({ it.second }, { it.first.charger.detourM })).take(12).map { it.first }
    }

    private fun ClosedFloatingPointRange<Double>.widen() = (start - rules.foodSlackMinutes)..(endInclusive + rules.foodSlackMinutes)

    /** Battery % used driving from [fromM] to [toM] on [plan] (negative going back), leaving out the charging. */
    private fun usedBetween(plan: TripPlan, fromM: Double, toM: Double): Double {
        val t = plan.socTrace
        fun usedTo(m: Double): Double {
            var u = 0.0
            for (i in 1 until t.size) {
                val a = t[i - 1]; val b = t[i]
                if (b.distanceM - a.distanceM < 1.0) continue                     // a charge, not driving
                if (a.distanceM >= m) break
                val f = ((minOf(b.distanceM, m) - a.distanceM) / (b.distanceM - a.distanceM)).coerceIn(0.0, 1.0)
                u += (a.socPercent - b.socPercent) * f
            }
            return u
        }
        return usedTo(toM) - usedTo(fromM)
    }

    /** [plan] without the stop at [s]: the battery after it carries on from its arrival level. */
    private fun withoutStop(plan: TripPlan, s: Stop): TripPlan {
        val d = s.charger.alongRouteM
        val gain = s.departSoc - s.arriveSoc
        var jumped = false
        val trace = plan.socTrace.mapNotNull { p ->
            when {
                p.distanceM < d - 1 -> p
                kotlin.math.abs(p.distanceM - d) <= 1 -> if (!jumped) { jumped = true; p.copy(socPercent = s.arriveSoc) } else null
                else -> p.copy(socPercent = p.socPercent - gain)
            }
        }
        val outcome = when (val o = plan.outcome) {
            is PlanOutcome.Ok -> PlanOutcome.Ok(o.stops - s, o.arrivalSoc - gain)
            is PlanOutcome.Unreachable -> PlanOutcome.Unreachable(o.stops - s, o.strandedAtM, o.reason)
        }
        return plan.copy(outcome = outcome, socTrace = trace)
    }

    private fun scale(points: List<ProfilePoint>, driveMinutes: Double): Double {
        val raw = driveMinutesTo(points, points.last().distanceM, 1.0)
        return if (raw > 0) driveMinutes / raw else 1.0
    }

    fun plan(
        points: List<ProfilePoint>, startSoc: Double, chargers: List<Charger>, driveMinutes: Double, departMin: Int,
        food: Map<Pair<String, Meal>, String?> = emptyMap(),   // (charger id, meal) → a restaurant nearby serving it (null = none)
        base: TripPlan? = null,
        choices: MealChoices = MealChoices(),
    ): TimedPlan {
        val notes = ArrayList<String>()
        val chargers = chargers.filter { it.network !in prefs.avoidedNetworks }
        val scale = scale(points, driveMinutes)
        val first = base ?: planner.plan(points, startSoc, chargers, driveMinutes)

        // Night applies only to the stops you reach at night (from nightFrom to nightUntil), not to the whole trip:
        // those must be on the road. Daytime stops (and meals) work as usual.
        val pool = chargers.filter { c -> !rules.isNight((clockAt(points, first, departMin, c.alongRouteM, scale) % 1440).toInt()) || nightOk(c) }
        var plan = first
        var usable = chargers
        if (pool.size < chargers.size) {
            val p = planner.plan(points, startSoc, pool, driveMinutes)
            if (p.outcome is PlanOutcome.Ok) { plan = p; usable = pool }
            else notes += "No on-road charger works for the night part — charge more before you leave, or use the stops below (some may be off the main road)."
        }
        if (plan.outcome !is PlanOutcome.Ok) return TimedPlan(plan, notes)

        val minCharger = settings.minArrivalAtChargerPercent.toDouble()
        val done = HashSet<Meal>()
        repeat(3) {
            val arriveClock = clockAt(points, plan, departMin, points.last().distanceM, scale)
            val (meal, w) = windowsDuring(departMin, arriveClock).firstOrNull { it.first !in done && it.first !in choices.skip } ?: return TimedPlan(plan, notes)
            done += meal

            // 0) The user picked where to eat.
            val chosen = choices.at[meal]?.let { id -> usable.firstOrNull { it.id == id } }
            if (chosen != null) {
                val trace = plan.socTrace
                val existing = stops(plan).firstOrNull { it.charger.id == chosen.id }
                val arrive = existing?.arriveSoc ?: ((trace.lastOrNull { it.distanceM <= chosen.alongRouteM }?.socPercent ?: startSoc) -
                    chosen.detourM / 2 / 1000.0 / vehicle.cityKmPerKwh / vehicle.usableCapacityKwh * 100.0)
                plan = forceStop(points, plan, startSoc, usable, driveMinutes, chosen, arrive, meal, food[chosen.id to meal])
                return@repeat
            }

            // 1) An existing stop already falls in the window → make it the meal stop.
            val current = stops(plan)
            val existing = current.firstOrNull { clockAt(points, plan, departMin, it.charger.alongRouteM, scale) in w }
            if (existing != null) {
                // No restaurant there: eat at the nearest charger that has one, a little outside the window or under the buffer.
                val moved = if (food[existing.charger.id to meal] != null) null else usable.asSequence()
                    .filter { c -> food[c.id to meal] != null && c.id != existing.charger.id && rules.foodMoveM >= kotlin.math.abs(c.alongRouteM - existing.charger.alongRouteM) }
                    .filter { c -> clockAt(points, plan, departMin, c.alongRouteM, scale) in w.widen() }
                    .sortedBy { c -> kotlin.math.abs(c.alongRouteM - existing.charger.alongRouteM) }
                    .mapNotNull { c ->
                        val arrive = existing.arriveSoc - usedBetween(plan, existing.charger.alongRouteM, c.alongRouteM) -
                            c.detourM / 2 / 1000.0 / vehicle.cityKmPerKwh / vehicle.usableCapacityKwh * 100.0
                        if (arrive < maxOf(5.0, minCharger - rules.foodSocGrace)) return@mapNotNull null
                        forceStop(points, withoutStop(plan, existing), startSoc, usable, driveMinutes, c, arrive, meal, food[c.id to meal])
                            .takeIf { it.outcome is PlanOutcome.Ok }
                    }.firstOrNull()
                plan = moved ?: forceStop(points, plan, startSoc, usable, driveMinutes, existing.charger, existing.arriveSoc, meal, food[existing.charger.id to meal])
                return@repeat
            }
            // 2) Otherwise add a charger in the window, preferring one with food, then the one closest to the road.
            val trace = plan.socTrace
            fun socAt(c: Charger) = (trace.lastOrNull { it.distanceM <= c.alongRouteM }?.socPercent ?: startSoc) -
                c.detourM / 2 / 1000.0 / vehicle.cityKmPerKwh / vehicle.usableCapacityKwh * 100.0
            fun free(c: Charger) = current.none { kotlin.math.abs(it.charger.alongRouteM - c.alongRouteM) < 5_000 }
            val inWindow = usable.filter { c -> clockAt(points, plan, departMin, c.alongRouteM, scale) in w && socAt(c) >= minCharger && free(c) }
                .sortedWith(compareBy({ food[it.id to meal] == null }, { it.detourM })).firstOrNull()
            // None in the window has food: one with food a little outside it, or reached a little under the buffer.
            val withFood = if (inWindow != null && food[inWindow.id to meal] != null) null else usable.filter { c ->
                food[c.id to meal] != null && clockAt(points, plan, departMin, c.alongRouteM, scale) in w.widen() &&
                    socAt(c) >= maxOf(5.0, minCharger - rules.foodSocGrace) && free(c)
            }.minByOrNull { it.detourM }
            val pick = withFood ?: inWindow
            if (pick == null) {
                notes += "No DC charger around ${meal.label.lowercase()} time on this route — plan a quick food stop on the way."
                return@repeat
            }
            plan = forceStop(points, plan, startSoc, usable, driveMinutes, pick, socAt(pick), meal, food[pick.id to meal])
            if (food[pick.id to meal] == null) notes += "No good restaurant serving ${meal.label.lowercase()} found near the ${meal.label.lowercase()} stop — carry something or eat before you leave."
        }
        return TimedPlan(plan, notes)
    }

    /** Keeps [plan]'s stops before [c], stops at [c] for a meal (charging meanwhile), re-plans the rest. */
    private fun forceStop(
        points: List<ProfilePoint>, plan: TripPlan, startSoc: Double, chargers: List<Charger>, driveMinutes: Double,
        c: Charger, arriveSoc: Double, meal: Meal, eatery: String?,
    ): TripPlan {
        val d = c.alongRouteM
        val before = stops(plan).filter { it.charger.alongRouteM < d - 1 }
        // Charge for the meal: at least the meal duration, at most the charge target.
        val target = settings.maxChargeTargetPercent.toDouble()
        var depart = arriveSoc
        while (depart < target && ChargeTime.minutes(vehicle, arriveSoc, depart + 1, c.maxKw) <= rules.mealMinutes) depart += 1
        val existing = stops(plan).firstOrNull { it.charger.id == c.id }
        if (existing != null && existing.departSoc > depart) depart = existing.departSoc
        val stop = Stop(c, arriveSoc, depart, ChargeTime.minutes(vehicle, arriveSoc, depart, c.maxKw), meal, eatery)

        // Re-plan from the charger with the new charge.
        val restPoints = points.filter { it.distanceM >= d }.map { it.copy(distanceM = it.distanceM - d) }
        if (restPoints.size < 2) return plan
        val restChargers = chargers.filter { it.alongRouteM > d + 1_000 }.map { it.copy(alongRouteM = it.alongRouteM - d) }
        val restMinutes = driveMinutes * (1 - d / points.last().distanceM)
        val rest = planner.plan(restPoints, depart, restChargers, restMinutes)
        val byId = chargers.associateBy { it.id }
        fun unshift(s: Stop) = s.copy(charger = byId[s.charger.id] ?: s.charger.copy(alongRouteM = s.charger.alongRouteM + d))
        val restStops = stops(rest).map(::unshift)
        val outcome = when (val o = rest.outcome) {
            is PlanOutcome.Ok -> PlanOutcome.Ok(before + stop + restStops, o.arrivalSoc)
            is PlanOutcome.Unreachable -> PlanOutcome.Unreachable(before + stop + restStops, o.strandedAtM + d, o.reason)
        }
        val trace = plan.socTrace.filter { it.distanceM < d } + SocPoint(d, arriveSoc, restPoints.first().elevationM) +
            rest.socTrace.map { it.copy(distanceM = it.distanceM + d) }
        return plan.copy(outcome = outcome, socTrace = trace)
    }
}
