package dev.irshadm.volta.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** Where the app opens: the first-run step still to do, or the app itself. */
enum class StartDestination { SignIn, MapsKey, Home }

/** Decides [StartDestination] from the account and the first-run progress, so the rule lives in one tested place. */
class ObserveStartDestinationUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val setup: SetupRepository,
) {
    operator fun invoke(): Flow<StartDestination> =
        combine(accounts.account, setup.mapsKeyStepDone) { account, keyStepDone ->
            when {
                account is Account.SignedOut -> StartDestination.SignIn
                !keyStepDone -> StartDestination.MapsKey
                else -> StartDestination.Home
            }
        }
}
