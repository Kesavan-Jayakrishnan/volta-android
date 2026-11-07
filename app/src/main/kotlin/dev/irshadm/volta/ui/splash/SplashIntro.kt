package dev.irshadm.volta.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.irshadm.volta.data.ismart.LinkState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.irshadm.volta.R
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.VoltaColors
import kotlinx.coroutines.flow.first
import kotlin.math.cos
import kotlin.math.exp

/** Same charcoal as the launcher icon and the system splash, so the hand-off is seamless. */
val SplashBg = Color(0xFF292B30)

/** Where the dashboard's car sits on screen (window coordinates) and which picture it shows, so the splash can hand over to it. */
object HeroAnchor {
    var bounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    var image by mutableStateOf<Int?>(null)
}

/**
 * Holds the dashboard back while the splash plays: its sections start their entrance only when the real car
 * starts rising ([holding] = false), and the dashboard's own car stays hidden until the splash car has landed.
 */
object SplashGate {
    var holding by mutableStateOf(false)
    var landed by mutableStateOf(true)
}

/**
 * Cold-start intro, built from the icon's own car: its light lines trace in behind a glowing edge,
 * the car appears dark, the bolt drops in, then the light bar, headlights and badge switch on and
 * "VOLTA" rises. Meanwhile the app fetches fresh data from the car; the line at the bottom follows that
 * fetch. The intro ends once the animation has played and the fetch is done (or failed), waiting at
 * most [MAX_WAIT_MS] beyond the animation; the dashboard then keeps showing that it's updating.
 */
@Composable
fun SplashIntro(link: LinkState, onFinished: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val clock = remember { Animatable(0f) }            // ms on the timeline
    val reveal = remember { Animatable(0f) }          // drawn car → real car, in the middle
    val rise = remember { Animatable(0f) }            // real car → its place on the dashboard (a spring; may pass 1 briefly)
    val sheen = remember { Animatable(0f) }           // a champagne highlight that sweeps the car once it has landed
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The fetch counts as done once a refresh has started and finished (or there's no car link to fetch from).
    var sawRefresh by remember { mutableStateOf(false) }
    val connected = link as? LinkState.Connected
    if (connected?.refreshing == true) sawRefresh = true
    val done = connected == null || (sawRefresh && !connected.refreshing)
    val isDone by rememberUpdatedState(done)
    LaunchedEffect(Unit) {
        if (reduced) clock.snapTo(END) else clock.animateTo(END, tween(END.toInt(), easing = LinearEasing))
        kotlinx.coroutines.withTimeoutOrNull(MAX_WAIT_MS) { snapshotFlow { isDone }.first { it } }
        // Hand-off: the drawn car slowly becomes the real one, holds, then rises into the dashboard while the
        // dashboard's sections come in one after another beneath it.
        reveal.animateTo(1f, tween(if (reduced) 200 else 850, easing = LinearEasing))
        if (!reduced) kotlinx.coroutines.delay(150)
        SplashGate.holding = false
        // A spring, not a tween: the car lifts, eases into its place with a hair of overshoot and settles. The
        // dashboard sections come in beneath it meanwhile; a soft shadow travels with it and fades as it lands.
        if (reduced) rise.animateTo(1f, tween(200))
        else rise.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.78f, stiffness = 95f, visibilityThreshold = 0.002f))
        SplashGate.landed = true
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        if (!reduced) sheen.animateTo(1f, tween(650, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        onFinished()
    }
    val t = clock.value
    fun ease(from: Float, len: Float): Float { val x = ((t - from) / len).coerceIn(0f, 1f); return 1 - (1 - x) * (1 - x) * (1 - x) }
    val trace = ease(0f, 1_300f)
    val body = ease(1_100f, 700f)
    val boltT = ((t - 1_600f) / 700f).coerceIn(0f, 1f)
    val boltDrop = if (boltT <= 0f) 1f else (exp(-6f * boltT) * cos(9f * boltT))   // 1 → 0 with a little bounce
    val lights = ease(2_300f, 350f)                     // off until the bolt lands, then on once
    val word = ease(2_700f, 600f)
    // Waiting for the car after the intro has played: the lights breathe, a bar slides, the dots move.
    val waiting = t >= END && !done && reveal.value == 0f
    val pulse = rememberInfiniteTransition(label = "wait")
    val breath by pulse.animateFloat(1f, 0.45f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val slide by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Reverse), label = "slide")
    val dotsT by pulse.animateFloat(0f, 3f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "dots")
    val waitIn by androidx.compose.animation.core.animateFloatAsState(if (waiting) 1f else 0f, tween(300), label = "waitIn")
    val statusText = when {
        link is LinkState.SignedOut -> "Showing your saved readings"
        link is LinkState.NeedsReconnect -> "Sign in again to get live data"
        !done && (!sawRefresh || t < 1_800f) -> "Connecting to your car…"
        !done -> "Reading battery, location and locks…"
        connected?.error != null -> "Couldn't reach the car — showing the last reading"
        else -> "Updated just now"
    }
    val status = if (!done && statusText.endsWith("…")) statusText.dropLast(1) + ".".repeat(dotsT.toInt() + 1).padEnd(3, ' ') else statusText

    val r = reveal.value
    val p = rise.value
    val pc = p.coerceIn(0f, 1f)
    val fade = (1f - pc / 0.6f).coerceIn(0f, 1f)               // the background clears as the car rises
    // The path is a shallow arc: height follows the spring, while sideways travel and size run on a smooth curve.
    val px = pc * pc * (3 - 2 * pc)
    val lift = kotlin.math.sin(Math.PI.toFloat() * pc)         // 0 → 1 → 0: how far "in the air" the car is
    val labels = (1f - r * 2f).coerceIn(0f, 1f)                 // bolt, wordmark and status go as the real car appears
    // Cross-fade without a dip: the photo comes in over the drawing (sharpening from a soft blur), and only once it's
    // mostly there does the drawing underneath go — so there's never a moment where both are half see-through.
    fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    val photoIn = smooth(r / 0.75f)
    val drawingOut = smooth((r - 0.45f) / 0.55f)
    val soften = (if (reduced) 0f else 1f) * kotlin.math.sin(Math.PI.toFloat() * r)   // 0 → 1 → 0
    var art by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The real car: first exactly where the drawn car is (same size, same centre), then in the dashboard's spot.
    val hero = HeroAnchor.bounds
    val heroImage = HeroAnchor.image
    val start = art?.let { a ->
        val w = a.width * 0.93f / 0.9f; val h = w / HERO_ASPECT
        androidx.compose.ui.geometry.Rect(a.left + a.width * 0.5f - w / 2, a.top + a.height * CAR_CY - h * 0.52f, a.left + a.width * 0.5f + w / 2, a.top + a.height * CAR_CY + h * 0.48f)
    }
    val end = hero?.let { with(density) {
        val cl = it.left + 10.dp.toPx(); val cr = it.right - 10.dp.toPx(); val ct = it.top + 40.dp.toPx(); val cb = it.bottom - 4.dp.toPx()
        val w = minOf(cr - cl, (cb - ct) * HERO_ASPECT); val h = w / HERO_ASPECT
        androidx.compose.ui.geometry.Rect((cl + cr) / 2 - w / 2, (ct + cb) / 2 - h / 2, (cl + cr) / 2 + w / 2, (ct + cb) / 2 + h / 2)
    } }

    Box(
        Modifier.fillMaxSize().drawWithContent { drawRect(SplashBg.copy(alpha = fade)); drawContent() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(
                Modifier.fillMaxWidth(0.92f).aspectRatio(ART_W / ART_H)
                    .placedAt { art = it }
                    .blur((soften * 4).dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
                    .graphicsLayer { alpha = 1f - drawingOut },
            ) {
                val w = maxWidth
                // 1) light lines, revealed left to right
                Image(
                    painterResource(R.drawable.splash_lines), null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - 0.85f * body }.drawWithContent {
                        clipRect(right = size.width * trace) { this@drawWithContent.drawContent() }
                        if (trace in 0.01f..0.99f) {
                            val x = size.width * trace
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, VoltaColors.Champagne.copy(alpha = 0.45f), Color.Transparent), startX = x - 18f, endX = x + 18f),
                                topLeft = androidx.compose.ui.geometry.Offset(x - 18f, size.height * 0.08f),
                                size = androidx.compose.ui.geometry.Size(36f, size.height * 0.84f))
                        }
                    },
                )
                // 2) the car, lights off
                Image(painterResource(R.drawable.splash_car_off), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = body })
                // 3) lights on
                Image(painterResource(R.drawable.splash_lights), null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = lights * (1f - waitIn * (1f - breath)) })
                // 4) the bolt, dropping into place
                Image(
                    painterResource(R.drawable.splash_bolt), null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.offset(x = w * (BOLT_X / ART_W), y = w * (BOLT_Y / ART_W))
                        .size(w * (BOLT_W / ART_W), w * (BOLT_H / ART_W))
                        .graphicsLayer { alpha = (boltT * 2).coerceAtMost(1f) * labels; translationY = -60.dp.toPx() * boltDrop },
                )
            }
            Text(
                "VOLTA", style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 10.sp), color = VoltaColors.Champagne,
                modifier = Modifier.padding(top = 28.dp).graphicsLayer { alpha = word * labels; translationY = 12.dp.toPx() * (1 - word) },
            )
        }
        if (start != null && heroImage != null && r > 0f) {
            val from = start
            val to = end ?: start
            val sc = 1f + (to.width / from.width - 1f) * px
            val tx = from.left + (to.left - from.left) * px
            val ty = from.top + (to.top - from.top) * p
            // Ground shadow: a soft ellipse under the car that only exists while it is in the air.
            androidx.compose.foundation.Canvas(Modifier.matchParentSize().graphicsLayer { alpha = lift * 0.55f * photoIn }) {
                val w = from.width * sc
                val h = from.height * sc
                val cx = tx + w / 2
                val cy = ty + h * 0.94f + lift * 10.dp.toPx()
                drawOval(
                    Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(cx, cy), radius = w * 0.36f),
                    topLeft = androidx.compose.ui.geometry.Offset(cx - w * 0.36f, cy - h * 0.09f), size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.18f),
                )
            }
            Image(
                painterResource(heroImage), null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopStart)
                    .size(with(density) { from.width.toDp() }, with(density) { from.height.toDp() })
                    .graphicsLayer {
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        alpha = photoIn
                        translationX = tx
                        translationY = ty
                        scaleX = sc; scaleY = sc
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // Once landed, a champagne highlight sweeps across the body once: light catching the car.
                        val sh = sheen.value
                        if (sh > 0f && sh < 1f) {
                            val x = size.width * (sh * 1.6f - 0.3f)
                            drawRect(
                                Brush.linearGradient(listOf(Color.Transparent, VoltaColors.Champagne.copy(alpha = 0.38f), Color.Transparent),
                                    start = androidx.compose.ui.geometry.Offset(x - size.width * 0.18f, size.height), end = androidx.compose.ui.geometry.Offset(x + size.width * 0.18f, 0f)),
                                blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                            )
                        }
                    }
                    .blur(((1f - photoIn) * 6).dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
            )
        }
        // A thin bar sliding back and forth while waiting for the car.
        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 30.dp).size(120.dp, 2.dp)
                .graphicsLayer { alpha = waitIn * labels }
                .drawWithContent {
                    drawRect(VoltaColors.Border)
                    val w = size.width * 0.35f
                    drawRect(VoltaColors.Champagne, topLeft = androidx.compose.ui.geometry.Offset((size.width - w) * slide, 0f), size = androidx.compose.ui.geometry.Size(w, size.height))
                },
        )
        Text(status, style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted,
            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = labels }.navigationBarsPadding().padding(bottom = 40.dp))
    }
}

private const val END = 3_400f
/** After the animation, wait this long at most for the car (its reply can take 10 s or more). */
private const val MAX_WAIT_MS = 6_000L
// The artwork (990 × 750) and where the bolt sits in it.
private const val ART_W = 990f
private const val ART_H = 750f
private const val BOLT_X = 445f
private const val BOLT_Y = 12f
private const val BOLT_W = 99f
private const val BOLT_H = 224f
/** The car's centre height in the artwork (the bolt sits above it). */
private const val CAR_CY = 0.62f
/** The dashboard's car photos are 1500 × 804. */
private const val HERO_ASPECT = 1500f / 804f

private fun Modifier.placedAt(onRect: (androidx.compose.ui.geometry.Rect) -> Unit) =
    this.onGloballyPositioned { onRect(it.boundsInWindow()) }
