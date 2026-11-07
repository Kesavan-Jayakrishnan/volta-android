package dev.irshadm.volta.feature.onboarding.mapskey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.domain.CheckAndSaveMapsKeyUseCase
import dev.irshadm.volta.core.domain.MapsKeyRepository
import dev.irshadm.volta.core.domain.SetupRepository
import dev.irshadm.volta.core.maps.AppIdentity
import dev.irshadm.volta.core.maps.KeyCheckReport
import dev.irshadm.volta.core.maps.MapsKeyFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the key screen was opened from. The same screen serves both. */
enum class MapsKeyMode { FirstRun, Settings }

sealed interface KeyPhase {
    data object Editing : KeyPhase

    /** Doesn't look like a Google key; caught before spending a request. */
    data object BadFormat : KeyPhase
    data object Checking : KeyPhase
    data class Checked(val report: KeyCheckReport) : KeyPhase
}

data class MapsKeyUiState(
    val input: String = "",
    val phase: KeyPhase = KeyPhase.Editing,
    /** The key already saved on the phone, for Settings to show masked. */
    val savedKey: String? = null,
) {
    val canCheck: Boolean get() = input.isNotBlank() && phase != KeyPhase.Checking
    val passed: Boolean get() = (phase as? KeyPhase.Checked)?.report?.passed == true
}

@HiltViewModel
class MapsKeyViewModel @Inject constructor(
    private val checkAndSave: CheckAndSaveMapsKeyUseCase,
    private val keys: MapsKeyRepository,
    private val setup: SetupRepository,
    /** Shown in the fix for a key restricted to other apps. */
    val identity: AppIdentity,
) : ViewModel() {
    private val _state = MutableStateFlow(MapsKeyUiState(savedKey = keys.key.value))
    val state: StateFlow<MapsKeyUiState> = _state.asStateFlow()

    fun onInputChange(value: String) = _state.update { it.copy(input = value.trim(), phase = KeyPhase.Editing) }

    fun check() {
        val key = _state.value.input
        if (!_state.value.canCheck) return
        if (!MapsKeyFormat.looksValid(key)) {
            _state.update { it.copy(phase = KeyPhase.BadFormat) }
            return
        }
        _state.update { it.copy(phase = KeyPhase.Checking) }
        viewModelScope.launch {
            val report = checkAndSave(key)
            _state.update { it.copy(phase = KeyPhase.Checked(report), savedKey = keys.key.value) }
        }
    }

    /** First run: leave this step, with or without a key. Trip planning asks again later if there's none. */
    fun finishFirstRun() {
        viewModelScope.launch { setup.setMapsKeyStepDone(true) }
    }

    fun removeKey() {
        keys.remove()
        _state.update { MapsKeyUiState() }
    }
}

/** `AIzaSyB•••••••••••w4Qk`: enough to recognise the key, not enough to use it. */
internal fun maskKey(key: String): String = if (key.length <= 11) "•".repeat(key.length) else key.take(7) + "•".repeat(11) + key.takeLast(4)
