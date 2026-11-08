package dev.irshadm.volta.core.domain

import dev.irshadm.volta.core.testing.FakeAccountRepository
import dev.irshadm.volta.core.testing.FakeControlPinRepository
import dev.irshadm.volta.core.testing.FakeSetupRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveStartDestinationUseCaseTest {

    private suspend fun start(account: Account, keyStepDone: Boolean, pinStepDone: Boolean = true) = ObserveStartDestinationUseCase(FakeAccountRepository(account), FakeSetupRepository(keyStepDone), FakeControlPinRepository(stepDone = pinStepDone))().first()

    @Test fun `signed out always starts at sign-in`() = runTest {
        assertEquals(StartDestination.SignIn, start(Account.SignedOut, keyStepDone = false))
        assertEquals(StartDestination.SignIn, start(Account.SignedOut, keyStepDone = true))
    }

    @Test fun `signed in without the key step goes to the key`() = runTest {
        assertEquals(StartDestination.MapsKey, start(Account.SignedIn("9800000012"), keyStepDone = false))
    }

    @Test fun `signed in with the key step done goes home`() = runTest {
        assertEquals(StartDestination.Home, start(Account.SignedIn("9800000012"), keyStepDone = true))
    }

    @Test fun `signed in without the PIN step asks for the PIN before the key`() = runTest {
        assertEquals(StartDestination.ControlPin, start(Account.SignedIn("9800000012"), keyStepDone = false, pinStepDone = false))
        assertEquals(StartDestination.ControlPin, start(Account.SignedIn("9800000012"), keyStepDone = true, pinStepDone = false))
    }

    @Test fun `signed out starts at sign-in even without the PIN step`() = runTest {
        assertEquals(StartDestination.SignIn, start(Account.SignedOut, keyStepDone = false, pinStepDone = false))
    }
}
