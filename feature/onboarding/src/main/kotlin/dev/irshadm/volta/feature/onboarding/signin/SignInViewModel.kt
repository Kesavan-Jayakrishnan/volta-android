package dev.irshadm.volta.feature.onboarding.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.domain.AccountRepository
import dev.irshadm.volta.core.domain.SignInError
import dev.irshadm.volta.core.domain.SignInException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val phone: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val busy: Boolean = false,
    val error: SignInError? = null,
) {
    /** MG wants a 10-digit number; its passwords are at least 6 characters. Checked here to save a counted attempt. */
    val canSubmit: Boolean get() = !busy && phone.length == 10 && password.length >= 6
}

@HiltViewModel
class SignInViewModel @Inject constructor(private val accounts: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    /** Digits only. A pasted "+91 98000 00012" keeps the ten digits after the country code. */
    fun onPhoneChange(value: String) = _state.update {
        val digits = value.filter(Char::isDigit)
        it.copy(phone = (if (digits.length > 10 && digits.startsWith("91")) digits.drop(2) else digits).take(10))
    }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }

    fun onTogglePassword() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    /** One attempt per tap; MG counts every wrong password, so nothing here retries. */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = accounts.signIn(current.phone, current.password)
            _state.update { state ->
                result.fold(
                    onSuccess = { state.copy(busy = false, password = "") },
                    onFailure = { e ->
                        val error = (e as? SignInException)?.error ?: SignInError.Other(e.message ?: "Sign-in failed")
                        // A rejected password is cleared so the next attempt is a deliberate retype.
                        state.copy(busy = false, error = error, password = if (error is SignInError.Rejected) "" else state.password)
                    },
                )
            }
        }
    }
}
