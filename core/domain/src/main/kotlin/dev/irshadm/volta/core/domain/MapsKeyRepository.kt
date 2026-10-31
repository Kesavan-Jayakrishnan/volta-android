package dev.irshadm.volta.core.domain

import dev.irshadm.volta.core.maps.MapsKeyProvider
import kotlinx.coroutines.flow.StateFlow

/** The user's own Google Maps Platform key, stored encrypted on the phone. */
interface MapsKeyRepository : MapsKeyProvider {
    /** The saved key, or null. Observed by Settings and by screens that need Google. */
    val key: StateFlow<String?>

    fun save(key: String)

    fun remove()

    override fun currentKey(): String? = key.value
}
