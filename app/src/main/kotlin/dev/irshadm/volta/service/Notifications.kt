package dev.irshadm.volta.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.model.CarColor
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.irshadm.volta.MainActivity
import dev.irshadm.volta.R
import dev.irshadm.volta.car.VoltaCarAppService
import dev.irshadm.volta.core.planner.LatLng

/**
 * Notification channels follow the dashboard's alert tiers, each with its own sound:
 *  - Act now: car moved, unlocked or open while parked, can't reach the next charger — a bright two-note chime.
 *  - Car & trip alerts: everything else worth knowing (falling behind plan, plugs taken, charging done) — one soft note.
 *  - Quiet: tips and slow trends — no sound.
 * Each can be changed in Android's notification settings for Volta.
 *
 * With Android Auto connected, the same alerts (Act now and Car & trip, not Quiet) also show as heads-up cards on
 * the car screen, through a CarAppExtender. Their buttons carry car-side pending intents: "Navigate …" starts the
 * car's navigation app, "Show plan" opens Volta on the car screen. The phone notification is unchanged.
 */
object Notifications {
    const val CHANNEL_TRIP = "trip"
    const val CHANNEL_ALERTS = "alerts_v2"
    const val CHANNEL_URGENT = "act_v2"
    const val CHANNEL_QUIET = "health"
    const val CHANNEL_PRECOOL = "precool"

    /** Channels from before the sounds; removed so they don't linger in settings. */
    private val OLD_CHANNELS = listOf("alerts", "urgent")

    enum class Level(val channel: String) { Normal(CHANNEL_ALERTS), Urgent(CHANNEL_URGENT), Quiet(CHANNEL_QUIET) }
    const val ID_TRIP = 1
    private var nextId = 100

    /** Champagne for on track, amber when slipping (the live trip card's accent). */
    private const val CHAMPAGNE = 0xFFD6B98A.toInt()
    private const val AMBER = 0xFFF59E0B.toInt()

    /** A notification button: what it does on the phone, and (when set) on the car screen. */
    data class Action(val label: String, val phone: PendingIntent, val car: PendingIntent? = null)

    private const val CAR_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun sound(context: Context, raw: Int) = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$raw")
    private val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRIP, "Live trip", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing trip tracking: battery against the plan"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_URGENT, "Act now", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Car moved without you, left unlocked or open, or the next charger is out of reach"
                setSound(sound(context, R.raw.alert_act), audio)
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Car & trip alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Falling behind plan, plugs taken at your next stop, charging done, tyres"
                setSound(sound(context, R.raw.alert_check), audio)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PRECOOL, "Smart pre-cool", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cool the cabin before you get in, and the cooling progress"
                setSound(sound(context, R.raw.alert_check), audio)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Tips and trends", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Battery-care tips, slow tyre pressure loss, a weakening 12 V battery (silent)"
            },
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Opens Volta on the car screen (Android Auto routes it to VoltaCarAppService). */
    fun openCarApp(context: Context): PendingIntent? = runCatching {
        CarPendingIntent.getCarApp(context, 1, Intent(context, VoltaCarAppService::class.java), CAR_FLAGS)
    }.getOrNull()

    /** A notification button that starts navigation to [to]: Google Maps on the phone, the car's navigation app in the car. */
    fun navigate(context: Context, label: String, to: LatLng): Action {
        val code = (to.lat * 1e5).toInt() xor (to.lng * 1e5).toInt()
        val phone = PendingIntent.getActivity(
            context,
            code,
            Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${to.lat},${to.lng}")).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val car = runCatching {
            CarPendingIntent.getCarApp(context, code, Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=${to.lat},${to.lng}")), CAR_FLAGS)
        }.getOrNull()
        return Action(label, phone, car)
    }

    /** A notification button that opens the app (on the phone, or on the car screen). */
    fun show(context: Context, label: String): Action = Action(label, openApp(context), openCarApp(context))

    /**
     * The live trip card in the shade. [behind] is how far below plan, in %: from 2 % it turns amber, so the
     * trend shows before anything pops up. The bar is the battery.
     */
    fun ongoing(context: Context, title: String, text: String, soc: Double? = null, behind: Double = 0.0) = NotificationCompat.Builder(context, CHANNEL_TRIP)
        .setSmallIcon(R.drawable.ic_stat_volta)
        .setColor(if (behind >= 2) AMBER else CHAMPAGNE)
        .setSubText(if (behind >= 2) "${behind.toInt()} % behind plan" else "On track")
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .apply { soc?.let { setProgress(100, it.toInt().coerceIn(0, 100), false) } }
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(context))
        .build()

    /** An alert on the tier's channel (its sound), with up to three buttons. Silently skipped without permission. */
    fun alert(context: Context, title: String, text: String, level: Level = Level.Normal, actions: List<Action> = emptyList()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(
            nextId++,
            NotificationCompat.Builder(context, level.channel)
                .setSmallIcon(R.drawable.ic_stat_volta)
                .setColor(if (level == Level.Urgent) AMBER else CHAMPAGNE)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(if (level == Level.Quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(context))
                .apply { actions.take(3).forEach { a -> addAction(0, a.label, a.phone) } }
                .apply { if (level != Level.Quiet) extend(carExtender(context, level, actions)) }
                .build(),
        )
    }

    /** The car-screen version of an alert: a heads-up card over whatever is open, with the car-side buttons. */
    private fun carExtender(context: Context, level: Level, actions: List<Action>): CarAppExtender {
        val b = CarAppExtender.Builder()
            .setSmallIcon(R.drawable.ic_stat_volta)
            .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
            .setColor(if (level == Level.Urgent) CarColor.RED else CarColor.createCustom(CHAMPAGNE, CHAMPAGNE))
        openCarApp(context)?.let { b.setContentIntent(it) }
        actions.take(2).forEach { a -> a.car?.let { b.addAction(R.drawable.ic_stat_volta, a.label, it) } }
        return b.build()
    }
}
