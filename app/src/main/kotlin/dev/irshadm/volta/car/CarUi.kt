package dev.irshadm.volta.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.Metadata
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.core.graphics.drawable.IconCompat
import dev.irshadm.volta.R
import kotlin.math.roundToInt

/**
 * Turns the plain UI models into Car App Library templates. Android Auto draws them in its own dark style; Volta
 * sets only the accent (gold for the primary button and the battery ring, green for "on plan"), the icons and the words.
 */
@Suppress("DEPRECATION")
object CarUi {
    private const val GOLD = 0xFFD4AF7C.toInt()
    private const val GOLD_INK = 0xFF1A1407.toInt()
    private const val GREEN = 0xFF7DD39A.toInt()
    private const val AMBER = 0xFFF0C989.toInt()
    private const val RED = 0xFFFF8A80.toInt()
    private const val INK = 0xFFF1F1F1.toInt()
    private const val MUTE = 0xFFA9ABB2.toInt()
    private const val TRACK = 0xFF2A2C31.toInt()
    private const val TARGET = 0xFF3B5A45.toInt()
    private const val GREY = 0xFF5A5D66.toInt()

    val gold: CarColor = CarColor.createCustom(GOLD, GOLD)

    /** Text spans in rows accept the standard car colours only. */
    fun color(t: Tone): CarColor = when (t) {
        Tone.Good -> CarColor.GREEN
        Tone.Warn -> CarColor.YELLOW
        Tone.Bad -> CarColor.RED
        Tone.Neutral -> CarColor.DEFAULT
    }

    fun text(s: CharSequence, tone: Tone): CharSequence = if (tone == Tone.Neutral) s else SpannableString(s).apply { setSpan(ForegroundCarColorSpan.create(color(tone)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    /** "●●●●○" in green / default / yellow, then the line. */
    fun dots(n: Int, text: String?): CharSequence {
        val tone = when {
            n >= 4 -> Tone.Good
            n <= 2 -> Tone.Warn
            else -> Tone.Neutral
        }
        val b = SpannableStringBuilder(text("●".repeat(n.coerceIn(0, CarModels.DOTS)) + "○".repeat((CarModels.DOTS - n).coerceIn(0, CarModels.DOTS)), tone))
        if (!text.isNullOrEmpty()) b.append("  ").append(text)
        return b
    }

    fun row(r: CarRow): Row = Row.Builder().setTitle(r.title).apply {
        if (r.dots != null) addText(dots(r.dots, r.text)) else r.text?.let { addText(text(it, r.tone)) }
    }.build()

    fun limit(carContext: CarContext, type: Int, fallback: Int): Int = if (carContext.carAppApiLevel >= 2) runCatching { carContext.getCarService(ConstraintManager::class.java).getContentLimit(type) }.getOrDefault(fallback) else fallback

    /** A pane: the chip joins the title, the ring is the pane image, the first button is the gold primary. */
    fun pane(carContext: CarContext, ui: PaneUi, root: Boolean, onAction: (CarAction) -> Unit): PaneTemplate {
        val pane = Pane.Builder()
        ui.rows.take(limit(carContext, ConstraintManager.CONTENT_LIMIT_TYPE_PANE, 4)).forEach { pane.addRow(row(it)) }
        ui.actions.take(2).forEachIndexed { i, a -> pane.addAction(action(carContext, a, primary = i == 0, onAction)) }
        if (carContext.carAppApiLevel >= 4) ui.ring?.let { pane.setImage(ringIcon(it)) }
        val b = PaneTemplate.Builder(pane.build())
            .setTitle(ui.chip?.let { "${ui.title} · ${it.text}" } ?: ui.title)
            .setHeaderAction(if (root) Action.APP_ICON else Action.BACK)
        if (ui.headerActions.isNotEmpty()) {
            val strip = ActionStrip.Builder()
            ui.headerActions.take(2).forEach { strip.addAction(action(carContext, it, primary = false, onAction)) }
            b.setActionStrip(strip.build())
        }
        return b.build()
    }

    fun loadingPane(title: String, root: Boolean): PaneTemplate = PaneTemplate.Builder(Pane.Builder().setLoading(true).build()).setTitle(title).setHeaderAction(if (root) Action.APP_ICON else Action.BACK).build()

    fun action(carContext: CarContext, a: CarAction, primary: Boolean, onAction: (CarAction) -> Unit): Action = Action.Builder().setTitle(a.label).setOnClickListener { onAction(a) }.apply {
        if (primary) {
            setBackgroundColor(gold)
            if (carContext.carAppApiLevel >= 4) setFlags(Action.FLAG_PRIMARY)
        }
    }.build()

    /** A list row: numbered badge, name, one or two lines; tapping it (when [onClick] is set) opens the stop. */
    fun stopRow(carContext: CarContext, r: StopRowUi, onClick: (() -> Unit)?): Row = Row.Builder()
        .setTitle(r.title)
        .addText(r.text)
        .apply { r.text2?.let { addText(it) } }
        .setImage(badgeIcon(carContext, r.badge, r.number), Row.IMAGE_TYPE_SMALL)
        .apply {
            if (onClick != null) {
                setOnClickListener(onClick)
                setBrowsable(true)
            }
        }
        .build()

    /** A place row for the chargers map: distance as a DistanceSpan (the host formats it), a lettered marker. */
    fun placeRow(r: ChargerRowUi, label: String, best: Boolean, onClick: () -> Unit): Row {
        // Round for a glance: 450 m, 1.1 km, 12 km.
        val distance = when {
            r.distanceKm < 0.95 -> Distance.create((r.distanceKm * 20).roundToInt() * 50.0, Distance.UNIT_METERS)
            r.distanceKm < 10 -> Distance.create((r.distanceKm * 10).roundToInt() / 10.0, Distance.UNIT_KILOMETERS_P1)
            else -> Distance.create(r.distanceKm.roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
        }
        val line = SpannableString("  · ${r.text}").apply { setSpan(DistanceSpan.create(distance), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
        val marker = PlaceMarker.Builder().setLabel(label).setColor(if (best) CarColor.PRIMARY else CarColor.DEFAULT).build()
        return Row.Builder()
            .setTitle(r.name)
            .addText(line)
            .apply { r.history?.let { addText(text(it, r.historyTone)) } }
            .setMetadata(Metadata.Builder().setPlace(Place.Builder(CarLocation.create(r.position.lat, r.position.lng)).setMarker(marker).build()).build())
            .setOnClickListener(onClick)
            .build()
    }

    fun flagIcon(carContext: CarContext): CarIcon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_flag)).build()

    // ---------------------------------------------------------------- drawn icons

    /** The battery ring: track, charge target (when charging), the charge itself, the number and a short caption. */
    fun ringIcon(r: CarRing): CarIcon {
        val size = 400
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = 34f
        val rect = RectF(stroke, stroke, size - stroke, size - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
        }
        p.color = TRACK
        c.drawArc(rect, -90f, 360f, false, p)
        r.targetSoc?.let {
            p.color = TARGET
            c.drawArc(rect, -90f, 360f * it.coerceIn(0, 100) / 100f, false, p)
        }
        p.color = when (r.tone) {
            Tone.Good -> GREEN
            Tone.Bad -> RED
            Tone.Warn -> AMBER
            Tone.Neutral -> GOLD
        }
        p.strokeCap = Paint.Cap.ROUND
        c.drawArc(rect, -90f, 360f * r.soc.coerceIn(0, 100) / 100f, false, p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 104f
        }
        c.drawText("${r.soc} %", size / 2f, size / 2f + 24f, t)
        t.color = MUTE
        t.textSize = 34f
        c.drawText(r.caption, size / 2f, size / 2f + 78f, t)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    /** Numbered stop badge: gold = next, grey = done, amber = added by a re-plan, white = later; flag = destination. */
    fun badgeIcon(carContext: CarContext, badge: Badge, number: Int?): CarIcon {
        if (badge == Badge.Destination) return flagIcon(carContext)
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = when (badge) {
            Badge.Next -> GOLD
            Badge.Done -> TRACK
            Badge.New -> AMBER
            else -> INK
        }
        val ink = when (badge) {
            Badge.Done -> MUTE
            else -> GOLD_INK
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        if (badge == Badge.Done) {
            c.drawCircle(
                size / 2f,
                size / 2f,
                size / 2f - 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = GREY
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                },
            )
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 52f
        }
        c.drawText(number?.toString() ?: "", size / 2f, size / 2f + 18f, t)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    /** What a button does: navigation goes to the car's navigation app, everything else opens a Volta screen. */
    fun Screen.perform(deps: CarDeps, a: CarAction) {
        when (a) {
            is CarAction.Navigate -> Navigate.to(carContext, a.name, a.to)
            is CarAction.StopDetails -> screenManager.push(StopDetailScreen(carContext, deps, a.chargerId))
            is CarAction.ChargerProblem -> screenManager.push(ChargerProblemScreen(carContext, deps, a.chargerId))
            CarAction.AllStops -> screenManager.push(StopsScreen(carContext, deps))
            CarAction.Chargers -> screenManager.push(ChargersScreen(carContext, deps))
        }
    }
}
