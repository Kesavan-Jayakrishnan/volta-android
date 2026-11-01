package dev.irshadm.volta.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.trip.TripRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the car data fresh while the phone is in the car, i.e. while Android Auto is connected or a Volta car
 * session is open. The i-SMART API has no push, so freshness is a polling cadence, and this is the one place
 * polling is cheap: the car is awake and the phone is on its charger.
 *
 * - No trip: read the car every [ON_MS] while it's switched on, every [PARKED_MS] while parked, and after every
 *   [MOVE_M] of phone movement (fused location, so a drive produces readings on distance as well as time).
 * - On a trip: the trip ticks every [TripRepository.PROJECTED_POLL_MS] instead of the settings interval, and
 *   between ticks [TripRepository.nudge] moves the ring with phone GPS every [NUDGE_MS].
 *
 * Held by reasons ("session", "projection") so either signal keeps it running and the last one to go stops it.
 */
@Singleton
class InCarRefresh @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trips: TripRepository,
    private val ismart: IsmartRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reasons = mutableSetOf<String>()
    private var job: Job? = null
    @Volatile private var lastRefreshAt = 0L
    private var fused: FusedLocationProviderClient? = null
    private val onMove = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (trips.active.value == null) scope.launch { refresh(minGapMs = MOVE_GAP_MS) }
        }
    }

    @Synchronized fun hold(reason: String) { if (reasons.add(reason) && reasons.size == 1) start() }
    @Synchronized fun release(reason: String) { if (reasons.remove(reason) && reasons.isEmpty()) stop() }

    private fun start() {
        active = true
        trips.setProjected(true)
        job = scope.launch {
            while (isActive) {
                val onTrip = trips.active.value != null
                if (onTrip) {
                    runCatching { trips.nudge() }
                } else {
                    // Unknown state counts as on: the first read settles it.
                    val on = ismart.status.value?.ignitionOn?.value != false
                    refresh(minGapMs = if (on) ON_MS else PARKED_MS)
                }
                delay(if (onTrip) NUDGE_MS else LOOP_MS)
            }
        }
        watchMovement()
    }

    private fun stop() {
        job?.cancel(); job = null
        runCatching { fused?.removeLocationUpdates(onMove) }; fused = null
        trips.setProjected(false)
        active = false
    }

    private suspend fun refresh(minGapMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastRefreshAt < minGapMs) return
        if (ismart.link.value !is LinkState.Connected) return
        lastRefreshAt = now
        runCatching { ismart.refresh(includeCharge = true) }
    }

    @SuppressLint("MissingPermission")
    private fun watchMovement() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, MOVE_GAP_MS)
            .setMinUpdateDistanceMeters(MOVE_M).setMinUpdateIntervalMillis(MOVE_GAP_MS).build()
        fused = LocationServices.getFusedLocationProviderClient(context).also {
            runCatching { it.requestLocationUpdates(request, onMove, Looper.getMainLooper()) }
        }
    }

    companion object {
        /** True while the in-car cadence runs; the background worker then skips its own "driving" follow-ups. */
        @Volatile var active = false
            private set

        const val ON_MS = 2 * 60_000L
        const val PARKED_MS = 10 * 60_000L
        const val NUDGE_MS = 45_000L
        const val LOOP_MS = 30_000L
        const val MOVE_M = 1_000f
        const val MOVE_GAP_MS = 60_000L
    }
}
