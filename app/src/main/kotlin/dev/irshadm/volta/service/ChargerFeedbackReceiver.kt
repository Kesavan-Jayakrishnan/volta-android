package dev.irshadm.volta.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.irshadm.volta.data.maps.ChargeVerdict
import dev.irshadm.volta.data.maps.ChargerFeedback

/**
 * "How was <charger>?" after you leave a stop on a live trip: Worked / Had to wait / Broken, one tap each,
 * straight from the notification. The answer feeds that charger's rating (and broken ones get skipped).
 */
class ChargerFeedbackReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun chargerFeedback(): ChargerFeedback
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val verdict = intent.getStringExtra(EXTRA_VERDICT)?.let { runCatching { ChargeVerdict.valueOf(it) }.getOrNull() } ?: return
        EntryPointAccessors.fromApplication(context, Deps::class.java).chargerFeedback().add(id, verdict)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
    }

    companion object {
        private const val EXTRA_ID = "charger"
        private const val EXTRA_VERDICT = "verdict"
        private const val EXTRA_NOTIFICATION = "notification"

        fun ask(context: Context, chargerId: String, name: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val notificationId = 7_000 + (chargerId.hashCode() and 0xFFF)
            val b = NotificationCompat.Builder(context, Notifications.CHANNEL_QUIET)
                .setSmallIcon(dev.irshadm.volta.R.drawable.ic_stat_volta)
                .setContentTitle("How was $name?")
                .setContentText("One tap helps rate it for next time.")
                .setAutoCancel(true)
                .setContentIntent(Notifications.openApp(context))
            ChargeVerdict.entries.forEachIndexed { i, v ->
                val pi = PendingIntent.getBroadcast(
                    context,
                    notificationId * 4 + i,
                    Intent(context, ChargerFeedbackReceiver::class.java).putExtra(EXTRA_ID, chargerId).putExtra(EXTRA_VERDICT, v.name).putExtra(EXTRA_NOTIFICATION, notificationId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(0, v.label, pi)
            }
            NotificationManagerCompat.from(context).notify(notificationId, b.build())
        }
    }
}
