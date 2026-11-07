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
import dev.irshadm.volta.core.designsystem.VoltaColors

@Composable
fun VoltaCard(
    modifier: Modifier = Modifier,
    background: Color = VoltaColors.Surface,
    border: Color = VoltaColors.Border,
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
    VoltaCard(
        if (onClick == null) {
            modifier
        } else {
            modifier.pressScale(interaction).clip(RoundedCornerShape(16.dp))
                .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
        },
        background = if (alert) VoltaColors.tint(VoltaColors.Amber, 0.08f) else VoltaColors.Surface,
        border = if (alert) VoltaColors.tint(VoltaColors.Amber, 0.4f) else VoltaColors.Border,
        radius = 16.dp,
        padding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(34.dp).clip(RoundedCornerShape(11.dp)).background(VoltaColors.tint(tint)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp)) }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (alert) VoltaColors.AmberText else VoltaColors.Text,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
        )
    }
}

enum class VoltaButtonStyle { Primary, Secondary }

@Composable
fun VoltaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: VoltaButtonStyle = VoltaButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val base = modifier.heightIn(min = 54.dp).pressScale(interaction).clip(shape)
    val styled = when (style) {
        VoltaButtonStyle.Primary -> base.background(if (enabled) VoltaColors.Gold else VoltaColors.Border)
        VoltaButtonStyle.Secondary -> base.border(1.dp, VoltaColors.BorderStrong, shape)
    }
    Row(
        styled.clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (style == VoltaButtonStyle.Primary) VoltaColors.GoldInk else VoltaColors.Text
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(18.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Small toggle chip. */
@Composable
fun VoltaChip(label: String, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val bg by animateColorAsState(if (on) VoltaColors.tint(VoltaColors.Amber, 0.12f) else VoltaColors.tint(VoltaColors.Muted, 0.06f), tween(200), label = "chip")
    val bd by animateColorAsState(if (on) VoltaColors.tint(VoltaColors.Amber, 0.55f) else VoltaColors.tint(VoltaColors.Muted, 0.22f), tween(200), label = "chipB")
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (on) VoltaColors.AmberText else VoltaColors.TextSoft,
        modifier = Modifier.pressScale(interaction).clip(shape).background(bg).border(1.dp, bd, shape)
            .clickable(interaction, indication = null, role = Role.Switch, onClick = onClick)
            .heightIn(min = 36.dp).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
fun RowScope.Spacer1() = Box(Modifier.weight(1f))
