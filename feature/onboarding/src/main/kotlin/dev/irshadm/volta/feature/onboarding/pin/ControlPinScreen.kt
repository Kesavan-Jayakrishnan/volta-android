package dev.irshadm.volta.feature.onboarding.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.irshadm.volta.core.designsystem.NoteTone
import dev.irshadm.volta.core.designsystem.VoltaButton
import dev.irshadm.volta.core.designsystem.VoltaColors
import dev.irshadm.volta.core.designsystem.VoltaNote
import dev.irshadm.volta.core.designsystem.VoltaPinField
import dev.irshadm.volta.core.designsystem.VoltaTextButton
import dev.irshadm.volta.core.designsystem.VoltaTheme
import dev.irshadm.volta.core.domain.PinCheck

/** First run, after sign-in: the control PIN, which remote commands need. Skippable; commands ask again when needed. */
@Composable
fun ControlPinRoute(vm: ControlPinViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ControlPinScreen(state, vm::onPinChange, { vm.check() }, vm::skip)
}

@Composable
fun ControlPinScreen(state: ControlPinUiState, onPinChange: (String) -> Unit, onCheck: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(VoltaColors.Bg).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Your MG control PIN", style = MaterialTheme.typography.headlineSmall, color = VoltaColors.Text)
        Text(
            "The 4-digit PIN from the MG app. Volta needs it to lock, unlock, open windows and start the AC.",
            style = MaterialTheme.typography.bodyMedium,
            color = VoltaColors.TextSoft,
        )
        Spacer(Modifier.height(8.dp))
        ControlPinForm(state, onPinChange, onCheck, "Check with MG")
        VoltaTextButton("Skip for now", onSkip, Modifier.align(Alignment.CenterHorizontally))
        Text(
            "Battery, range and trips work without it.",
            style = MaterialTheme.typography.bodySmall,
            color = VoltaColors.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Shown when a command needs the PIN and none is saved, instead of sending people to Settings. Once MG accepts the
 * PIN, [onSaved] carries on with the command they tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPinSheet(title: String, actionLabel: String, onSaved: () -> Unit, onDismiss: () -> Unit, vm: ControlPinViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = VoltaColors.Surface, contentColor = VoltaColors.Text) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(
                "MG asks for your 4-digit control PIN before any remote command. You only enter it once.",
                style = MaterialTheme.typography.bodyMedium,
                color = VoltaColors.TextSoft,
            )
            ControlPinForm(state, vm::onPinChange, { vm.check(onSaved) }, actionLabel)
            VoltaTextButton("Not now", onDismiss, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The PIN boxes, what went wrong with the last check, and the check button. Shared by every place that asks for the PIN. */
@Composable
fun ControlPinForm(
    state: ControlPinUiState,
    onPinChange: (String) -> Unit,
    onCheck: () -> Unit,
    checkLabel: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        VoltaPinField(
            state.pin,
            onPinChange,
            isError = state.problem == PinCheck.Wrong && state.pin.isEmpty(),
            enabled = !state.checking,
            autoFocus = autoFocus,
            onDone = onCheck,
        )
        Text(
            "Only a hash is kept, encrypted on this phone. Each command also asks for your fingerprint.",
            style = MaterialTheme.typography.bodySmall,
            color = VoltaColors.Muted,
            textAlign = TextAlign.Center,
        )
        state.problem?.let { problem ->
            val (title, body) = problemText(problem)
            VoltaNote(NoteTone.Error, body = body, title = title)
        }
        VoltaButton(
            text = when {
                state.checking -> "Checking with MG…"
                state.problem != null -> "Try again"
                else -> checkLabel
            },
            onClick = onCheck,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canCheck || state.checking,
            busy = state.checking,
        )
    }
}

private fun problemText(problem: PinCheck): Pair<String, String> = when (problem) {
    PinCheck.Wrong -> "MG says that PIN is wrong" to "Check it in the MG app before trying again. Wrong PINs count against MG's limit."
    PinCheck.Offline -> "Can't reach MG" to "Check your connection and try again."
    is PinCheck.Other -> "MG couldn't check the PIN" to problem.message
    PinCheck.Saved -> "" to ""
}

@Preview
@Composable
private fun ControlPinPreview() = VoltaTheme {
    ControlPinScreen(ControlPinUiState(pin = "12"), {}, {}, {})
}

@Preview
@Composable
private fun ControlPinWrongPreview() = VoltaTheme {
    ControlPinScreen(ControlPinUiState(problem = PinCheck.Wrong), {}, {}, {})
}
