package dev.irshadm.volta.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/** Motion tokens: strong ease-out for entering, drawer curve for sheets. */
object WtMotion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
    val Drawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** Critically damped default — no overshoot. */
    fun <T> settle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** A little bounce for physical things (doors swinging, alerts dropping in). */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)

    const val PRESS_MS = 140
    const val STAGGER_MS = 60
}
