package dev.irshadm.volta.service.smart

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Whether you're on foot, from Android's activity recognition (Play services, battery-cheap). Used so the
 * walk-back-to-the-car signal doesn't fire when you pass the car in a cab or on a bike, and so the pre-cool
 * sheet can say "while you walk there". Optional: without the Physical activity permission everything still
 * works on location alone.
 */
object Walking {
    private const val PREFS = "walking"
    private const val FRESH_MS = 20 * 60_000L

    enum class Mode { Foot, Vehicle, Bike, Still }

    fun hasPermission(context: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun intent(context: Context) = PendingIntent.getBroadcast(
        context, 11, Intent(context, WalkingReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Starts (or stops) listening for walking / riding transitions. Survives until reboot; re-armed with the geofences. */
    @SuppressLint("MissingPermission")
    fun watch(context: Context, on: Boolean) {
        val client = ActivityRecognition.getClient(context)
        if (!on || !hasPermission(context)) { runCatching { client.removeActivityTransitionUpdates(intent(context)) }; return }
        val types = listOf(DetectedActivity.WALKING, DetectedActivity.ON_FOOT, DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE, DetectedActivity.STILL)
        val transitions = types.map { ActivityTransition.Builder().setActivityType(it).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build() }
        runCatching { client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), intent(context)) }
    }

    internal fun record(context: Context, type: Int, at: Long) {
        val mode = when (type) {
            DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> Mode.Foot
            DetectedActivity.IN_VEHICLE -> Mode.Vehicle
            DetectedActivity.ON_BICYCLE -> Mode.Bike
            else -> Mode.Still
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode.name).putLong("at", at).apply()
    }

    /** What you were last doing, if it's recent (20 min); null when unknown. */
    fun now(context: Context, now: Long = System.currentTimeMillis()): Mode? {
        if (!hasPermission(context)) return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - p.getLong("at", 0) > FRESH_MS) return null
        return p.getString("mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
    }
}

class WalkingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val last = ActivityTransitionResult.extractResult(intent)?.transitionEvents?.lastOrNull() ?: return
        // Event time is nanoseconds since boot; convert to wall-clock.
        val at = System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtimeNanos() - last.elapsedRealTimeNanos) / 1_000_000
        Walking.record(context, last.activityType, at)
    }
}
