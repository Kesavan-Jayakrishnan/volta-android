package dev.irshadm.volta.feature.onboarding.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.domain.ControlPinRepository
import dev.irshadm.volta.core.domain.PinCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val PIN_LENGTH = 4

data class ControlPinUiState(
    val pin: String = "",
    val checking: Boolean = false,
    /** Why the last check failed; never [PinCheck.Saved]. */
    val problem: PinCheck? = null,
) {
    val canCheck: Boolean get() = pin.length == PIN_LENGTH && !checking
}

/** Checks a control PIN with MG before saving it. Serves the first-run step, the command sheet and Settings. */
@HiltViewModel
class ControlPinViewModel @Inject constructor(private val pins: ControlPinRepository) : ViewModel() {
    private val _state = MutableStateFlow(ControlPinUiState())
    val state: StateFlow<ControlPinUiState> = _state.asStateFlow()

    fun onPinChange(value: String) = _state.update { it.copy(pin = value.filter(Char::isDigit).take(PIN_LENGTH), problem = null) }

    /** One check per tap: a wrong PIN counts against MG's limit, so nothing here retries. [onSaved] runs once MG accepts it. */
    fun check(onSaved: () -> Unit = {}) {
        if (!_state.value.canCheck) return
        val pin = _state.value.pin
        _state.update { it.copy(checking = true, problem = null) }
        viewModelScope.launch {
            when (val result = pins.checkAndSave(pin)) {
                PinCheck.Saved -> {
                    _state.value = ControlPinUiState()
                    onSaved()
                }

                PinCheck.Wrong -> _state.update { it.copy(pin = "", checking = false, problem = result) }

                else -> _state.update { it.copy(checking = false, problem = result) }
            }
        }
    }

    fun skip() = pins.skip()
}
