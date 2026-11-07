package dev.irshadm.volta.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.VoltaMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Every pressable shrinks to 0.97 while held — instant feedback on touch-down. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(VoltaMotion.PRESS_MS, easing = VoltaMotion.EaseOut), label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Staggered entrance: fade + 12dp rise, [index] × 60 ms after first composition. Plays once. */
fun Modifier.appear(index: Int): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    val rise = remember { Animatable(if (reduced) 0f else 1f) }
    val px = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        // Under the splash, wait until it releases the dashboard; then sections come in top to bottom as the car rises.
        if (dev.irshadm.volta.ui.splash.SplashGate.holding) {
            androidx.compose.runtime.snapshotFlow { dev.irshadm.volta.ui.splash.SplashGate.holding }.first { !it }
            delay(80L + index * 90L)
        } else {
            delay(index * VoltaMotion.STAGGER_MS.toLong())
        }
        launch { alpha.animateTo(1f, tween(420, easing = VoltaMotion.EaseOut)) }
        rise.animateTo(0f, tween(560, easing = VoltaMotion.EaseOut))
    }
    graphicsLayer {
        this.alpha = alpha.value
        translationY = rise.value * px
    }
}
