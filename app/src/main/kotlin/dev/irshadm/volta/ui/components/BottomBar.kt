package dev.irshadm.volta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.irshadm.volta.core.designsystem.Wt

data class BottomTab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun WtBottomBar(tabs: List<BottomTab>, current: String?, onSelect: (BottomTab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Wt.Bg.copy(alpha = 0.94f)).navigationBarsPadding()) {
        HorizontalDivider(color = Wt.Border)
        Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val selected = tab.route == current
                val color by animateColorAsState(if (selected) Wt.Champagne else Wt.Dim, tween(220), label = "tab")
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier.widthIn(min = 64.dp).pressScale(interaction)
                        .clickable(interaction, indication = null, role = Role.Tab) { onSelect(tab) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelSmall, color = color)
                }
            }
        }
    }
}

/** Shared "arrives in a later milestone" screen for tabs not built yet. */
@Composable
fun ComingSoon(title: String, milestone: String, description: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(20.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.appear(0))
        WtCard(Modifier.fillMaxWidth().appear(1)) {
            Box(Modifier.size(44.dp).background(Wt.tint(Wt.Gold), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Wt.Gold, modifier = Modifier.size(22.dp))
            }
            Text(milestone, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Wt.Muted)
        }
    }
}
