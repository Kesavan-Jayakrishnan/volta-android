package dev.irshadm.volta.ui.trip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.irshadm.volta.data.db.TripEntity
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.trip.ActiveTrip
import dev.irshadm.volta.data.trip.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripUiState(
    val active: ActiveTrip? = null,
    val recent: List<TripEntity> = emptyList(),
    val link: LinkState = LinkState.SignedOut,
    val now: Long = System.currentTimeMillis(),
    val checking: Boolean = false,
    /** Usable battery (kWh), to turn km/kWh into "range on a full charge". */
    val capacityKwh: Double = 37.1,
    /** The car's own range claim scaled to a full battery (latest reading's range ÷ its SoC), when it has one. */
    val carClaimKm: Double? = null,
)

@HiltViewModel
class TripViewModel @Inject constructor(
    private val trips: TripRepository,
    private val ismart: IsmartRepository,
    drives: dev.irshadm.volta.data.trip.DriveLogger,
    settings: dev.irshadm.volta.data.SettingsRepository,
) : ViewModel() {
    init { viewModelScope.launch { runCatching { drives.backfill(); drives.resplit() } } }

    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
    private val checking = kotlinx.coroutines.flow.MutableStateFlow(false)

    val state: StateFlow<TripUiState> = combine(
        combine(trips.active, trips.recent(1_000), ismart.link) { a, r, l -> Triple(a, r, l) },
        clock, checking, settings.vehicleProfile, ismart.status,
    ) { (a, r, l), n, c, v, s ->
        // Below ~5 % the car's range guess is too coarse to scale up to 100 %.
        val claim = s?.soc?.value?.let { sr -> sr.carRangeKm?.takeIf { sr.socPercent >= 5 }?.let { it / sr.socPercent * 100 } }
        TripUiState(a, r, l, n, c, v.usableCapacityKwh, claim)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TripUiState())

    /** Closes a planned trip left "in progress" after the process died mid-trip (no live trip is running). */
    fun closeOpenTrips() = viewModelScope.launch { runCatching { trips.closeStale() } }

    fun checkNow() = viewModelScope.launch {
        checking.value = true
        runCatching { trips.tick() }
        checking.value = false
    }

    fun reconnect() = viewModelScope.launch { ismart.reconnect() }
}
