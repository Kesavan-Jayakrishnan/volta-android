package dev.irshadm.volta.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.irshadm.volta.core.domain.Account
import dev.irshadm.volta.core.domain.AccountRepository
import dev.irshadm.volta.core.domain.MapsKeyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(val phone: String? = null, val hasMapsKey: Boolean = false)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val accounts: AccountRepository,
    keys: MapsKeyRepository,
) : ViewModel() {
    val state: StateFlow<HomeUiState> = combine(accounts.account, keys.key) { account, key ->
        HomeUiState(phone = (account as? Account.SignedIn)?.phone, hasMapsKey = key != null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun signOut() = accounts.signOut()
}
