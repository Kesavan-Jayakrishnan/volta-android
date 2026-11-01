package dev.irshadm.volta.data.trip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import dev.irshadm.volta.core.planner.LatLng
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class Fix(val position: LatLng, val altitudeM: Double?, val speedKmh: Double?, val atMillis: Long)

/** Phone GPS (the car's own fix only updates occasionally). */
@Singleton
class LocationProvider @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = 10_000): Fix? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
            }
        }
        val loc = fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: return null
        return Fix(
            LatLng(loc.latitude, loc.longitude),
            if (loc.hasAltitude()) loc.altitude else null,
            if (loc.hasSpeed()) loc.speed * 3.6 else null,
            loc.time,
        )
    }
}
