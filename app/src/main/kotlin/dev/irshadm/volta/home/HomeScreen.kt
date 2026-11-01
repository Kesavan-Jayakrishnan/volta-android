package dev.irshadm.volta.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.core.designsystem.VoltaTheme
import dev.irshadm.volta.core.designsystem.Wt
import dev.irshadm.volta.core.designsystem.WtButton
import dev.irshadm.volta.core.designsystem.WtButtonStyle
import dev.irshadm.volta.core.designsystem.WtIcons

@Composable
fun HomeRoute(onOpenMapsKey: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    HomeScreen(state, onOpenMapsKey, vm::signOut)
}

/** The signed-in home. For now: the account, the Google key, and sign out; the dashboard lands here next. */
@Composable
fun HomeScreen(state: HomeUiState, onOpenMapsKey: () -> Unit, onSignOut: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Wt.Bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Volta", style = MaterialTheme.typography.headlineLarge, color = Wt.Text)
        SettingRow(WtIcons.Car, "MG i-SMART", state.phone?.let { "+91 $it" } ?: "Not signed in", onClick = null)
        SettingRow(WtIcons.Key, "Google Maps key", if (state.hasMapsKey) "Added" else "Not added: trip planning needs one", onClick = onOpenMapsKey)
        Spacer(Modifier.weight(1f))
        WtButton("Sign out", onSignOut, Modifier.fillMaxWidth(), style = WtButtonStyle.Secondary)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, value: String, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Wt.Surface).border(1.dp, Wt.Border, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Wt.Gold, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Wt.Text)
            Text(value, style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
        }
        if (onClick != null) Icon(WtIcons.Chevron, null, tint = Wt.Dim, modifier = Modifier.size(18.dp))
    }
}

@Preview
@Composable
private fun HomePreview() = VoltaTheme { HomeScreen(HomeUiState(phone = "9800000012", hasMapsKey = false), {}, {}) }
