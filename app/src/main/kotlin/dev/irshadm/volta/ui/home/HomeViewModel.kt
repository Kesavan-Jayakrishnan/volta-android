package dev.irshadm.volta.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.ismart.RemoteCommand
import dev.irshadm.volta.core.model.BodyStatus
import dev.irshadm.volta.core.model.SocReading
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.SocRepository
import dev.irshadm.volta.data.ismart.CommandState
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.ui.components.CarHeroState
import dev.irshadm.volta.ui.components.CarShot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

enum class DataMode { Live, Manual }

private data class Extras(val link: LinkState, val cmd: CommandState, val now: Long, val place: String?, val weather: dev.irshadm.volta.data.weather.Weather?)

/** The home charger as the dashboard sees it: Bluetooth link, and a start/stop waiting for the car to confirm. */
data class ChargerUi(
    val link: dev.irshadm.volta.data.charger.SpinCharger.Link = dev.irshadm.volta.data.charger.SpinCharger.Link.Off,
    val pending: Boolean? = null,
    /** The charger's own state while connected: quicker than the car's report after a start or stop. */
    val state: dev.irshadm.volta.data.charger.SpinCharger.ChargerState? = null,
) {
    val chargerCharging: Boolean get() = link is dev.irshadm.volta.data.charger.SpinCharger.Link.Ready && state == dev.irshadm.volta.data.charger.SpinCharger.ChargerState.Charging
}

data class HomeUiState(
    val loaded: Boolean = false,
    val soc: SocReading? = null,
    val profile: VehicleProfile = VehicleProfile(),
    val vehicle: VehicleStatus? = null,
    val mode: DataMode = DataMode.Manual,
    val link: LinkState = LinkState.SignedOut,
    val command: CommandState = CommandState.Idle,
    val hasPin: Boolean = false,
    val nowMillis: Long = System.currentTimeMillis(),
    /** Street/area where the car is, from the phone's geocoder. */
    val placeName: String? = null,
    /** Outside from the weather service; the cabin measured while the car is on, estimated while parked. */
    val temps: dev.irshadm.volta.data.weather.Temps = dev.irshadm.volta.data.weather.Temps(null, null, false),
    /** One battery-care tip under the charge bar, if there's something worth saying and it wasn't swiped away today. */
    val tip: dev.irshadm.volta.service.BatteryCare.Tip? = null,
    /** Battery % when the current charge started (from the logged session), for the charging bar. */
    val plugInSoc: Double? = null,
    val charger: ChargerUi = ChargerUi(),
    /** Which MG this is: the header shows its name, and only a Windsor gets the Windsor photos. */
    val car: dev.irshadm.volta.data.ismart.CarIdentity = dev.irshadm.volta.data.ismart.CarIdentity.Unknown,
) {
    val refreshing: Boolean get() = (link as? LinkState.Connected)?.refreshing == true

    /** Signed in, nothing from the car yet, and a read is on its way: the dashboard shows its skeleton. */
    val waitingForCar: Boolean get() = mode == DataMode.Live && vehicle == null && refreshing

    val hero: CarHeroState?
        get() = vehicle?.let { v ->
            val locked = v.locked?.value ?: true
            val shot = when {
                v.charging?.value == true -> CarShot.Charging
                v.lightsOn?.value == true -> CarShot.Lights
                locked -> CarShot.Locked
                else -> CarShot.Unlocked
            }
            CarHeroState(shot, locked, v.body?.value ?: BodyStatus(), v.tyrePsi?.value)
        }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val socRepository: SocRepository,
    private val settings: SettingsRepository,
    private val ismart: IsmartRepository,
    private val maps: dev.irshadm.volta.data.maps.GoogleMapsClient,
    private val weatherClient: dev.irshadm.volta.data.weather.WeatherClient,
    private val tripDao: dev.irshadm.volta.data.db.TripDao,
    private val chargeDao: dev.irshadm.volta.data.db.ChargeDao,
    private val spin: dev.irshadm.volta.data.charger.SpinCharger,
) : ViewModel() {
    /** true = start sent, false = stop sent, until the car reports the new state (or 90 s pass). */
    private val chargePending = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    private val chargerUi = combine(spin.link, chargePending, spin.state) { l, p, st -> ChargerUi(l, p, st) }
    private val plugInSoc = kotlinx.coroutines.flow.MutableStateFlow<Double?>(null)

    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    private val vehicleSource = combine(ismart.status, ismart.link) { live, link ->
        if (link !is LinkState.SignedOut) live to DataMode.Live else null to DataMode.Manual
    }

    private var lastGeoKey: String? = null
    private var lastLandmarkKey: String? = null
    private val placeName = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** A name the user gave this spot (or "Home"), which beats any looked-up landmark. */
    private val namedPlace = combine(vehicleSource, settings.savedPlaces, settings.energySettings) { (v, _), saved, energy ->
        val g = v?.gps?.value ?: return@combine null
        saved.firstOrNull { it.contains(g.latitude, g.longitude) }?.name
            ?: if (energy.hasHome && dev.irshadm.volta.core.planner.Geo.distanceM(
                    dev.irshadm.volta.core.planner.LatLng(energy.homeLat!!, energy.homeLng!!),
                    dev.irshadm.volta.core.planner.LatLng(g.latitude, g.longitude),
                ) <= energy.homeRadiusM
            ) {
                "Home"
            } else {
                null
            }
    }
    private val place = combine(placeName, namedPlace) { looked, named ->
        if (named == null) looked else (listOf(named) + looked.orEmpty().split(", ").filter { it.isNotBlank() && !it.startsWith("Near ") }).joinToString(", ")
    }
    private val weather = kotlinx.coroutines.flow.MutableStateFlow<dev.irshadm.volta.data.weather.Weather?>(null)
    private val usualKm = kotlinx.coroutines.flow.MutableStateFlow<Double?>(null)
    private val tipPrefs = context.getSharedPreferences("tips", android.content.Context.MODE_PRIVATE)
    private val dismissed = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val ticks = combine(clock, dismissed) { now, _ -> now }
    private val extras = combine(ismart.link, ismart.command, ticks, place, weather) { link, cmd, now, place, w -> Extras(link, cmd, now, place, w) }

    private val extrasAndCharger = combine(extras, chargerUi) { e, c -> e to c }
    val state: StateFlow<HomeUiState> = combine(socRepository.latest, settings.vehicleProfile, vehicleSource, extrasAndCharger) { manual, profile, (vehicle, mode), (ex, charger) ->
        val (link, cmd, now, place, w) = ex
        // Newest stored reading or the live status, whichever is newer.
        val soc = listOfNotNull(manual, vehicle?.soc?.value).maxByOrNull { it.measuredAtMillis }
        val temps = dev.irshadm.volta.data.weather.CabinModel.temps(vehicle, w, now)
        val km = vehicle?.distanceSinceLastChargeKm?.value
        val kwh = vehicle?.energySinceLastChargeKwh?.value
        val care = if (mode == DataMode.Live) {
            dev.irshadm.volta.service.BatteryCare.tip(
                context,
                vehicle,
                temps.outsideC,
                usualKm.value,
                if (km != null && kwh != null && kwh >= 1) km / kwh else profile.cityKmPerKwh,
                profile.usableCapacityKwh,
                now,
            )
        } else {
            null
        }
        val tip = care?.takeIf { !dismissedToday(it.key, now) }
        HomeUiState(true, soc, profile, vehicle, mode, link, cmd, ismart.hasPin, now, place, temps, tip, plugInSoc.value, charger, ismart.car.value)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // Name the car's spot: the on-device geocoder (free) whenever the car has moved ~100 m, and the nearest
        // landmark (one Google Places call) only once it's parked there, never while it's driving.
        viewModelScope.launch {
            vehicleSource.collect { (v, _) ->
                val g = v?.gps?.value ?: return@collect
                val key = "%.3f,%.3f".format(g.latitude, g.longitude)
                val parked = v.ignitionOn?.value != true
                if (key == lastGeoKey && (!parked || key == lastLandmarkKey)) return@collect
                lastGeoKey = key
                placeName.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        android.location.Geocoder(context).getFromLocation(g.latitude, g.longitude, 1)?.firstOrNull()?.let { a ->
                            // Full address line minus plus code, PIN code, state and country → "Place, Road, Area, City".
                            val parts = (a.getAddressLine(0) ?: "").split(",").map { it.trim() }.filter { p ->
                                p.isNotEmpty() && '+' !in p && p.count(Char::isDigit) < 5 && p != a.adminArea && p != a.countryName &&
                                    !(a.adminArea != null && p.startsWith(a.adminArea))
                            }.distinct()
                            parts.joinToString(", ").ifEmpty { null }
                        }
                    }.getOrNull()
                }
                if (!parked) return@collect
                lastLandmarkKey = key
                // Nearest landmark (Places, one call per parking spot) makes the spot recognisable.
                val landmark = runCatching { maps.nearestPlaceName(dev.irshadm.volta.core.planner.LatLng(g.latitude, g.longitude)) }.getOrNull()
                if (landmark != null) placeName.value = (listOf("Near $landmark") + placeName.value.orEmpty().split(", ").filter { it.isNotBlank() && it != landmark }).joinToString(", ")
            }
        }
        // Battery care: remember full / low / sitting-full times from every reading, and the usual daily km.
        viewModelScope.launch {
            vehicleSource.collect { (v, mode) ->
                if (v != null && mode == DataMode.Live) dev.irshadm.volta.service.BatteryCare.record(context, v)
                plugInSoc.value = if (v?.pluggedIn?.value == true || v?.charging?.value == true) runCatching { chargeDao.open()?.startSoc }.getOrNull() else null
            }
        }
        viewModelScope.launch {
            val trips = tripDao.finished(300)
            usualKm.value = dev.irshadm.volta.service.BatteryCare.usualDailyKm(trips)
        }
        // Home charger: connect over Bluetooth only while the car is plugged in (and this screen is open); retry
        // every minute while it's out of reach; let go when it's unplugged. A pending start/stop clears when the
        // car reports the new state.
        viewModelScope.launch {
            vehicleSource.collect { (v, mode) ->
                val plugged = mode == DataMode.Live && (v?.pluggedIn?.value == true || v?.charging?.value == true)
                if (!plugged) spin.disconnect()
                chargePending.value?.let { want -> if (v?.charging?.value == want) chargePending.value = null }
            }
        }
        // Try right away whenever a charger is saved or the car reports plugged in; then every 20 s, reconnect if
        // needed or re-read the charger's state so the Start / Stop button follows it.
        viewModelScope.launch {
            combine(spin.saved, ismart.status) { saved, v -> saved != null && (v?.pluggedIn?.value == true || v?.charging?.value == true) }
                .distinctUntilChanged()
                .collectLatest { want ->
                    while (want) {
                        if (spin.link.value is dev.irshadm.volta.data.charger.SpinCharger.Link.Off) {
                            runCatching { spin.connect() }
                        } else {
                            runCatching { spin.refreshState() }
                        }
                        delay(20_000)
                    }
                }
        }
        // Weather where the car is, every 15 minutes while the dashboard is open (the client caches per spot).
        viewModelScope.launch {
            combine(vehicleSource, clock) { (v, _), _ -> v?.gps?.value }.collect { g ->
                if (g != null) weatherClient.now(g.latitude, g.longitude)?.let { weather.value = it }
            }
        }
        // Fresh data on opening the app is fetched by MainActivity (during the splash), not here.
    }

    fun refresh() = viewModelScope.launch { ismart.refresh() }

    /** Start (true) or stop (false) charging at the home charger; the car's next readings confirm it. */
    fun setCharging(start: Boolean) = viewModelScope.launch {
        if (!spin.charge(start)) {
            delay(8_000)
            spin.clearFailure()
            return@launch
        }
        // The charger confirms within seconds; only wait on the car when the charger couldn't tell.
        val confirmed = spin.state.value?.let { (it == dev.irshadm.volta.data.charger.SpinCharger.ChargerState.Charging) == start } == true
        if (confirmed) {
            ismart.refresh()
            return@launch
        }
        chargePending.value = start
        // The car takes a little while to report the change.
        for (wait in listOf(8_000L, 15_000L, 30_000L, 37_000L)) {
            delay(wait)
            ismart.refresh()
            if (chargePending.value == null) return@launch
        }
        chargePending.value = null
    }

    override fun onCleared() {
        spin.disconnect()
    }

    private fun dismissedToday(key: String, now: Long) = tipPrefs.getLong(key, -1) == now / 86_400_000L

    /** Swiped away: hide this tip until tomorrow. */
    fun dismissTip(key: String) {
        tipPrefs.edit().putLong(key, System.currentTimeMillis() / 86_400_000L).apply()
        dismissed.value++
    }

    /** Long-press on the location chip: remember a name for where the car is now. */
    fun nameCurrentPlace(name: String) {
        val g = state.value.vehicle?.gps?.value ?: return
        if (name.isBlank()) return
        viewModelScope.launch { settings.namePlace(name, g.latitude, g.longitude) }
    }
    fun reconnect() = viewModelScope.launch { ismart.reconnect() }
    fun send(cmd: RemoteCommand) = viewModelScope.launch { ismart.send(cmd) }
    fun clearCommand() = ismart.clearCommand()
}
