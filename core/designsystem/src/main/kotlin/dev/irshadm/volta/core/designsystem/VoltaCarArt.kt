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
 * as at the end of the splash; with animations turned off on the phone it simply appears lit.
 * With [blendEdges], the artwork's charcoal backdrop fades out towards every edge, so it sits straight on any dark
 * background without a visible box.
 */
@Composable
fun VoltaCarArt(modifier: Modifier = Modifier, animate: Boolean = false, blendEdges: Boolean = false) {
    val still = !animate || LocalReducedMotion.current
    val drop = remember { Animatable(if (still) 1f else 0f) }      // bolt: 0 = above, 1 = in place
    val lights = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        launch { drop.animateTo(1f, tween(BOLT_MS, delayMillis = 150, easing = LinearEasing)) }
        lights.animateTo(1f, tween(350, delayMillis = 150 + BOLT_MS))
    }

    BoxWithConstraints(modifier.aspectRatio(ART_W / ART_H).then(if (blendEdges) Modifier.fadeEdges() else Modifier)) {
        val w = maxWidth
        Image(painterResource(R.drawable.volta_art_lines), null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.15f }, contentScale = ContentScale.FillBounds)
        Image(painterResource(R.drawable.volta_art_car), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Image(painterResource(R.drawable.volta_art_lights), null, Modifier.fillMaxSize().graphicsLayer { alpha = lights.value }, contentScale = ContentScale.FillBounds)
        Image(
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
    VoltaCarArt(Modifier.fillMaxWidth().padding(24.dp), blendEdges = true)
}
