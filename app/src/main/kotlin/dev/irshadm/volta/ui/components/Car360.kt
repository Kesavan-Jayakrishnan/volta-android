package dev.irshadm.volta.ui.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.irshadm.volta.core.designsystem.LocalReducedMotion
import dev.irshadm.volta.core.designsystem.VoltaColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 360° image sets bundled as `res/drawable-nodpi/car360_<state>_<index>.webp`.
 * Index 0 = front, then clockwise seen from above in 45° steps (1 front-right … 7 front-left).
 * States missing an angle fall back to the locked set; with no set at all the caller shows a single photo.
 */
class Car360Frames(private val ids: Map<CarShot, IntArray>) {
    val frameCount: Int get() = ids[CarShot.Locked]?.size ?: 0
    val available: Boolean get() = frameCount >= 2

    fun frame(shot: CarShot, index: Int): Int {
        val n = frameCount
        val i = ((index % n) + n) % n
        return ids[shot]?.getOrNull(i)?.takeIf { it != 0 } ?: ids.getValue(CarShot.Locked)[i]
    }

    companion object {
        const val ANGLES = 8
        /** Opens on the front three-quarter view, like the single photo. */
        const val DEFAULT_INDEX = 1

        @SuppressLint("DiscouragedApi")
        fun load(context: Context): Car360Frames {
            fun id(name: String) = context.resources.getIdentifier(name, "drawable", context.packageName)
            val map = CarShot.entries.associateWith { shot ->
                IntArray(ANGLES) { id("car360_${shot.name.lowercase()}_$it") }
            }.filterValues { arr -> arr.any { it != 0 } }
            val locked = map[CarShot.Locked]?.takeIf { arr -> arr.all { it != 0 } }
            return Car360Frames(if (locked == null) emptyMap() else map)
        }
    }
}

/**
 * Drag to turn the car. A fling keeps spinning with friction and settles on the nearest angle
 * with a soft spring; changing state (locked → unlocked …) crossfades at the current angle.
 */
/**
 * [showIndex]: a frame to turn to (the car turns to show the wheel that needs a look); [onIndexChange] reports
 * the frame on screen so the caller can place markers on it.
 */
@Composable
fun Car360Viewer(frames: Car360Frames, shot: CarShot, modifier: Modifier = Modifier, showIndex: Int? = null, onIndexChange: (Int) -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val n = frames.frameCount
    val step = 360f / n
    val angle = remember { Animatable(Car360Frames.DEFAULT_INDEX * step) }
    val index = (angle.value / step).roundToInt()
    androidx.compose.runtime.LaunchedEffect(index) { onIndexChange(index.mod(n)) }
    androidx.compose.runtime.LaunchedEffect(showIndex) {
        if (showIndex == null) return@LaunchedEffect
        // The nearest turn of the wheel that lands on that frame.
        val base = showIndex * step
        val target = base + Math.round((angle.value - base) / 360f) * 360f
        if (abs(target - angle.value) > 1f) angle.animateTo(target, if (reduced) tween(0) else spring(dampingRatio = 0.9f, stiffness = 60f))
    }
    // The angle dots show only while turning (and briefly after), so they don't sit on the car.
    var turning by remember { mutableStateOf(false) }
    val dotsAlpha by androidx.compose.animation.core.animateFloatAsState(if (turning) 1f else 0f, tween(if (turning) 120 else 400), label = "dots")
    androidx.compose.runtime.LaunchedEffect(turning, angle.isRunning) {
        if (turning && !angle.isRunning) { kotlinx.coroutines.delay(1_200); turning = false }
    }
    val pxPerDegree = 2.2f       // ~800 px drag ≈ one full turn on a phone

    fun settle(velocityDegPerS: Float) = scope.launch {
        if (!reduced && abs(velocityDegPerS) > 60f) {
            val target = exponentialDecay<Float>(frictionMultiplier = 1.4f).calculateTargetValue(angle.value, velocityDegPerS)
            val snapped = (target / step).roundToInt() * step
            angle.animateTo(snapped, spring(dampingRatio = 0.85f, stiffness = 60f), initialVelocity = velocityDegPerS)
        } else {
            angle.animateTo((angle.value / step).roundToInt() * step, spring(dampingRatio = 1f, stiffness = 400f))
        }
    }

    Box(
        modifier
            .semantics { contentDescription = "Your car, view ${index.mod(n) + 1} of $n. Drag sideways to rotate." }
            .pointerInput(n) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking(); turning = true; scope.launch { angle.stop() } },
                    onHorizontalDrag = { change, dx ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        scope.launch { angle.snapTo(angle.value - dx / pxPerDegree) }
                    },
                    onDragEnd = { settle(-tracker.calculateVelocity().x / pxPerDegree) },
                    onDragCancel = { settle(0f) },
                )
            },
    ) {
        Crossfade(targetState = frames.frame(shot, index), animationSpec = tween(if (reduced) 0 else 110), label = "frame") { res ->
            Image(painterResource(res), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).graphicsLayer { alpha = dotsAlpha }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(n) { i ->
                Box(Modifier.size(if (i == index.mod(n)) 7.dp else 5.dp).clip(CircleShape).background(if (i == index.mod(n)) VoltaColors.Gold else VoltaColors.tint(VoltaColors.Muted, 0.35f)))
            }
        }
    }
}
