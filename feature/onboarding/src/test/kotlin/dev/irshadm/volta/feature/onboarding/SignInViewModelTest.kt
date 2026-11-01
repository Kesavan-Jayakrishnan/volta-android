package dev.irshadm.volta.feature.onboarding

import dev.irshadm.volta.core.domain.Account
import dev.irshadm.volta.core.domain.SignInError
import dev.irshadm.volta.core.domain.SignInException
import dev.irshadm.volta.core.testing.FakeAccountRepository
import dev.irshadm.volta.core.testing.MainDispatcherRule
import dev.irshadm.volta.feature.onboarding.signin.SignInViewModel
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val accounts = FakeAccountRepository()
    private val vm = SignInViewModel(accounts)

    private fun fill(phone: String = "98000 00012", password: String = "secret12") {
        vm.onPhoneChange(phone); vm.onPasswordChange(password)
    }

    @Test fun `pasted number drops the country code`() {
        vm.onPhoneChange("+91 98000-00012")
        assertEquals("9800000012", vm.state.value.phone)
    }

    @Test fun `incomplete form never reaches MG`() {
        fill(password = "123")
        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        assertTrue(accounts.attempts.isEmpty())
    }

    @Test fun `success signs in and forgets the typed password`() {
        fill()
        vm.submit()
        assertEquals(listOf("9800000012" to "secret12"), accounts.attempts)
        assertEquals(Account.SignedIn("9800000012"), accounts.account.value)
        assertEquals("", vm.state.value.password)
        assertNull(vm.state.value.error)
    }

    @Test fun `rejection shows MG's message, clears the password, and does not retry`() {
        accounts.nextResult = Result.failure(SignInException(SignInError.Rejected("Incorrect password")))
        fill()
        vm.submit()
        assertEquals(SignInError.Rejected("Incorrect password"), vm.state.value.error)
        assertEquals("", vm.state.value.password)
        assertEquals(1, accounts.attempts.size)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `offline keeps the password for a retry`() {
        accounts.nextResult = Result.failure(SignInException(SignInError.Offline))
        fill()
        vm.submit()
        assertEquals(SignInError.Offline, vm.state.value.error)
        assertEquals("secret12", vm.state.value.password)
    }
}
