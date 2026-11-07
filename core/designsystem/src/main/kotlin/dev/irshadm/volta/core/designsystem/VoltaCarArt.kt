package dev.irshadm.volta.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp

/**
 * Volta's car artwork, the same layers the app icon and splash are drawn from: faint light lines, the car, its
 * light bar and headlights, and the bolt above it. With [animate], the bolt drops in and the lights switch on once,
 * as at the end of the splash; with animations turned off on the phone it simply appears lit. [bolt] and [lines]
 * leave those layers out, for places that want just the car.
 *
 * The car is painted on a flat charcoal backdrop, and most of its body is that same charcoal, so it can't be cut out.
 * Passing [background] recolours instead: the backdrop maps exactly onto that colour and everything brighter stretches
 * to keep its contrast, so on that background the car looks as if it had none. [blendEdges] also fades the outer
 * edges, where the backdrop's own soft vignette would otherwise show.
 */
@Composable
fun VoltaCarArt(
    modifier: Modifier = Modifier,
    animate: Boolean = false,
    bolt: Boolean = true,
    lines: Boolean = true,
    background: Color? = null,
    blendEdges: Boolean = false,
) {
    val still = !animate || LocalReducedMotion.current
    val drop = remember { Animatable(if (still) 1f else 0f) }      // bolt: 0 = above, 1 = in place
    val lights = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        // With the bolt, the lights wait for it to land; without, they come on shortly after the screen opens.
        if (bolt) launch { drop.animateTo(1f, tween(BOLT_MS, delayMillis = 150, easing = LinearEasing)) }
        lights.animateTo(1f, tween(if (bolt) 350 else 600, delayMillis = if (bolt) 150 + BOLT_MS else 300))
    }
    val recolour = remember(background) { background?.let(::backdropTo) }

    BoxWithConstraints(modifier.aspectRatio(ART_W / ART_H).then(if (blendEdges) Modifier.fadeEdges() else Modifier)) {
        val w = maxWidth
        if (lines) Image(painterResource(R.drawable.volta_art_lines), null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.15f }, contentScale = ContentScale.FillBounds)
        Image(painterResource(R.drawable.volta_art_car), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = recolour)
        Image(painterResource(R.drawable.volta_art_lights), null, Modifier.fillMaxSize().graphicsLayer { alpha = lights.value }, contentScale = ContentScale.FillBounds)
        if (bolt) Image(
            painterResource(R.drawable.volta_art_bolt), null, contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset(x = w * (BOLT_X / ART_W), y = w * (BOLT_Y / ART_W))
                .size(w * (BOLT_W / ART_W), w * (BOLT_H / ART_W))
                .graphicsLayer {
                    val t = drop.value
                    alpha = (t * 2).coerceAtMost(1f)
                    // Falls from 60dp above with a small bounce, as in the splash.
                    translationY = -60.dp.toPx() * if (t <= 0f) 1f else exp(-6f * t) * cos(9f * t) * (1f - t)
                },
        )
    }
}

/**
 * Fades content to transparent near all four edges. The car nearly fills the artwork's width, so the sides fade over
 * a narrower band than the top and bottom, where there's only backdrop.
 */
private fun Modifier.fadeEdges(sides: Float = 0.12f, topBottom: Float = 0.2f): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            fun fade(band: Float) = arrayOf(0f to Color.Transparent, band to Color.Black, 1f - band to Color.Black, 1f to Color.Transparent)
            drawRect(Brush.horizontalGradient(*fade(sides)), blendMode = BlendMode.DstIn)
            drawRect(Brush.verticalGradient(*fade(topBottom)), blendMode = BlendMode.DstIn)
        }

/**
 * A colour filter that moves the artwork's backdrop charcoal onto [target] and stretches each channel above it, so
 * white stays white and every line keeps its contrast. Anything darker than the backdrop (a little shadow under the
 * car) clamps to black.
 */
private fun backdropTo(target: Color): ColorFilter {
    fun channel(from: Float, to: Float): Pair<Float, Float> {
        val scale = (255f - to) / (255f - from)
        return scale to (to - from * scale)
    }
    val (rs, ro) = channel(BACKDROP_R, target.red * 255f)
    val (gs, go) = channel(BACKDROP_G, target.green * 255f)
    val (bs, bo) = channel(BACKDROP_B, target.blue * 255f)
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                rs, 0f, 0f, 0f, ro,
                0f, gs, 0f, 0f, go,
                0f, 0f, bs, 0f, bo,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
}

/** The backdrop the car is painted on, measured from the artwork. */
private const val BACKDROP_R = 40f
private const val BACKDROP_G = 42f
private const val BACKDROP_B = 47f

private const val ART_W = 990f
private const val ART_H = 750f
private const val BOLT_X = 445f
private const val BOLT_Y = 12f
private const val BOLT_W = 99f
private const val BOLT_H = 224f
private const val BOLT_MS = 700

@Preview(backgroundColor = 0xFF0B0D10, showBackground = true)
@Composable
private fun VoltaCarArtPreview() = VoltaTheme {
    VoltaCarArt(Modifier.fillMaxWidth().padding(24.dp), bolt = false, lines = false, background = VoltaColors.Bg, blendEdges = true)
}
