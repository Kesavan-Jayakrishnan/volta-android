package dev.irshadm.volta.ui.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.Wt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** What the loader shows: where the car is, the road appearing, hills and chargers rising, then a charge. */
enum class LoaderStage { Locating, Route, Terrain, Planning }

/**
 * The planning loader as a night drive. The car holds its place on screen while the world scrolls under it, so it
 * keeps driving however long a step takes: a ping while you're located, the road drawing in ahead as it pulls
 * away, hills rising and charger posts appearing while terrain is read, then the road
 * slows and stops with a charger at the car, which plugs in while the stops are planned.
 */
@Composable
fun PlanningLoader(stage: LoaderStage, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    var t by remember { mutableDoubleStateOf(0.0) }
    var stageAt by remember { mutableDoubleStateOf(0.0) }
    // World scroll (grid units), its speed, and for the last step where it started and where it stops.
    val drive = remember { doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
    LaunchedEffect(stage) {
        stageAt = t
        if (stage == LoaderStage.Planning) {
            drive[2] = drive[0]
            // The first charger at least 140 units ahead of the car (so it rolls in, not snaps).
            val ahead = drive[0] + CAR_X + 22 + 140
            drive[3] = Math.ceil((ahead - CHARGER_PHASE) / CHARGER_EVERY) * CHARGER_EVERY + CHARGER_PHASE - CAR_X - 22
        }
    }
    val current by androidx.compose.runtime.rememberUpdatedState(stage)
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        var last = -1L
        while (true) withFrameNanos { now ->
            val dt = if (last < 0) 0.0 else ((now - last) / 1e9).coerceAtMost(0.05)
            last = now
            t += dt
            when (current) {
                LoaderStage.Planning -> drive[0] = drive[2] + (drive[3] - drive[2]) * smooth((t - stageAt) / 2.6)
                else -> {
                    val want = if (current == LoaderStage.Locating) 0.0 else SPEED
                    drive[1] += (want - drive[1]) * (1 - kotlin.math.exp(-dt * 1.5))
                    drive[0] += drive[1] * dt
                }
            }
        }
    }
    Canvas(modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp))) {
        val since = if (reduced) 9.0 else t - stageAt
        drawDrive(stage, since, if (reduced) 0.0 else t, drive[0].toFloat(), moving = stage != LoaderStage.Locating && (stage != LoaderStage.Planning || since < 2.6))
    }
}

// The scene is laid out on a 596 × 300 grid and scaled to the canvas.
private const val GW = 596f
private const val GH = 300f
private const val CAR_X = 210f
private const val SPEED = 120.0                 // grid units a second
private const val CHARGER_EVERY = 230.0
private const val CHARGER_PHASE = 150.0
private val Night = Color(0xFF0F141B)
private val Hills = Color(0xFF161D26)
private val Road = Color(0xFF3A4655)
private val Post = Color(0xFF1D2430)
private val Body = Color(0xFFD9C08F)
private val Glass = Color(0xFF1D252D)
private val Tyre = Color(0xFF0B0E13)
private val Hub = Color(0xFF8B95A3)
private val Beam = Color(0x2EFFECBE)

private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

/** Ground height at world position [x]; [amp] raises the hills from flat (0) to full (1). */
private fun hillY(x: Float, amp: Float): Float =
    230f - amp * (30f * sin(x / 70f) + 18f * sin(x / 31f + 1f) + 16f * sin(x / 140f + 2f))

private fun DrawScope.drawDrive(stage: LoaderStage, since: Double, t: Double, off: Float, moving: Boolean) {
    val sx = size.width / GW
    val sy = size.height / GH
    fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
    fun ground(screenX: Float, amp: Float) = hillY(screenX + off, amp)
    val s = stage.ordinal
    val u = since / 2.4
    val amp = when { s > 2 -> 1f; s == 2 -> smooth(u * 1.6).toFloat(); else -> 0f }

    drawRect(Night)
    // Stars drift slowly (far away), the hills and road at full speed.
    repeat(40) { i ->
        val x = ((i * 97 - off * 0.08f) % GW + GW) % GW
        drawRect(Color.White.copy(alpha = 0.22f), p(x, (i * 53 % 120).toFloat()), Size(2f * sx, 2f * sy))
    }
    drawPath(Path().apply {
        moveTo(0f, size.height)
        var x = 0f
        while (x <= GW) { lineTo(x * sx, ground(x, amp) * sy); x += 6f }
        lineTo(size.width, size.height); close()
    }, Hills)

    // The road: drawn in ahead of the car once the route is known, with centre dashes that scroll past.
    val road = when { s > 1 -> 1f; s == 1 -> smooth(u * 1.4).toFloat(); else -> 0f }
    if (road > 0f) {
        val end = CAR_X + (GW - CAR_X) * road
        val line = Path().apply {
            moveTo(0f, ground(0f, amp) * sy)
            var x = 6f
            while (x <= end) { lineTo(x * sx, ground(x, amp) * sy); x += 6f }
        }
        drawPath(line, Road, style = Stroke(5f * sy))
        var w = floor((off) / 20f) * 20f - off
        while (w < end) {
            if (w + 10f > 0f) drawLine(Wt.Gold, p(w.coerceAtLeast(0f), ground(w.coerceAtLeast(0f), amp) - 0.5f), p((w + 10f).coerceAtMost(end), ground((w + 10f).coerceAtMost(end), amp) - 0.5f), 1.5f * sy)
            w += 20f
        }
    }

    // Charger posts along the road, every CHARGER_EVERY units of the world; they appear while terrain is read.
    val docked = s == 3 && since >= 2.6
    if (s >= 2) {
        val reveal = if (s == 2) smooth(u * 2.2).toFloat() else 1f
        var k = floor((off - 40f - CHARGER_PHASE) / CHARGER_EVERY).toInt()
        while (true) {
            val sxp = (k * CHARGER_EVERY + CHARGER_PHASE).toFloat() - off
            if (sxp > GW + 40f) break
            if (sxp > -40f) {
                val atCar = docked && kotlin.math.abs(sxp - (CAR_X + 22f)) < 2f
                charger(p(sxp, ground(sxp, amp) - 4f), sx, reveal, charging = atCar, t = t)
            }
            k++
        }
    }

    // Locating: a ping where the car waits.
    if (s == 0) {
        val ph = ((1 + sin(t * 5)) / 2).toFloat()
        drawCircle(Wt.Gold.copy(alpha = 0.6f - ph * 0.5f), (10f + ph * 26f) * sx, p(CAR_X, ground(CAR_X, 0f) - 14f), style = Stroke(2f * sx))
    }

    // The car: fixed on screen, tilted with the slope, wheels turning with the scroll, a little bounce while moving.
    val cy = ground(CAR_X, amp)
    val angle = Math.toDegrees(atan2((ground(CAR_X + 6f, amp) - ground(CAR_X - 6f, amp)) * sy, 12f * sx).toDouble()).toFloat()
    val bob = if (moving) (sin(t * 11.0) * 1.2).toFloat() else 0f
    car(p(CAR_X, cy - 8f + bob), 1.2f * sx, angle, wheel = off / 7f)
    if (docked) {
        val f = smooth((since - 2.6) / 1.2).toFloat() * (0.6f + 0.4f * ((t % 3.0) / 3.0).toFloat())   // keeps topping up while you wait
        val box = p(CAR_X - 22f, cy - 62f)
        drawRoundRect(Post, box, Size(44f * sx, 14f * sx), CornerRadius(4f * sx))
        drawRoundRect(Wt.Green, box + Offset(3f * sx, 3f * sx), Size(38f * sx * (0.14f + 0.86f * f).coerceAtMost(1f), 8f * sx), CornerRadius(2f * sx))
    }
}

/** The car side-on; [wheel] turns the hubs (radians), so they roll with the distance covered. */
private fun DrawScope.car(at: Offset, k: Float, angleDeg: Float, wheel: Float) = translate(at.x, at.y) {
    rotate(angleDeg, Offset.Zero) {
        drawPath(Path().apply { moveTo(18f * k, -6f * k); lineTo(70f * k, -18f * k); lineTo(70f * k, 8f * k); lineTo(18f * k, 0f); close() }, Beam)
        drawRoundRect(Body, Offset(-22f * k, -12f * k), Size(44f * k, 12f * k), CornerRadius(5f * k))
        drawRoundRect(Body, Offset(-12f * k, -22f * k), Size(24f * k, 12f * k), CornerRadius(5f * k))
        drawRect(Glass, Offset(-8f * k, -19f * k), Size(7f * k, 7f * k))
        drawRect(Glass, Offset(2f * k, -19f * k), Size(7f * k, 7f * k))
        listOf(-12f, 12f).forEach { wx ->
            val c = Offset(wx * k, 1f * k)
            drawCircle(Tyre, 6f * k, c)
            drawCircle(Hub, 2.5f * k, c)
            for (sp in 0 until 2) {
                val a = wheel + sp * Math.PI.toFloat() / 2
                val d = Offset(cos(a) * 4.5f * k, sin(a) * 4.5f * k)
                drawLine(Hub, c - d, c + d, 1.2f * k)
            }
        }
    }
}

private fun DrawScope.charger(base: Offset, k: Float, alpha: Float, charging: Boolean, t: Double) {
    if (alpha <= 0f) return
    val top = base - Offset(11f * k, 40f * k)
    drawRoundRect(Post.copy(alpha = alpha), top, Size(22f * k, 40f * k), CornerRadius(4f * k))
    drawRoundRect(Wt.Green.copy(alpha = alpha), top, Size(22f * k, 40f * k), CornerRadius(4f * k), style = Stroke(2f * k))
    val c = base - Offset(0f, 24f * k)
    val bolt = Path().apply {
        val b = 0.8f * k
        moveTo(c.x + 2 * b, c.y - 12 * b); lineTo(c.x - 7 * b, c.y + 2 * b); lineTo(c.x - 1 * b, c.y + 2 * b)
        lineTo(c.x - 3 * b, c.y + 12 * b); lineTo(c.x + 7 * b, c.y - 2 * b); lineTo(c.x + 1 * b, c.y - 2 * b); close()
    }
    drawPath(bolt, Wt.Green.copy(alpha = alpha))
    if (charging) for (i in 0 until 3) {
        val ph = ((t * 0.9 + i / 3.0) % 1.0).toFloat()
        drawCircle(Wt.Green.copy(alpha = (1 - ph) * 0.7f), (14f + ph * 30f) * k, base - Offset(0f, 22f * k), style = Stroke(2f * k))
    }
}
