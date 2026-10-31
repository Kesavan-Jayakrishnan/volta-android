package dev.irshadm.volta.core.domain

import dev.irshadm.volta.core.testing.FakeAccountRepository
import dev.irshadm.volta.core.testing.FakeSetupRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserveStartDestinationUseCaseTest {

    private suspend fun start(account: Account, keyStepDone: Boolean) =
        ObserveStartDestinationUseCase(FakeAccountRepository(account), FakeSetupRepository(keyStepDone))().first()

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
}
