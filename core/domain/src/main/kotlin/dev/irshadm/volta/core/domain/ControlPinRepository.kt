package dev.irshadm.volta.core.domain

import kotlinx.coroutines.flow.StateFlow

/** What happened when a control PIN was checked with MG. */
sealed interface PinCheck {
    data object Saved : PinCheck

    /** MG said the PIN is wrong. Wrong tries count against MG's limit, so it's never retried automatically. */
    data object Wrong : PinCheck

    /** MG's servers couldn't be reached. */
    data object Offline : PinCheck
    data class Other(val message: String) : PinCheck
}

/** The 4-digit MG control PIN that remote commands need. Only a hash of it is kept, encrypted on the phone. */
interface ControlPinRepository {
    val hasPin: StateFlow<Boolean>

    /** True once this sign-in has a PIN, or the PIN step was skipped. Forgotten on sign-out with the rest of the login. */
    val stepDone: StateFlow<Boolean>

    /** Checks [pin] with MG without sending anything to the car, and saves it only if MG accepts it. */
    suspend fun checkAndSave(pin: String): PinCheck

    /** Leaves the first-run PIN step without a PIN. Commands ask for it when they need it. */
    fun skip()

    fun clear()
}
