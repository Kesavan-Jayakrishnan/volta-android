package dev.irshadm.volta.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.data.ismart.IsmartRepository
import javax.inject.Inject
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.ui.components.WtButton
import dev.irshadm.volta.ui.components.WtButtonStyle
import dev.irshadm.volta.core.designsystem.WtIcons
import dev.irshadm.volta.core.designsystem.Wt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@HiltViewModel
class AccountGateViewModel @Inject constructor(val ismart: IsmartRepository) : ViewModel() {
    fun reconnect() = viewModelScope.launch { ismart.reconnect() }
    fun dismissReconnect() = ismart.dismissReconnect()
    /** Signing out sends the app back to the sign-in screen; the saved readings stay until a new account signs in. */
    fun differentAccount() = ismart.signOut(keepReadings = true)
}

/**
 * Car connection lost: the session was taken by the MG app or expired, the saved login is still
 * good. Full screen until Reconnect works or "Keep showing saved readings" dismisses it; the dashboard's amber
 * banner stays after that. With 3 sign-ins an hour allowed, the button waits for the next slot.
 */
@Composable
fun ConnectionLostScreen(vm: AccountGateViewModel = hiltViewModel()) {
    val link by vm.ismart.link.collectAsStateWithLifecycle()
    val status by vm.ismart.status.collectAsStateWithLifecycle()
    val reconnecting by vm.ismart.reconnecting.collectAsStateWithLifecycle()
    val lostError = (link as? LinkState.NeedsReconnect)?.error
    val retryAt = remember(link) { vm.ismart.nextLoginAllowedAt()?.takeIf { it > System.currentTimeMillis() } }
    val now = System.currentTimeMillis()
    fun clock(ms: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).lowercase()

    val soc = status?.soc?.value
    val readingAt = soc?.measuredAtMillis
    val ageMin = readingAt?.let { ((now - it) / 60_000).toInt() }
    val carLine = listOfNotNull(
        soc?.let { "${it.socPercent.roundToInt()} %" },
        if (status?.ignitionOn?.value == true) "driving" else "parked",
        status?.locked?.value?.let { if (it) "locked" else "unlocked" },
    ).joinToString(" · ")

    Column(
        Modifier.fillMaxSize().background(Wt.Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        Box(Modifier.size(84.dp).clip(CircleShape).background(Wt.GoldInk).border(1.dp, Wt.tint(Wt.Amber, 0.35f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (retryAt != null) WtIcons.Clock else WtIcons.Warning, null, tint = Wt.Amber, modifier = Modifier.size(40.dp))
        }
        Text(
            if (retryAt != null) "Signed in too often this hour" else "Car connection lost",
            style = MaterialTheme.typography.headlineSmall, color = Wt.Text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            if (retryAt != null) "Volta and the MG app have been taking the session from each other. To stay within MG's limits, Volta waits before signing in again."
            else "The MG i-SMART app signed in, or the session expired. Your login is saved, so one tap brings live data back.",
            style = MaterialTheme.typography.bodyLarge, color = Wt.TextSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp, start = 8.dp, end = 8.dp),
        )

        Column(
            Modifier.padding(top = 32.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Wt.Surface).border(1.dp, Wt.Border, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            if (retryAt != null) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Next attempt at ${clock(retryAt)}", style = MaterialTheme.typography.titleSmall, color = Wt.Text)
                        Text("3 sign-ins in the last hour · the limit is 3", style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
                    }
                    Text("in ${((retryAt - now) / 60_000).coerceAtLeast(1)} min", style = MaterialTheme.typography.labelMedium, color = Wt.AmberText)
                }
                HorizontalDivider(color = Wt.Border)
                Text("Close the MG i-SMART app on every phone first, or the two will keep signing each other out.",
                    style = MaterialTheme.typography.bodySmall, color = Wt.TextSoft, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(WtIcons.Clock, null, tint = Wt.Muted, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ageMin?.let { "Last reading ${if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min"} ago" } ?: "No saved reading", style = MaterialTheme.typography.titleSmall, color = Wt.Text)
                        if (carLine.isNotEmpty()) Text(carLine, style = MaterialTheme.typography.bodySmall, color = Wt.Muted)
                    }
                }
                HorizontalDivider(color = Wt.Border)
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(WtIcons.Info, null, tint = Wt.Muted, modifier = Modifier.size(20.dp))
                    Text(lostError ?: "Reconnecting signs the MG app out again. Live trip re-planning and alerts stay paused until then.",
                        style = MaterialTheme.typography.bodySmall, color = if (lostError != null) Wt.AmberText else Wt.TextSoft, modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WtButton(
                when { reconnecting -> "Reconnecting…"; retryAt != null -> "Reconnect at ${clock(retryAt)}"; else -> "Reconnect" },
                { vm.reconnect() }, Modifier.fillMaxWidth(), enabled = !reconnecting && retryAt == null,
            )
            WtButton("Keep showing saved readings", { vm.dismissReconnect() }, Modifier.fillMaxWidth(), style = WtButtonStyle.Secondary)
            Text(
                "Sign in with a different account", style = MaterialTheme.typography.labelLarge, color = Wt.Gold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.differentAccount() }.padding(12.dp),
            )
        }
    }
}
