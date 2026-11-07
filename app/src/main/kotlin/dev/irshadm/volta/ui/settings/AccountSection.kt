package dev.irshadm.volta.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.ui.components.VoltaButton
import dev.irshadm.volta.ui.components.VoltaButtonStyle
import dev.irshadm.volta.core.designsystem.VoltaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(val ismart: IsmartRepository) : ViewModel()

/** MG i-SMART account: who is signed in, the control PIN for remote commands, and sign-out. */
@Composable
fun MgAccountSection(vm: AccountViewModel = hiltViewModel()) {
    val link by vm.ismart.link.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinSet by remember { mutableStateOf(vm.ismart.hasPin) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedContent(link is LinkState.SignedOut, label = "account") { signedOut ->
            if (signedOut) {
                // Signed out, the sign-in screen covers the app, so this side never shows.
                Text("Not signed in", style = MaterialTheme.typography.titleSmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val number = when (val l = link) { is LinkState.Connected -> l.phone; is LinkState.NeedsReconnect -> l.phone; else -> "" }
                    Text("Signed in as +91 $number", style = MaterialTheme.typography.titleSmall)
                    if (link is LinkState.NeedsReconnect) {
                        Text("Signed out by another app.", style = MaterialTheme.typography.bodySmall, color = VoltaColors.Amber)
                        VoltaButton("Reconnect", { scope.launch { vm.ismart.reconnect() } }, Modifier.fillMaxWidth())
                    }
                    Text("Control PIN", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your 4-digit MG control PIN, needed for lock/unlock and AC. Only a hash is stored, encrypted on this phone. Each command also asks for your fingerprint or screen lock.",
                        style = MaterialTheme.typography.bodySmall, color = VoltaColors.Muted,
                    )
                    if (pinSet) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PIN saved", style = MaterialTheme.typography.bodyMedium, color = VoltaColors.Green, modifier = Modifier.weight(1f))
                            VoltaButton("Change", { vm.ismart.clearPin(); pinSet = false }, style = VoltaButtonStyle.Secondary)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(4) }, label = { Text("4-digit PIN") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.weight(1f),
                            )
                            VoltaButton("Save", { vm.ismart.setPin(pin); pin = ""; pinSet = true }, enabled = pin.length == 4)
                        }
                    }
                    VoltaButton("Sign out", { vm.ismart.signOut(); pinSet = false }, Modifier.fillMaxWidth(), style = VoltaButtonStyle.Secondary)
                }
            }
        }
    }
}
