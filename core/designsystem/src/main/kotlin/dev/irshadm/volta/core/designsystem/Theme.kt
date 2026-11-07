package dev.irshadm.volta.core.designsystem

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Volta's dark-only Material 3 theme. */
private val Scheme = darkColorScheme(
    primary = VoltaColors.Gold,
    onPrimary = VoltaColors.GoldInk,
    secondary = VoltaColors.Champagne,
    background = VoltaColors.Bg,
    onBackground = VoltaColors.Text,
    surface = VoltaColors.Surface,
    onSurface = VoltaColors.Text,
    surfaceVariant = VoltaColors.Surface,
    onSurfaceVariant = VoltaColors.Muted,
    surfaceContainer = VoltaColors.Surface,
    surfaceContainerHigh = VoltaColors.SurfaceHigh,
    surfaceContainerHighest = VoltaColors.SurfaceHigh,
    outline = VoltaColors.BorderStrong,
    outlineVariant = VoltaColors.Border,
    error = VoltaColors.Amber,
    errorContainer = VoltaColors.AmberTint,
    onErrorContainer = VoltaColors.AmberText,
)

/** True when the user turned animations off (Developer options / Accessibility). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun VoltaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = Scheme, typography = VoltaTypography, content = content)
    }
}
