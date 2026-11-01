package dev.irshadm.volta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.domain.ObserveStartDestinationUseCase
import dev.irshadm.volta.core.domain.StartDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Which top-level screen to show. Null until the saved state has loaded, so nothing flashes on launch. */
@HiltViewModel
class AppViewModel @Inject constructor(observeStart: ObserveStartDestinationUseCase) : ViewModel() {
    val destination: StateFlow<StartDestination?> =
        observeStart().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)
}
