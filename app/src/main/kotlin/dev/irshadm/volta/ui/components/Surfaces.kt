package dev.irshadm.volta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.irshadm.volta.core.designsystem.Wt

@Composable
fun WtCard(
    modifier: Modifier = Modifier,
    background: Color = Wt.Surface,
    border: Color = Wt.Border,
    radius: Dp = 20.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val bg by animateColorAsState(background, tween(260), label = "cardBg")
    val bd by animateColorAsState(border, tween(260), label = "cardBorder")
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.clip(shape).background(bg).border(1.dp, bd, shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Colour-coded status tile (#3 "Friendly" influence): tinted icon chip + value. */
@Composable
fun StatusTile(icon: ImageVector, tint: Color, label: String, modifier: Modifier = Modifier, alert: Boolean = false, onClick: (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    WtCard(
        if (onClick == null) modifier else modifier.pressScale(interaction).clip(RoundedCornerShape(16.dp))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        background = if (alert) Wt.tint(Wt.Amber, 0.08f) else Wt.Surface,
        border = if (alert) Wt.tint(Wt.Amber, 0.4f) else Wt.Border,
        radius = 16.dp,
        padding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(34.dp).clip(RoundedCornerShape(11.dp)).background(Wt.tint(tint)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (alert) Wt.AmberText else Wt.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
        )
    }
}

enum class WtButtonStyle { Primary, Secondary }

@Composable
fun WtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WtButtonStyle = WtButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val base = modifier.heightIn(min = 54.dp).pressScale(interaction).clip(shape)
    val styled = when (style) {
        WtButtonStyle.Primary -> base.background(if (enabled) Wt.Gold else Wt.Border)
        WtButtonStyle.Secondary -> base.border(1.dp, Wt.BorderStrong, shape)
    }
    Row(
        styled.clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (style == WtButtonStyle.Primary) Wt.GoldInk else Wt.Text
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Small toggle chip. */
@Composable
fun WtChip(label: String, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val bg by animateColorAsState(if (on) Wt.tint(Wt.Amber, 0.12f) else Wt.tint(Wt.Muted, 0.06f), tween(200), label = "chip")
    val bd by animateColorAsState(if (on) Wt.tint(Wt.Amber, 0.55f) else Wt.tint(Wt.Muted, 0.22f), tween(200), label = "chipB")
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (on) Wt.AmberText else Wt.TextSoft,
        modifier = Modifier.pressScale(interaction).clip(shape).background(bg).border(1.dp, bd, shape)
            .clickable(interaction, indication = null, role = Role.Switch, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
fun RowScope.Spacer1() = Box(Modifier.weight(1f))
