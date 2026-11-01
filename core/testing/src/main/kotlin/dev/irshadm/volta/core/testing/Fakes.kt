package dev.irshadm.volta.core.testing

import dev.irshadm.volta.core.domain.Account
import dev.irshadm.volta.core.domain.AccountRepository
import dev.irshadm.volta.core.domain.MapsKeyRepository
import dev.irshadm.volta.core.domain.SetupRepository
import dev.irshadm.volta.core.maps.KeyCheckReport
import dev.irshadm.volta.core.maps.MapsKeyChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Signs in when [nextResult] succeeds; records every attempt in [attempts]. */
class FakeAccountRepository(initial: Account = Account.SignedOut) : AccountRepository {
    override val account = MutableStateFlow(initial)
    override val rejection = MutableStateFlow<String?>(null)
    var nextResult: Result<Unit> = Result.success(Unit)
    val attempts = mutableListOf<Pair<String, String>>()

    override suspend fun signIn(phone: String, password: String): Result<Unit> {
        attempts += phone to password
        return nextResult.onSuccess { account.value = Account.SignedIn(phone) }
    }

    override fun signOut() { account.value = Account.SignedOut }
}

class FakeMapsKeyRepository(initial: String? = null) : MapsKeyRepository {
    override val key: StateFlow<String?> get() = _key
    private val _key = MutableStateFlow(initial)
    override fun save(key: String) { _key.value = key }
    override fun remove() { _key.value = null }
}

class FakeSetupRepository(done: Boolean = false) : SetupRepository {
    override val mapsKeyStepDone = MutableStateFlow(done)
    override suspend fun setMapsKeyStepDone(done: Boolean) { mapsKeyStepDone.value = done }
}

/** Answers every check with [report]; records the keys it was asked about. */
class FakeMapsKeyChecker(var report: KeyCheckReport) : MapsKeyChecker {
    val checked = mutableListOf<String>()
    override suspend fun check(key: String): KeyCheckReport { checked += key; return report }
}
