package dev.irshadm.volta.data.maps

import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.planner.Charger
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.core.planner.ProfilePoint
import dev.irshadm.volta.core.planner.RoutePoint
import dev.irshadm.volta.core.planner.PlanOption
import dev.irshadm.volta.core.planner.TripPlan
import dev.irshadm.volta.core.planner.TripPlanner
import dev.irshadm.volta.core.planner.MealPlanner
import dev.irshadm.volta.core.planner.MealRules
import dev.irshadm.volta.core.planner.Meal
import dev.irshadm.volta.core.planner.MealChoices
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.security.MessageDigest

/** Everything the Plan screen shows for one computed trip. */
data class PlannedTrip(
    val origin: LatLng,
    val destination: NamedPlace,
    val route: RouteResult,
    val profile: List<ProfilePoint>,
    val chargers: List<Charger>,
    val options: List<PlanOption>,
    val selected: Int = 0,
    /** Amenity names near each charger id, loaded lazily for longer stops. */
    val amenities: Map<String, List<String>> = emptyMap(),
    /** When you leave; every clock time in the plan follows from it. */
    val departAt: Long = System.currentTimeMillis(),
    /** Planner notes per option (night mode, meals that couldn't be placed…). */
    val optionNotes: List<List<String>> = emptyList(),
    /** Google's routes for this trip and the one being planned. */
    val routes: List<RouteChoice> = emptyList(),
    val routeIndex: Int = 0,
    /** Chargers you'd reach in a meal window that have a good restaurant nearby, in route order. */
    val mealOptions: List<MealOption> = emptyList(),
    /** Where you chose to eat / which meals you skip; kept across re-plans of this route. */
    val mealChoices: MealChoices = MealChoices(),
    /** Charger id → the restaurant picked there (default: the best one). */
    val spotChoice: Map<String, String> = emptyMap(),
) {
    val plan: TripPlan get() = options[selected].plan
    val notes: List<String> get() = optionNotes.getOrNull(selected).orEmpty()

    /** Restaurants near [chargerId] for [meal], best first. */
    fun spots(chargerId: String, meal: Meal): List<MealSpot> = mealOptions.firstOrNull { it.charger.id == chargerId && it.meal == meal }?.spots.orEmpty()
}

/** One of Google's routes with what matters for an EV — read once, reused when it's planned. */
data class RouteChoice(
    val route: RouteResult,
    val samples: List<RoutePoint>,
    val elevations: List<Double>,
    val chargers: List<Charger>,
) {
    val climbM: Double get() = dev.irshadm.volta.core.planner.Terrain.ascentM(elevations)
    val peakM: Double get() = elevations.maxOrNull() ?: 0.0
    /** Longest stretch with no DC charger near the route. */
    val longestGapKm: Double get() = (listOf(0.0) + chargers.map { it.alongRouteM }.sorted() + listOf(route.distanceM)).zipWithNext { a, b -> b - a }.maxOrNull()?.div(1000) ?: 0.0
    val trafficDelayMin: Double get() = route.normalDurationS?.let { (route.durationS - it) / 60 }?.coerceAtLeast(0.0) ?: 0.0
}

/** A charger in a meal window with restaurants nearby; [clock] = minutes after the departure day's midnight. */
data class MealOption(val meal: Meal, val charger: Charger, val clock: Double, val spots: List<MealSpot>)

/**
 * Orchestrates one plan: route → elevation profile (cached per polyline) → chargers
 * along the route → [TripPlanner]. Route geometry is resampled every 200 m.
 */
class PlanRepository(
    private val client: GoogleMapsClient,
    private val cacheDir: File,
) {
    /** Google's routes (usually 1–3), each with its terrain and the DC chargers along it, read in parallel. */
    suspend fun routeChoices(origin: LatLng, destination: NamedPlace, onProgress: (String) -> Unit = {}): List<RouteChoice> = coroutineScope {
        onProgress("Finding the route…")
        val routes = client.computeRoutes(origin, destination.position)
        onProgress("Reading the terrain and nearby chargers…")
        val samples = routes.map { Geo.resample(it.path, SAMPLE_M) }
        val elevations = routes.mapIndexed { i, r -> async { cachedElevations(r.encodedPolyline, samples[i]) } }
        // One route at a time, so later routes reuse the stretches of road already searched (the costly call).
        val found = routes.map { client.chargersAlongPath(it.path, MIN_DC_KW) }
        routes.mapIndexed { i, route ->
            RouteChoice(route, samples[i], elevations[i].await(),
                found[i].map { it.toCharger(samples[i], route.distanceM) }.filter { it.detourM <= 10_000 }.distinctBy { it.id })
        }
    }

    /** Plans charging and meal stops on [routes][index]. */
    suspend fun planRoute(
        origin: LatLng,
        destination: NamedPlace,
        routes: List<RouteChoice>,
        index: Int,
        startSoc: Double,
        vehicle: VehicleProfile,
        settings: TripSettings,
        chargerPrefs: ChargerPreferences,
        departAt: Long = System.currentTimeMillis(),
        rules: MealRules = MealRules(),
    ): PlannedTrip {
        val choice = routes[index]
        val profile = buildProfile(choice.samples, choice.elevations, choice.route)
        val options = TripPlanner(vehicle, settings, chargerPrefs).options(profile, startSoc, choice.chargers, choice.route.durationS / 60.0)
        val trip = PlannedTrip(origin, destination, choice.route, profile, choice.chargers, options, departAt = departAt, routes = routes, routeIndex = index)
        return timed(trip, startSoc, vehicle, settings, chargerPrefs, rules)
    }

    /**
     * Fits every option to the departure time: meal breaks at chargers near a restaurant that serves that meal and
     * is open then, and on-road chargers for stops reached at night. Checks restaurants near a few candidate chargers.
     */
    private suspend fun timed(
        trip: PlannedTrip, startSoc: Double, vehicle: VehicleProfile, settings: TripSettings, prefs: ChargerPreferences, rules: MealRules,
    ): PlannedTrip {
        val meals = MealPlanner(vehicle, settings, prefs, rules)
        val departMin = java.util.Calendar.getInstance().apply { timeInMillis = trip.departAt }.let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val driveMin = trip.route.durationS / 60.0
        val candidates = meals.mealCandidates(trip.profile, startSoc, trip.chargers, driveMin, departMin)
        val mealOptions = candidates.map { m ->
            val at = java.util.Calendar.getInstance().apply { timeInMillis = trip.departAt + ((m.clock - departMin) * 60_000).toLong() }
            val day = at.get(java.util.Calendar.DAY_OF_WEEK) - 1                   // Places: 0 = Sunday
            val minute = at.get(java.util.Calendar.HOUR_OF_DAY) * 60 + at.get(java.util.Calendar.MINUTE)
            MealOption(m.meal, m.charger, m.clock, foodCache.getOrPut("${m.charger.id}|${m.meal}|$day") {
                runCatching { client.mealPlaces(m.charger.position, m.meal, day, minute) }.getOrDefault(emptyList())
            })
        }
        val food = mealOptions.associate { (it.charger.id to it.meal) to it.spots.firstOrNull()?.name }
        val timed = trip.options.map { o -> o to meals.plan(trip.profile, startSoc, trip.chargers, driveMin, departMin, food, base = o.plan, choices = trip.mealChoices) }
        return trip.copy(
            options = timed.map { (o, t) -> o.copy(plan = t.plan) }, optionNotes = timed.map { it.second.notes },
            mealOptions = mealOptions.filter { it.spots.isNotEmpty() }.sortedBy { it.clock },
        )
    }

    private val foodCache = HashMap<String, List<MealSpot>>()

    /** Re-runs the stop planner on an existing route (new start %, buffers or departure time) — no new route search. */
    suspend fun replan(
        trip: PlannedTrip, startSoc: Double, vehicle: VehicleProfile, settings: TripSettings, chargerPrefs: ChargerPreferences,
        departAt: Long = trip.departAt, rules: MealRules = MealRules(),
    ): PlannedTrip {
        val goal = trip.options.getOrNull(trip.selected)?.goal
        val options = TripPlanner(vehicle, settings, chargerPrefs).options(trip.profile, startSoc, trip.chargers, trip.route.durationS / 60.0)
        val fresh = trip.copy(options = options, selected = options.indexOfFirst { it.goal == goal }.coerceAtLeast(0), departAt = departAt)
        // A different departure time moves the meal windows, so earlier meal picks no longer apply.
        val keep = if (departAt == trip.departAt) fresh else fresh.copy(mealChoices = MealChoices(), spotChoice = emptyMap())
        return timed(keep, startSoc, vehicle, settings, chargerPrefs, rules)
    }

    /** Re-plans with new meal choices (no new searches). */
    suspend fun withMeals(
        trip: PlannedTrip, choices: MealChoices, startSoc: Double, vehicle: VehicleProfile, settings: TripSettings, chargerPrefs: ChargerPreferences, rules: MealRules,
    ): PlannedTrip = replan(trip.copy(mealChoices = choices), startSoc, vehicle, settings, chargerPrefs, trip.departAt, rules)

    /** Food / café / fuel-station names within 400 m of a charger (Nearby Search, cached per charger). */
    suspend fun amenitiesNear(charger: Charger): List<String> =
        amenityCache.getOrPut(charger.id) { runCatching { client.nearbyAmenities(charger.position) }.getOrDefault(emptyList()) }

    private val amenityCache = HashMap<String, List<String>>()

    private suspend fun cachedElevations(polyline: String, samples: List<RoutePoint>): List<Double> {
        val file = File(cacheDir, "elev-" + sha256(polyline) + ".txt")
        if (file.exists()) {
            val cached = file.readLines().mapNotNull { it.toDoubleOrNull() }
            if (cached.size == samples.size) return cached
        }
        val values = client.elevations(samples.map { it.position })
        runCatching { file.writeText(values.joinToString("\n")) }
        return values
    }

    private fun buildProfile(samples: List<RoutePoint>, elevations: List<Double>, route: RouteResult): List<ProfilePoint> {
        // Speed for each sample = speed of the route step it falls in.
        val stepEnds = route.steps.runningFold(0.0) { acc, s -> acc + s.distanceM }.drop(1)
        val fallback = if (route.durationS > 0) route.distanceM / route.durationS * 3.6 else 50.0
        return samples.mapIndexed { i, p ->
            val idx = stepEnds.indexOfFirst { it >= p.distanceM }.let { if (it < 0) route.steps.lastIndex else it }
            val step = route.steps.getOrNull(idx)
            val speed = if (step != null && step.durationS > 0) step.distanceM / step.durationS * 3.6 else fallback
            ProfilePoint(p.distanceM, elevations[i], speed.coerceIn(5.0, 130.0))
        }
    }

    private fun ChargerDto.toCharger(samples: List<RoutePoint>, routeM: Double): Charger {
        val nearest = samples.minBy { Geo.distanceM(it.position, position) }
        val offRoute = Geo.distanceM(nearest.position, position)
        val detour = viaDistanceM?.let { (it - routeM).coerceAtLeast(0.0) } ?: (2 * offRoute)
        return Charger(
            id = id,
            name = name,
            position = position,
            maxKw = maxKw.takeIf { it > 0 } ?: MIN_DC_KW,
            alongRouteM = nearest.distanceM,
            detourM = detour,
            connectorCount = connectorCount,
            availableCount = availableCount,
            network = ChargerPreferences.networkOf(name),
            address = address,
            open24h = open24h,
        )
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    companion object {
        const val SAMPLE_M = 200.0
        /** "DC fast" threshold for the charger search. */
        const val MIN_DC_KW = 15.0
    }
}
