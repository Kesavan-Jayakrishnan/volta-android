package dev.irshadm.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.irshadm.volta.R
import dev.irshadm.volta.core.designsystem.AppFont
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaMotion
import dev.irshadm.volta.core.model.BodyStatus
import dev.irshadm.volta.core.model.TyrePressures

/** A wheel: its name, and the turntable frame that shows it best (a three-quarter view with that wheel nearest). */
enum class Wheel(val label: String, val bestFrame: Int) {
    FrontLeft("Front-left", 7),
    FrontRight("Front-right", 1),
    RearLeft("Rear-left", 5),
    RearRight("Rear-right", 3),
    ;

    fun psi(t: TyrePressures): Double = when (this) {
        FrontLeft -> t.frontLeft
        FrontRight -> t.frontRight
        RearLeft -> t.rearLeft
        RearRight -> t.rearRight
    }
}

/**
 * Where each wheel's centre sits in each turntable frame (0 front · 1 front-right · 2 right · 3 rear-right · 4 rear ·
 * 5 rear-left · 6 left · 7 front-left), as fractions of the frame. A wheel hidden behind the car isn't listed.
 */
private val WHEEL_ANCHORS: List<Map<Wheel, Offset>> = listOf(
    mapOf(Wheel.FrontLeft to Offset(0.72f, 0.89f), Wheel.FrontRight to Offset(0.28f, 0.89f)),
    mapOf(Wheel.FrontRight to Offset(0.55f, 0.84f), Wheel.RearRight to Offset(0.875f, 0.74f)),
    mapOf(Wheel.FrontRight to Offset(0.19f, 0.84f), Wheel.RearRight to Offset(0.80f, 0.84f)),
    mapOf(Wheel.RearRight to Offset(0.49f, 0.86f), Wheel.FrontRight to Offset(0.095f, 0.74f)),
    mapOf(Wheel.RearLeft to Offset(0.28f, 0.90f), Wheel.RearRight to Offset(0.72f, 0.90f)),
    mapOf(Wheel.RearLeft to Offset(0.55f, 0.86f), Wheel.FrontLeft to Offset(0.875f, 0.74f)),
    mapOf(Wheel.FrontLeft to Offset(0.81f, 0.84f), Wheel.RearLeft to Offset(0.19f, 0.84f)),
    mapOf(Wheel.FrontLeft to Offset(0.55f, 0.84f), Wheel.RearLeft to Offset(0.875f, 0.74f)),
)

/** The frame to turn to: the one showing the most of [wheels], preferring the first wheel's own best view. */
fun frameFor(wheels: Set<Wheel>): Int? {
    if (wheels.isEmpty()) return null
    val counts = WHEEL_ANCHORS.map { f -> wheels.count { it in f } }
    val best = counts.max()
    val first = wheels.first()
    val candidates = WHEEL_ANCHORS.indices.filter { counts[it] == best }
    return candidates.firstOrNull { it == first.bestFrame } ?: candidates.firstOrNull { first in WHEEL_ANCHORS[it] } ?: candidates.first()
}

/** The turntable frames' aspect (car360_*.webp are 1500 × 804). */
private const val FRAME_W = 1500f
private const val FRAME_H = 804f

/** Which photo the hero shows when nothing needs attention. */
enum class CarShot(val res: Int, val frame: Int, val description: String) {
    Locked(R.drawable.car_locked, R.drawable.car360_locked_1, "Your car, locked"),
    Unlocked(R.drawable.car_unlocked, R.drawable.car360_unlocked_1, "Your car, unlocked"),
    Charging(R.drawable.car_charging, R.drawable.car360_charging_1, "Your car, charging"),
    Lights(R.drawable.car_lights, R.drawable.car360_lights_1, "Your car, lights on"),
}

data class CarHeroState(
    val shot: CarShot,
    val locked: Boolean,
    val body: BodyStatus,
    val tyres: TyrePressures?,
) {
    private val lowTyres: List<String>
        get() = tyres?.let { t ->
            buildList {
                if (t.frontLeft < TyrePressures.LOW_PSI) add("Front left tyre low · ${t.frontLeft} psi")
                if (t.frontRight < TyrePressures.LOW_PSI) add("Front right tyre low · ${t.frontRight} psi")
                if (t.rearLeft < TyrePressures.LOW_PSI) add("Rear left tyre low · ${t.rearLeft} psi")
                if (t.rearRight < TyrePressures.LOW_PSI) add("Rear right tyre low · ${t.rearRight} psi")
            }
        }.orEmpty()

    /** Human-readable alerts, most important first. Driver door = front right (India RHD). */
    val alerts: List<String>
        get() = buildList {
            if (body.frontRightOpen) add("Driver door open")
            if (body.frontLeftOpen) add("Front passenger door open")
            if (body.rearLeftOpen) add("Rear left door open")
            if (body.rearRightOpen) add("Rear right door open")
            if (body.bootOpen) add("Boot open")
            if (body.bonnetOpen) add("Bonnet open")
            addAll(lowTyres)
        }
}

/*
 * Car hero: a photo for normal states, switching to a top-down status diagram whenever something
 * needs attention (or when the user asks for it).
 */

/**
 * [lowWheels]: tyres worth a look. The car turns to the frame that shows the first one and an amber dot sits on
 * each such wheel while it is in view; tapping a dot calls [onTyreTap].
 */
@Composable
fun CarHero(
    state: CarHeroState,
    showDiagram: Boolean,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Boolean = true,
    lowWheels: Set<Wheel> = emptySet(),
    onTyreTap: () -> Unit = {},
    /** Only the Windsor has the 360° view; other models show [CarPictures.hero]. */
    model: dev.irshadm.volta.data.ismart.CarModel = dev.irshadm.volta.data.ismart.CarModel.Windsor,
) {
    val alerts = state.alerts
    val diagram = showDiagram || alerts.isNotEmpty()
    val context = androidx.compose.ui.platform.LocalContext.current
    val frames = remember { Car360Frames.load(context) }
    Box(modifier.fillMaxWidth().height(250.dp)) {
        if (backdrop) DuskBackdrop(Modifier.fillMaxSize())

        AnimatedContent(
            targetState = diagram,
            transitionSpec = {
                (fadeIn(tween(320)) + scaleIn(tween(420, easing = VoltaMotion.EaseOut), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(220)) + scaleOut(tween(320), targetScale = 1.03f))
            },
            label = "heroView",
            modifier = Modifier.fillMaxSize(),
        ) { showingDiagram ->
            if (showingDiagram) {
                Box(Modifier.fillMaxSize().padding(top = if (alerts.isNotEmpty()) 44.dp else 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                    TopDownCar(state, Modifier.fillMaxSize())
                }
            } else if (model != dev.irshadm.volta.data.ismart.CarModel.Windsor) {
                val picture = CarPictures.hero(model, state.shot)
                androidx.compose.runtime.SideEffect { dev.irshadm.volta.ui.splash.HeroAnchor.image = picture }
                Crossfade(state.shot, animationSpec = tween(380), label = "shot") { shot ->
                    Image(
                        painterResource(CarPictures.hero(model, shot)),
                        contentDescription = shot.description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 40.dp, bottom = 4.dp)
                            .graphicsLayer {
                                alpha = if (dev.irshadm.volta.ui.splash.SplashGate.landed) 1f else 0f
                                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                            }
                            .champagneLight(),
                    )
                }
            } else {
                if (frames.available) {
                    androidx.compose.runtime.SideEffect { dev.irshadm.volta.ui.splash.HeroAnchor.image = frames.frame(state.shot, Car360Frames.DEFAULT_INDEX) }
                    var frameIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Car360Frames.DEFAULT_INDEX) }
                    Box(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 40.dp, bottom = 4.dp)) {
                        Car360Viewer(
                            frames,
                            state.shot,
                            Modifier.fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (dev.irshadm.volta.ui.splash.SplashGate.landed) 1f else 0f
                                    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                }
                                .champagneLight(),
                            showIndex = frameFor(lowWheels),
                            onIndexChange = { frameIndex = it },
                        )
                        if (lowWheels.isNotEmpty() && dev.irshadm.volta.ui.splash.SplashGate.landed) TyreDots(frameIndex, lowWheels, onTyreTap, Modifier.fillMaxSize())
                    }
                } else {
                    androidx.compose.runtime.SideEffect { dev.irshadm.volta.ui.splash.HeroAnchor.image = state.shot.res }
                    Crossfade(state.shot, animationSpec = tween(380), label = "shot") { shot ->
                        Image(
                            painterResource(shot.res),
                            contentDescription = shot.description,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 40.dp, bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Amber dots on the wheels that need a look, placed on the photo as it is fitted in the box; each is a 44 dp target. */
@Composable
private fun TyreDots(index: Int, wheels: Set<Wheel>, onTap: () -> Unit, modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "tyreDot").animateFloat(0.14f, 0.30f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "tyreDotA")
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val ratio = FRAME_W / FRAME_H
        val w = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
        val h = w / ratio
        val x0 = (maxWidth - w) / 2
        val y0 = (maxHeight - h) / 2
        val anchors = WHEEL_ANCHORS[index.mod(WHEEL_ANCHORS.size)]
        val target = 44.dp
        wheels.forEach { wheel ->
            anchors[wheel]?.let { a ->
                Box(
                    Modifier.offset(x0 + w * a.x - target / 2, y0 + h * a.y - target / 2).size(target)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "${wheel.label} tyre, open tyre detail", onClick = onTap),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(VoltaColors.Amber.copy(alpha = pulse)))
                    Box(Modifier.size(14.dp).clip(androidx.compose.foundation.shape.CircleShape).background(VoltaColors.Amber).border(2.dp, VoltaColors.Bg, androidx.compose.foundation.shape.CircleShape))
                }
            }
        }
    }
}

/**
 * Dusk sky in champagne tones + two mountain ridges, with a soft glow behind the car. [heroTop] and [heroHeight]
 * place the car's area inside a taller canvas (the dashboard draws it from the very top of the screen down past
 * the location line), so the sky continues behind the header with no edge; it fades into the page at the bottom.
 */
@Composable
fun DuskBackdrop(modifier: Modifier, heroTop: Float = 0f, heroHeight: Float? = null) {
    Canvas(modifier) {
        val hh = heroHeight ?: size.height
        fun f(heroFraction: Float) = ((heroTop + heroFraction * hh) / size.height).coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(0f to VoltaColors.Bg, f(0.0f) to VoltaColors.Bg, f(0.42f) to Color(0xFF2A241A), f(0.6f) to Color(0xFF5C4A30), f(1f) to Color(0xFF0F0D0B), 1f to VoltaColors.Bg))
        // Champagne glow behind the car, low and wide like the last light on the horizon.
        drawRect(
            Brush.radialGradient(
                listOf(VoltaColors.Champagne.copy(alpha = 0.34f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.58f, heroTop + hh * 0.62f),
                radius = size.width * 0.62f,
            ),
        )
        val sx = size.width / 390f
        val sy = hh / 250f
        fun ridge(points: List<Pair<Float, Float>>, color: Color) {
            val p = Path().apply {
                moveTo(0f, heroTop + points.first().second * sy)
                points.forEach { (x, y) -> lineTo(x * sx, heroTop + y * sy) }
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(p, color)
        }
        ridge(listOf(0f to 170f, 60f to 130f, 110f to 150f, 170f to 105f, 230f to 140f, 290f to 112f, 350f to 138f, 390f to 120f), Color(0xFF17140F))
        ridge(listOf(0f to 190f, 80f to 160f, 150f to 178f, 220f to 150f, 300f to 172f, 390f to 155f), Color(0xFF0F0D0B))
        // Melt into the page below the car.
        val fadeFrom = (heroTop + hh * 0.86f).coerceAtMost(size.height)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, VoltaColors.Bg), startY = fadeFrom, endY = size.height),
            topLeft = androidx.compose.ui.geometry.Offset(0f, fadeFrom),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - fadeFrom),
        )
    }
}

/**
 * Top-down car from real renders, in layers: the closed car, then one image per open part (each door, boot,
 * bonnet) and the lit lamps, stacked so any combination shows. Open parts glow amber; tyre pressures sit
 * beside each wheel, low ones amber and pulsing. [compact] is the small inline version, with psi as whole numbers.
 */
@Composable
fun TopDownCar(state: CarHeroState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val b = state.body
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        0.06f,
        0.26f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulseA",
    )
    val parts = listOf(
        R.drawable.cartop_door_fr to b.frontRightOpen,
        R.drawable.cartop_door_fl to b.frontLeftOpen,
        R.drawable.cartop_door_rr to b.rearRightOpen,
        R.drawable.cartop_door_rl to b.rearLeftOpen,
        R.drawable.cartop_boot to b.bootOpen,
        R.drawable.cartop_bonnet to b.bonnetOpen,
    )
    val measurer = rememberTextMeasurer()
    val t = state.tyres
    val label = state.alerts.ifEmpty { listOf("All doors closed, tyres normal") }.joinToString()

    Box(modifier.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().aspectRatio(TOP_W / TOP_H)) {
            val layer = Modifier.fillMaxSize()
            Image(painterResource(R.drawable.cartop_base), null, layer, contentScale = ContentScale.Fit)
            if (state.shot == CarShot.Lights) Image(painterResource(R.drawable.cartop_lights), null, layer, contentScale = ContentScale.Fit)
            parts.forEach { (res, open) ->
                AnimatedVisibility(open, enter = fadeIn(tween(260)), exit = fadeOut(tween(200))) {
                    Box {
                        Image(painterResource(res), null, layer, contentScale = ContentScale.Fit)
                        // The same part again, tinted amber and breathing: "this one".
                        Image(
                            painterResource(res),
                            null,
                            layer.graphicsLayer { alpha = pulse },
                            contentScale = ContentScale.Fit,
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(VoltaColors.Amber, androidx.compose.ui.graphics.BlendMode.SrcAtop),
                        )
                    }
                }
            }
            if (t != null) {
                Canvas(layer) {
                    fun psi(v: Double, fx: Float, fy: Float, rightSide: Boolean) {
                        val low = v < TyrePressures.LOW_PSI
                        val c = Offset(size.width * fx, size.height * fy)
                        if (low) {
                            drawRoundRect(
                                VoltaColors.Amber.copy(alpha = pulse + 0.2f),
                                c - Offset(size.width * 0.035f, size.height * 0.055f),
                                Size(size.width * 0.07f, size.height * 0.11f),
                                CornerRadius(size.width * 0.03f),
                            )
                        }
                        val layout = measurer.measure(
                            if (compact) "${Math.round(v)}" else "${Math.round(v)} psi",
                            TextStyle(fontFamily = AppFont, fontSize = if (compact) 10.sp else 12.sp, color = if (low) VoltaColors.Amber else VoltaColors.TextSoft),
                        )
                        val gap = size.width * 0.06f
                        drawText(layout, topLeft = Offset(if (rightSide) c.x + gap else c.x - gap - layout.size.width, c.y - layout.size.height / 2f))
                    }
                    psi(t.frontLeft, WHEEL_L, WHEEL_FRONT, false)
                    psi(t.frontRight, WHEEL_R, WHEEL_FRONT, true)
                    psi(t.rearLeft, WHEEL_L, WHEEL_REAR, false)
                    psi(t.rearRight, WHEEL_R, WHEEL_REAR, true)
                }
            }
        }
    }
}

// The top-view frame (cartop_*.webp) and where the wheels sit in it, as fractions.
internal const val TOP_W = 896f
internal const val TOP_H = 1200f
internal const val WHEEL_L = 0.21f
internal const val WHEEL_R = 0.79f
internal const val WHEEL_FRONT = 0.22f
internal const val WHEEL_REAR = 0.78f

/**
 * Champagne colour grade on the car: its pixels are multiplied by a warm tone (stronger towards the upper
 * right, where the light comes from), so chrome, glass and reflections turn champagne while the black paint
 * stays deep. Transparent areas stay transparent.
 */
private fun Modifier.champagneLight() = this.drawWithContent {
    drawContent()
    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFFE2C79C), Color(0xFFF0E2CC), Color.White),
            start = androidx.compose.ui.geometry.Offset(size.width, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height),
        ),
        blendMode = androidx.compose.ui.graphics.BlendMode.Modulate,
    )
}
