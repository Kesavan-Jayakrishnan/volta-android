package dev.irshadm.volta.core.data.account

import dev.irshadm.volta.core.data.security.SecretStore
import dev.irshadm.volta.core.domain.Account
import dev.irshadm.volta.core.domain.AccountRepository
import dev.irshadm.volta.core.domain.SignInError
import dev.irshadm.volta.core.domain.SignInException
import dev.irshadm.volta.core.ismart.Credentials
import dev.irshadm.volta.core.ismart.IsmartException
import dev.irshadm.volta.core.ismart.LoginFrame
import dev.irshadm.volta.core.ismart.LoginRejectedException
import dev.irshadm.volta.core.ismart.LoginThrottledException
import dev.irshadm.volta.core.ismart.MgCrypto
import dev.irshadm.volta.core.ismart.MgIsmartClient
import dev.irshadm.volta.core.ismart.NotOwnerException
import dev.irshadm.volta.core.ismart.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * The MG i-SMART login, kept in a [SecretStore]. The phone number and password are saved before the first
 * request (the client reads them from there) and wiped again if MG doesn't accept them, so a failed sign-in
 * leaves nothing behind. Signing out removes only the MG entries; the Google key stays.
 */
class IsmartAccountRepository(
    private val secrets: SecretStore,
    clientFactory: (credentials: () -> Credentials?, sessions: SessionStore) -> MgIsmartClient,
) : AccountRepository {

    private val sessions = SecretSessionStore(secrets)
    private val client = clientFactory(::savedCredentials, sessions)
    private val _account = MutableStateFlow<Account>(savedCredentials()?.let { Account.SignedIn(it.phone) } ?: Account.SignedOut)
    override val account: StateFlow<Account> = _account.asStateFlow()

    override suspend fun signIn(phone: String, password: String): Result<Unit> = runCatching {
        val normalized = try {
            MgCrypto.normalizePhone(phone)
        } catch (e: IllegalArgumentException) {
            throw SignInException(SignInError.Other(e.message ?: "Check the mobile number"))
        }
        saveCredentials(Credentials(normalized, password))
        try {
            client.signIn()
            client.vehicles()
        } catch (e: Exception) {
            saveCredentials(null)
            sessions.save(null)
            throw SignInException(signInErrorOf(e))
        }
        _account.value = Account.SignedIn(normalized)
    }

    override fun signOut() {
        client.signOut()
        saveCredentials(null)
        sessions.save(null)
        _account.value = Account.SignedOut
    }

    private fun savedCredentials(): Credentials? {
        val phone = secrets.get(PHONE) ?: return null
        val password = secrets.get(PASSWORD) ?: return null
        return Credentials(phone, password)
    }

    private fun saveCredentials(credentials: Credentials?) {
        secrets.put(PHONE, credentials?.phone)
        secrets.put(PASSWORD, credentials?.password)
    }

    private companion object {
        const val PHONE = "mg.phone"
        const val PASSWORD = "mg.password"
    }
}

/** The client's session token, kept next to the login. */
private class SecretSessionStore(private val secrets: SecretStore) : SessionStore {
    override fun load(): LoginFrame.Session? {
        val uid = secrets.get(UID) ?: return null
        val token = secrets.get(TOKEN) ?: return null
        return LoginFrame.Session(uid, token)
    }

    override fun save(session: LoginFrame.Session?) {
        secrets.put(UID, session?.uid)
        secrets.put(TOKEN, session?.token)
    }

    private companion object {
        const val UID = "mg.uid"
        const val TOKEN = "mg.token"
    }
}

/** Maps the client's exceptions to the cases the sign-in screen shows differently. */
internal fun signInErrorOf(e: Throwable): SignInError = when (e) {
    is LoginRejectedException -> SignInError.Rejected(e.message ?: "MG rejected the sign-in")
    is LoginThrottledException -> SignInError.Throttled(e.retryAtMillis)
    is NotOwnerException -> SignInError.NotOwner
    is IOException -> SignInError.Offline
    is IsmartException -> if (e.cause is IOException) SignInError.Offline else SignInError.Other(e.message ?: "MG i-SMART error")
    else -> SignInError.Other(e.message ?: e.javaClass.simpleName)
}
