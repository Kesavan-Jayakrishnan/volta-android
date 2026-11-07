package dev.irshadm.volta.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.irshadm.volta.data.trip.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Runs only while a trip is active: checks the car + phone GPS on the trip interval and
 * keeps an ongoing notification ("Live tracking active").
 */
@AndroidEntryPoint
class TripService : Service() {
    @Inject lateinit var trips: TripRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                trips.end()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            Notifications.ID_TRIP,
            Notifications.ongoing(this, "Live trip", "Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    val wait = runCatching { trips.tick() }.getOrDefault(60_000L)
                    val t = trips.active.value ?: break
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    val next = t.nextStop?.let { " · next stop in ${((it.charger.alongRouteM - t.alongM) / 1000).roundToInt()} km" } ?: ""
                    val est = if (t.socEstimated) " (estimated)" else ""
                    nm.notify(
                        Notifications.ID_TRIP,
                        Notifications.ongoing(
                            this@TripService,
                            "To ${t.planned.destination.name}",
                            "${t.soc.roundToInt()} %$est · plan ${t.expectedSoc.roundToInt()} %$next",
                            soc = t.soc,
                            behind = -t.deviation,
                        ),
                    )
                    // Sleep until the next check, or until something (Android Auto connecting) wakes the trip early.
                    withTimeoutOrNull(wait.coerceAtLeast(30_000L)) { trips.wake.receive() }
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "stop"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, TripService::class.java).setAction(ACTION_STOP))
    }
}
