package dev.irshadm.volta.data.ismart

import dev.irshadm.volta.core.domain.ControlPinRepository
import dev.irshadm.volta.core.domain.PinCheck
import dev.irshadm.volta.core.ismart.IsmartException
import dev.irshadm.volta.core.ismart.SignedOutException
import dev.irshadm.volta.core.ismart.WrongPinException
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The control PIN as the PIN screens see it, on top of [IsmartRepository], which keeps the hash and talks to MG. */
@Singleton
class IsmartControlPin @Inject constructor(private val ismart: IsmartRepository) : ControlPinRepository {
    override val hasPin: StateFlow<Boolean> = ismart.pinSaved
    override val stepDone: StateFlow<Boolean> = ismart.pinStepDone

    override suspend fun checkAndSave(pin: String): PinCheck {
        if (!Regex("\\d{4}").matches(pin)) return PinCheck.Other("The control PIN is 4 digits")
        return try {
            ismart.checkPin(pin)
            ismart.setPin(pin)
            PinCheck.Saved
        } catch (e: IsmartException) {
            pinCheckOf(e)
        } catch (e: IOException) {
            pinCheckOf(e)
        }
    }

    override fun skip() = ismart.skipPinStep()

    override fun clear() = ismart.clearPin()
}

/** Maps the client's exceptions to the cases the PIN screens show differently. */
internal fun pinCheckOf(e: Throwable): PinCheck = when (e) {
    is WrongPinException -> PinCheck.Wrong
    is IOException -> PinCheck.Offline
    is SignedOutException -> PinCheck.Other("Volta is signed out of MG. Reconnect, then try again.")
    is IsmartException -> if (e.cause is IOException) PinCheck.Offline else PinCheck.Other(e.message ?: "MG i-SMART error")
    else -> PinCheck.Other(e.message ?: e.javaClass.simpleName)
}
