package dev.irshadm.volta.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings

/** Volta's dark-only Material 3 theme. */
private val Scheme = darkColorScheme(
    primary = Wt.Gold,
    onPrimary = Wt.GoldInk,
    secondary = Wt.Champagne,
    background = Wt.Bg,
    onBackground = Wt.Text,
    surface = Wt.Surface,
    onSurface = Wt.Text,
    surfaceVariant = Wt.Surface,
    onSurfaceVariant = Wt.Muted,
    surfaceContainer = Wt.Surface,
    surfaceContainerHigh = Wt.SurfaceHigh,
    surfaceContainerHighest = Wt.SurfaceHigh,
    outline = Wt.BorderStrong,
    outlineVariant = Wt.Border,
    error = Wt.Amber,
    errorContainer = Wt.AmberTint,
    onErrorContainer = Wt.AmberText,
)

/** True when the user turned animations off (Developer options / Accessibility). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun VoltaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = Scheme, typography = WtTypography, content = content)
    }
}
