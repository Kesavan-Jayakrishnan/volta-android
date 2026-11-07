package dev.irshadm.volta.service.smart

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import dev.irshadm.volta.QuickActionActivity
import dev.irshadm.volta.R
import dev.irshadm.volta.service.Notifications
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The numbers behind a pre-cool: how long to the target and what it costs. The cabin loses about 1.7° a minute
 * once the AC has had 3 minutes to start; the AC draws about 2 kW meanwhile.
 */
object Cooling {
    const val TARGET_C = 24

    /** Below this the cabin is fine as it is. */
    const val COMFY_BELOW = 29.0

    fun minutes(cabinC: Double): Int = ((cabinC - TARGET_C) * 0.6 + 3).roundToInt().coerceIn(3, 15)

    /** Battery used, in %, for [minutes] of AC. */
    fun batteryPercent(minutes: Int, capacityKwh: Double): Double = 2.0 * minutes / 60 / capacityKwh * 100

    fun batteryText(pct: Double): String = if (pct < 1) "under 1 %" else "about ${pct.roundToInt()} %"

    /** Estimated cabin temperature [elapsedMin] into a cool that started at [startC]. */
    fun estimate(startC: Double, elapsedMin: Double, totalMin: Int): Double {
        if (totalMin <= 0) return TARGET_C.toDouble()
        val f = (elapsedMin / totalMin).coerceIn(0.0, 1.0)
        return startC - (startC - TARGET_C) * f
    }

    // ---- the cool that is running now (so the sheet and the notification agree)

    private const val PREFS = "precool"

    data class Session(val startedAt: Long, val startC: Double, val minutes: Int, val capacityKwh: Double) {
        fun elapsedMin(now: Long = System.currentTimeMillis()) = (now - startedAt) / 60_000.0
        fun leftMin(now: Long = System.currentTimeMillis()) = (minutes - elapsedMin(now)).coerceAtLeast(0.0)
        fun cabinNow(now: Long = System.currentTimeMillis()) = estimate(startC, elapsedMin(now), minutes)
    }

    fun session(context: Context): Session? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong("cool_started", 0L)
        if (at == 0L) return null
        return Session(at, p.getFloat("cool_start_c", 30f).toDouble(), p.getInt("cool_minutes", 6), p.getFloat("cool_capacity", 37.1f).toDouble())
    }

    /** The AC was just switched on from Volta: remember the cool and follow it with a progress notification. */
    fun started(context: Context, cabinC: Double, capacityKwh: Double) {
        val minutes = minutes(cabinC)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("cool_started", System.currentTimeMillis()).putFloat("cool_start_c", cabinC.toFloat()).putInt("cool_minutes", minutes).putFloat("cool_capacity", capacityKwh.toFloat()).apply()
        NotificationManagerCompat.from(context).cancel(DepartureWorker.NOTIFICATION_ID)
        CoolingWorker.schedule(context, 0)
    }

    /** The AC was switched off from Volta (or has stopped): the cool is over. */
    fun stopped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("cool_started").apply()
        WorkManager.getInstance(context).cancelUniqueWork(CoolingWorker.WORK)
        NotificationManagerCompat.from(context).cancel(CoolingWorker.NOTIFICATION_ID)
    }

    // ---- the pictures the notifications carry

    /** The car on a soft tinted square: warm while the cabin is hot, cool blue while cooling, green when done. */
    fun carIcon(context: Context, tint: Int): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.RadialGradient(size / 2f, size * 0.38f, size * 0.7f, tint, blend(tint, 0xFF15171B.toInt(), 0.65f), android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, p)
        val picture = dev.irshadm.volta.ui.components.CarPictures.small(dev.irshadm.volta.data.ismart.CarIdentity.saved(context).model, dev.irshadm.volta.ui.components.CarShot.Locked)
        val car = BitmapFactory.decodeResource(context.resources, picture) ?: return bmp
        val scale = (size * 0.86f) / car.width
        val w = car.width * scale
        val h = car.height * scale
        c.drawBitmap(car, null, RectF((size - w) / 2, (size - h) / 2 + size * 0.04f, (size + w) / 2, (size + h) / 2 + size * 0.04f), Paint(Paint.FILTER_BITMAP_FLAG))
        return bmp
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    const val HOT = 0xFF5A2E14.toInt()
    const val COOL = 0xFF1F4A58.toInt()
    const val DONE = 0xFF163A2A.toInt()
}

/**
 * Follows a cool the user started from Volta: every minute it updates one progress notification ("34° → 24° ·
 * 4 min left", with Stop), and when the time is up or the car says the AC is off it posts "Cabin 24° · ready",
 * which clears itself. The car's own cabin reading freezes while it is switched off, so the number is the model's.
 */
class CoolingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = Cooling.session(ctx) ?: return Result.success()
        val d = EntryPointAccessors.fromApplication(ctx, SmartDeps::class.java)
        val ismart = d.smartIsmart()
        val elapsed = s.elapsedMin()
        // After a couple of minutes, trust the car over the clock: if the AC is off, the cool is over.
        if (elapsed >= 2.0) {
            runCatching { ismart.refresh(includeCharge = false) }
            if (ismart.status.value?.climateRunning?.value == false) {
                done(ctx, s)
                return Result.success()
            }
        }
        if (elapsed >= s.minutes || elapsed > 20) {
            done(ctx, s)
            return Result.success()
        }
        progress(ctx, s)
        schedule(ctx, 60)
        return Result.success()
    }

    private fun progress(ctx: Context, s: Cooling.Session) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val now = System.currentTimeMillis()
        val cabin = s.cabinNow(now).roundToInt()
        val left = kotlin.math.ceil(s.leftMin(now)).toInt()
        val text = "$cabin° → ${Cooling.TARGET_C}° · $left min left"
        val stop = sheetIntent(ctx)
        val b = NotificationCompat.Builder(ctx, Notifications.CHANNEL_PRECOOL)
            .setSmallIcon(R.drawable.ic_stat_precool)
            .setLargeIcon(Cooling.carIcon(ctx, Cooling.COOL))
            .setColor(0xFF7DD3E8.toInt())
            .setContentTitle("Cooling the cabin")
            .setContentText(text)
            .setSubText("ready about ${clock(s.startedAt + s.minutes * 60_000L)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(stop)
            .addAction(0, "Stop", stop)
        val fraction = (s.elapsedMin(now) / s.minutes).coerceIn(0.0, 1.0)
        val styled = runCatching {
            // Android 16+: a progress-centric notification the system can promote to a status-bar chip.
            b.setStyle(
                NotificationCompat.ProgressStyle().setProgress((fraction * 100).toInt()).setStyledByProgress(true)
                    .setProgressTrackerIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.drawable.ic_shortcut_precool)),
            )
            b.setRequestPromotedOngoing(true)
            true
        }.getOrDefault(false)
        if (!styled) b.setProgress(100, (fraction * 100).toInt(), false)
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, b.build())
    }

    private fun done(ctx: Context, s: Cooling.Session) {
        Cooling.stopped(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val used = Cooling.batteryText(Cooling.batteryPercent(minOf(s.minutes, kotlin.math.ceil(s.elapsedMin()).toInt().coerceAtLeast(1)), s.capacityKwh))
        NotificationManagerCompat.from(ctx).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(ctx, Notifications.CHANNEL_PRECOOL)
                .setSmallIcon(R.drawable.ic_stat_precool)
                .setLargeIcon(Cooling.carIcon(ctx, Cooling.DONE))
                .setColor(0xFF7DD39A.toInt())
                .setContentTitle("Cabin ${Cooling.TARGET_C}° · ready")
                .setContentText("${s.minutes} min of AC · $used of the battery")
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000L)
                .setContentIntent(sheetIntent(ctx))
                .build(),
        )
    }

    private fun sheetIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        12,
        Intent(Intent.ACTION_VIEW, Uri.parse("volta://action/precool"), ctx, QuickActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun clock(ms: Long) = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ms)).lowercase()

    companion object {
        const val WORK = "precool-cooling"
        const val NOTIFICATION_ID = 4244

        fun schedule(context: Context, delaySeconds: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CoolingWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).setInputData(workDataOf()).build(),
            )
        }
    }
}
