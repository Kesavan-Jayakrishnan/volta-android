package dev.irshadm.volta.ui.plan

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.SocRepository
import dev.irshadm.volta.data.maps.GoogleMapsClient
import dev.irshadm.volta.data.maps.NamedPlace
import dev.irshadm.volta.data.maps.PlaceSuggestion
import dev.irshadm.volta.data.maps.PlanRepository
import dev.irshadm.volta.data.maps.PlannedTrip
import dev.irshadm.volta.core.domain.MapsKeyRepository
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.planner.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.math.roundToInt

enum class Field { Origin, Destination }

sealed interface PlanPhase {
    data object Idle : PlanPhase
    data class Working(val message: String) : PlanPhase
    /** Google found more than one route: pick one before stops are planned. */
    data class Choosing(val origin: LatLng, val routes: List<dev.irshadm.volta.data.maps.RouteChoice>, val current: Int? = null) : PlanPhase
    data class Done(val trip: PlannedTrip) : PlanPhase
    data class Failed(val message: String) : PlanPhase
}

/** A one-tap destination on the "Where to?" screen: Home from the energy settings, or a place you named. */
data class QuickPlace(val name: String, val position: LatLng, val home: Boolean = false)

/** A destination you planned before (trips started from this app), newest first. */
data class RecentTrip(val destination: String, val km: Int?, val at: Long)

data class PlanUiState(
    val originQuery: String = "",
    val origin: NamedPlace? = null,          // null = current location
    val destinationQuery: String = "",
    val destination: NamedPlace? = null,
    val active: Field? = null,
    val suggestions: List<PlaceSuggestion> = emptyList(),
    /** Where the phone is right now, when location is already allowed: distances on the place chips. */
    val here: LatLng? = null,
    val places: List<QuickPlace> = emptyList(),
    val recent: List<RecentTrip> = emptyList(),
    val startSoc: Double? = null,
    /** True once the user adjusts the starting % for this plan; live readings then stop overriding it. */
    val socOverridden: Boolean = false,
    val socLabel: String = "",
    /** Latest car reading, for the "use car %" shortcut. */
    val carSoc: Double? = null,
    /** Origin/destination changed since the last plan → needs a full re-plan. */
    val routeDirty: Boolean = false,
    /** Chosen departure (null = now). */
    val departAt: Long? = null,
    val settings: TripSettings = TripSettings(),
    val phase: PlanPhase = PlanPhase.Idle,
    /** Whether the user's own Google key is saved; every search and plan needs one. */
    val hasMapsKey: Boolean = true,
) {
    val canPlan: Boolean get() = destination != null && startSoc != null && phase !is PlanPhase.Working

    /** Ask for a key instead of showing the form. A plan already on screen stays, even if the key was removed since. */
    val needsMapsKey: Boolean get() = !hasMapsKey && (phase is PlanPhase.Idle || phase is PlanPhase.Failed)
}

@HiltViewModel
class PlanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GoogleMapsClient,
    keys: MapsKeyRepository,
    private val repository: PlanRepository,
    private val settings: SettingsRepository,
    private val trips: dev.irshadm.volta.data.trip.TripRepository,
    private val location: dev.irshadm.volta.data.trip.LocationProvider,
    socRepository: SocRepository,
    val feedback: dev.irshadm.volta.data.maps.ChargerFeedback,
) : ViewModel() {

    /** Place photos for the journey preview, by photo name or charger id; a null value means Google had none. */
    private val photos = HashMap<String, android.graphics.Bitmap?>()

    /** The first Places photo of a restaurant ([name]) or else a charger; null when there is none or the lookup fails. */
    suspend fun photo(chargerId: String?, name: String?): android.graphics.Bitmap? {
        val key = name ?: chargerId ?: return null
        if (photos.containsKey(key)) return photos[key]
        val bmp = runCatching {
            val n = name ?: client.placePhotoName(chargerId!!) ?: return@runCatching null
            client.photo(n)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()
        photos[key] = bmp
        return bmp
    }

    /** Chargers swapped out with "Use backup instead" during this planning session. */
    private val avoided = mutableSetOf<String>()

    private val _state = MutableStateFlow(PlanUiState(hasMapsKey = keys.key.value != null))
    val state: StateFlow<PlanUiState> = _state

    private var sessionToken = UUID.randomUUID().toString()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            keys.key.collect { key -> _state.update { it.copy(hasMapsKey = key != null) } }
        }
        viewModelScope.launch {
            combine(socRepository.latest, settings.tripSettings) { newest, trip ->
                val label = if (newest == null) "no reading" else "from car"
                Triple(newest?.socPercent, label, trip)
            }.collect { (soc, label, trip) ->
                _state.update { it.copy(startSoc = if (it.socOverridden) it.startSoc else soc, socLabel = if (it.socOverridden) it.socLabel else label, settings = trip, carSoc = soc) }
            }
        }
        // One-tap destinations: Home (energy settings) and the places you named, then the trips you planned before.
        viewModelScope.launch {
            combine(settings.energySettings, settings.savedPlaces) { e, saved ->
                val home = if (e.hasHome) QuickPlace("Home", LatLng(e.homeLat!!, e.homeLng!!), home = true) else null
                listOfNotNull(home) + saved.filterNot { home != null && it.name.equals("Home", true) }.map { QuickPlace(it.name, LatLng(it.lat, it.lng)) }
            }.collect { p -> _state.update { it.copy(places = p) } }
        }
        viewModelScope.launch {
            trips.recent(30).collect { rows ->
                val r = rows.filter { it.source == "APP" && !it.destination.isNullOrBlank() }
                    .distinctBy { it.destination!!.trim().lowercase() }.take(3)
                    .map { RecentTrip(it.destination!!.trim(), it.plannedDistanceM?.let { m -> (m / 1000).roundToInt() }, it.startedAt) }
                _state.update { it.copy(recent = r) }
            }
        }
        viewModelScope.launch {
            if (location.hasPermission()) location.current(5_000)?.let { fix -> _state.update { it.copy(here = fix.position) } }
        }
    }

    /** A saved place as the destination: plans straight away. */
    fun pickPlace(p: QuickPlace) {
        searchJob?.cancel()
        _state.update {
            it.copy(destination = NamedPlace("saved:${p.name}", p.name, p.position), destinationQuery = p.name, active = null, suggestions = emptyList(), routeDirty = true)
        }
    }

    /** A recent destination (we only kept its name): look it up again, then plan. */
    fun pickRecent(r: RecentTrip) {
        searchJob?.cancel()
        _state.update { it.copy(destinationQuery = r.destination, destination = null, active = null, suggestions = emptyList(), phase = PlanPhase.Working("Finding ${r.destination}…")) }
        val token = sessionToken
        sessionToken = UUID.randomUUID().toString()
        viewModelScope.launch {
            runCatching {
                val s = client.autocomplete(r.destination, token, near = _state.value.here).firstOrNull() ?: error("Couldn't find ${r.destination} on the map")
                client.placeLocation(s.placeId, token)
            }.onSuccess { place -> _state.update { it.copy(destination = place, routeDirty = true, phase = PlanPhase.Idle) } }
                .onFailure { e -> _state.update { it.copy(phase = PlanPhase.Failed(e.message ?: "Couldn't find that place")) } }
        }
    }

    fun onQuery(field: Field, text: String) {
        _state.update {
            when (field) {
                Field.Origin -> it.copy(originQuery = text, origin = null, active = field)
                Field.Destination -> it.copy(destinationQuery = text, destination = null, active = field)
            }
        }
        searchJob?.cancel()
        if (text.trim().length < 3) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            // Nearest first: from the typed start for the destination, else from where the phone is.
            val near = (if (field == Field.Destination) _state.value.origin?.position else null) ?: _state.value.here
            val results = runCatching { client.autocomplete(text.trim(), sessionToken, near) }
            if (results.exceptionOrNull() is kotlinx.coroutines.CancellationException) return@launch
            _state.update { s ->
                results.fold(
                    onSuccess = { s.copy(suggestions = it) },
                    onFailure = { e -> s.copy(suggestions = emptyList(), phase = PlanPhase.Failed(e.message ?: "Search failed")) },
                )
            }
        }
    }

    fun onFocus(field: Field?) = _state.update { it.copy(active = field, suggestions = if (field == null) emptyList() else it.suggestions) }

    fun useCurrentLocation() = _state.update { it.copy(origin = null, originQuery = "", active = null, suggestions = emptyList(), routeDirty = it.origin != null || it.routeDirty) }

    fun pick(s: PlaceSuggestion) {
        val field = _state.value.active ?: return
        val token = sessionToken
        sessionToken = UUID.randomUUID().toString()
        _state.update { it.copy(suggestions = emptyList(), active = null) }
        viewModelScope.launch {
            runCatching { client.placeLocation(s.placeId, token) }
                .onSuccess { place ->
                    _state.update {
                        when (field) {
                            Field.Origin -> it.copy(origin = place, originQuery = s.title, routeDirty = true)
                            Field.Destination -> it.copy(destination = place, destinationQuery = s.title, routeDirty = true)
                        }
                    }
                }
                .onFailure { e -> _state.update { it.copy(phase = PlanPhase.Failed(e.message ?: "Couldn't load that place")) } }
        }
    }

    fun setStartSoc(soc: Double) {
        _state.update { it.copy(startSoc = soc.coerceIn(1.0, 100.0), socOverridden = true, socLabel = "manual") }
        replanLocally()
    }

    fun useCarSoc() {
        _state.update { it.copy(startSoc = it.carSoc ?: it.startSoc, socOverridden = false, socLabel = "from car") }
        replanLocally()
    }

    /** Departure time (null = now); re-fits meals / night mode to the existing route. */
    fun setDepartAt(at: Long?) {
        _state.update { it.copy(departAt = at) }
        replanLocally()
    }

    /** "Arrive with at least X %" at the destination. */
    fun setArriveWith(percent: Int) {
        val t = _state.value.settings
        val p = percent.coerceIn(0, 50)
        if (p == t.minArrivalAtDestinationPercent) return
        val next = t.copy(minArrivalAtDestinationPercent = p)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch { settings.save(next) }
        replanLocally()
    }

    /** "Charge before X %": the planner never lets the battery drop below this before a charging stop. */
    fun setRechargeAt(percent: Int) {
        val t = _state.value.settings
        val p = percent.coerceIn(5, minOf(40, t.maxChargeTargetPercent - 10))
        if (p == t.minArrivalAtChargerPercent) return
        val next = t.copy(minArrivalAtChargerPercent = p)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch { settings.save(next) }
        replanLocally()
    }

    private var replanJob: Job? = null

    /** New start % or buffer on an already planned route: re-run just the stop planner (instant, no API calls). */
    private fun replanLocally() {
        val done = _state.value.phase as? PlanPhase.Done ?: return
        replanJob?.cancel()
        replanJob = viewModelScope.launch {
            delay(250)
            val s = _state.value
            val soc = s.startSoc ?: return@launch
            val trip = runCatching {
                repository.replan(done.trip, soc, settings.vehicleProfile.first(), s.settings, settings.chargerPreferences.first(),
                    s.departAt ?: System.currentTimeMillis(), settings.mealRules.first())
            }.getOrNull() ?: return@launch
            _state.update { it.copy(phase = PlanPhase.Done(trip.copy(amenities = done.trip.amenities))) }
            remindBefore(trip, soc)
            loadAmenities()
        }
    }

    /** Call after location permission is granted (or when a typed origin is used). Finds Google's routes first. */
    fun plan() {
        val s = _state.value
        val destination = s.destination ?: return
        if (s.startSoc == null) return
        viewModelScope.launch {
            _state.update { it.copy(phase = PlanPhase.Working("Locating you…")) }
            val origin = s.origin?.position ?: currentLocation()
            if (origin == null) {
                _state.update { it.copy(phase = PlanPhase.Failed("Couldn't get your location. Type a starting point instead.")) }
                return@launch
            }
            runCatching { repository.routeChoices(origin, destination) { msg -> _state.update { it.copy(phase = PlanPhase.Working(msg)) } } }
                .onSuccess { routes ->
                    _state.update { it.copy(routeDirty = false) }
                    if (routes.size == 1) planRoute(origin, routes, 0)
                    else _state.update { it.copy(phase = PlanPhase.Choosing(origin, routes)) }
                }
                .onFailure { e -> _state.update { it.copy(phase = PlanPhase.Failed(e.message ?: "Planning failed")) } }
        }
    }

    /** Plans stops on the route picked in the route list. */
    fun chooseRoute(index: Int) {
        val c = _state.value.phase as? PlanPhase.Choosing ?: return
        viewModelScope.launch { planRoute(c.origin, c.routes, index) }
    }

    /** Back to the route list from a plan (no new search). */
    fun changeRoute() {
        val trip = (_state.value.phase as? PlanPhase.Done)?.trip ?: return
        if (trip.routes.size < 2) return
        _state.update { it.copy(phase = PlanPhase.Choosing(trip.origin, trip.routes, trip.routeIndex)) }
    }

    private suspend fun planRoute(origin: LatLng, routes: List<dev.irshadm.volta.data.maps.RouteChoice>, index: Int) {
        val s = _state.value
        val destination = s.destination ?: return
        val soc = s.startSoc ?: return
        _state.update { it.copy(phase = PlanPhase.Working("Planning charging stops…")) }
        // Skip chargers you marked broken lately, and ones swapped for their backup.
        val usable = routes.map { r -> r.copy(chargers = r.chargers.filterNot { it.id in avoided || feedback.recentlyBroken(it.id) }) }
        runCatching {
            repository.planRoute(origin, destination, usable, index, soc, settings.vehicleProfile.first(), s.settings, settings.chargerPreferences.first(),
                s.departAt ?: System.currentTimeMillis(), settings.mealRules.first())
        }.onSuccess { trip -> _state.update { it.copy(phase = PlanPhase.Done(trip)) }; remindBefore(trip, soc); loadAmenities() }
            .onFailure { e -> _state.update { it.copy(phase = PlanPhase.Failed(e.message ?: "Planning failed")) } }
    }

    /** "Use backup instead": plan again without [chargerId] (the backup is usually the next-nearest, so it takes its place). */
    fun avoidCharger(chargerId: String) {
        val trip = (_state.value.phase as? PlanPhase.Done)?.trip ?: return
        avoided += chargerId
        viewModelScope.launch { planRoute(trip.origin, trip.routes, trip.routeIndex) }
    }

    /** A plan for later today/tomorrow → the "before you leave" check ~45 min before departure. */
    private fun remindBefore(trip: PlannedTrip, soc: Double) {
        if (trip.departAt > System.currentTimeMillis() + 30 * 60_000) {
            dev.irshadm.volta.service.SmartAlerts.planTrip(context, trip.departAt, trip.destination.name, soc)
        }
    }

    /** Eat [meal] at [chargerId] (re-plans the stops around it). */
    fun eatAt(meal: dev.irshadm.volta.core.planner.Meal, chargerId: String) = changeMeals { it.copy(at = it.at + (meal to chargerId), skip = it.skip - meal) }

    /** No stop for [meal]: eat before leaving or on arrival. */
    fun skipMeal(meal: dev.irshadm.volta.core.planner.Meal) = changeMeals { it.copy(at = it.at - meal, skip = it.skip + meal) }

    private fun changeMeals(f: (dev.irshadm.volta.core.planner.MealChoices) -> dev.irshadm.volta.core.planner.MealChoices) {
        val done = _state.value.phase as? PlanPhase.Done ?: return
        val soc = _state.value.startSoc ?: return
        viewModelScope.launch {
            val s = _state.value
            val trip = runCatching {
                repository.withMeals(done.trip, f(done.trip.mealChoices), soc, settings.vehicleProfile.first(), s.settings, settings.chargerPreferences.first(), settings.mealRules.first())
            }.getOrNull() ?: return@launch
            _state.update { it.copy(phase = PlanPhase.Done(trip.copy(amenities = done.trip.amenities))) }
            loadAmenities()
        }
    }

    /** Which restaurant to eat at near a meal stop's charger (display only). */
    fun pickSpot(chargerId: String, name: String) {
        val done = _state.value.phase as? PlanPhase.Done ?: return
        _state.update { it.copy(phase = PlanPhase.Done(done.trip.copy(spotChoice = done.trip.spotChoice + (chargerId to name)))) }
    }

    /** Starts live tracking for the selected option. */
    fun startTrip(onStarted: () -> Unit) {
        val done = _state.value.phase as? PlanPhase.Done ?: return
        val soc = _state.value.startSoc ?: return
        viewModelScope.launch {
            trips.start(done.trip, soc)
            onStarted()
        }
    }

    fun selectOption(i: Int) {
        val done = _state.value.phase as? PlanPhase.Done ?: return
        if (i !in done.trip.options.indices || i == done.trip.selected) return
        _state.update { it.copy(phase = PlanPhase.Done(done.trip.copy(selected = i))) }
        loadAmenities()
    }

    /** Amenities for stops of 15 min or more in the selected option. */
    private fun loadAmenities() {
        val trip = (_state.value.phase as? PlanPhase.Done)?.trip ?: return
        val stops = (trip.plan.outcome as? dev.irshadm.volta.core.planner.PlanOutcome.Ok)?.stops.orEmpty()
            .filter { it.chargeMinutes >= 15 && it.charger.id !in trip.amenities }
        if (stops.isEmpty()) return
        viewModelScope.launch {
            val found = stops.associate { it.charger.id to repository.amenitiesNear(it.charger) }
            _state.update { s ->
                val d = s.phase as? PlanPhase.Done ?: return@update s
                s.copy(phase = PlanPhase.Done(d.trip.copy(amenities = d.trip.amenities + found)))
            }
        }
    }

    private suspend fun currentLocation(): LatLng? = location.current()?.position
}
