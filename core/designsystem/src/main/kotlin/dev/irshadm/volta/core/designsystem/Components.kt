package dev.irshadm.volta.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** Every pressable shrinks to 0.97 while held: instant feedback on touch-down. */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(WtMotion.PRESS_MS, easing = WtMotion.EaseOut), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

enum class WtButtonStyle { Primary, Secondary }

/** The app's button. [busy] swaps the icon for a spinner and blocks taps, so callers never double-submit. */
@Composable
fun WtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WtButtonStyle = WtButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val active = enabled && !busy
    val base = modifier.heightIn(min = 54.dp).pressScale(interaction).clip(shape)
    val styled = when (style) {
        WtButtonStyle.Primary -> base.background(if (enabled) Wt.Gold else Wt.Border)
        WtButtonStyle.Secondary -> base.border(1.dp, Wt.BorderStrong, shape)
    }
    Row(
        styled.clickable(interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (style == WtButtonStyle.Primary) Wt.GoldInk else Wt.Text
        when {
            busy -> CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            icon != null -> Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** A quiet text button in the accent colour ("Skip for now", "Not now"). */
@Composable
fun WtTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = Wt.Gold, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(12.dp),
    )
}

/** Tone of a [WtNote]: what the note asks of the reader. */
enum class NoteTone(internal val accent: Color, internal val fill: Color, internal val text: Color) {
    Caution(Wt.Amber, Wt.GoldInk, Wt.AmberText),
    Error(Wt.Red, Wt.RedInk, Wt.TextSoft),
    Success(Wt.Green, Wt.tint(Wt.Green, 0.08f), Wt.TextSoft),
}

/** A tinted note card with an icon, an optional bold [title] and a [body]. */
@Composable
fun WtNote(tone: NoteTone, body: String, modifier: Modifier = Modifier, title: String? = null, icon: ImageVector = WtIcons.Warning) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(tone.fill).border(1.dp, Wt.tint(tone.accent, 0.35f), shape).padding(14.dp, 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = tone.accent, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Wt.Text) }
            Text(body, style = MaterialTheme.typography.bodySmall, color = tone.text)
        }
    }
}

/**
 * Where you are in a short flow, in words ("STEP 1 OF 2"). Words rather than dots, so it can't be mistaken for a
 * carousel you could swipe.
 */
@Composable
fun StepLabel(current: Int, total: Int, modifier: Modifier = Modifier) {
    Text(
        "STEP $current OF $total", modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.14.em), color = Wt.Dim,
    )
}

@Preview(backgroundColor = 0xFF0B0D10, showBackground = true)
@Composable
private fun ComponentsPreview() = VoltaTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepLabel(current = 1, total = 2)
        WtNote(NoteTone.Caution, "Signing in here signs the official MG app out.")
        WtNote(NoteTone.Error, "Routes API isn't enabled for this key's project.", title = "Google said no")
        WtButton("Continue", onClick = {}, modifier = Modifier.fillMaxWidth())
        WtButton("Checking with Google…", onClick = {}, modifier = Modifier.fillMaxWidth(), busy = true)
        WtTextButton("Skip for now", onClick = {})
    }
}
