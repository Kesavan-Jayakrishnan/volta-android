package dev.irshadm.volta.core.data.maps

import dev.irshadm.volta.core.data.security.SecretStore
import dev.irshadm.volta.core.domain.MapsKeyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Google key, encrypted at rest and cached in memory so every request can read it cheaply. */
class SecretMapsKeyRepository(private val secrets: SecretStore) : MapsKeyRepository {
    private val _key = MutableStateFlow(secrets.get(KEY))
    override val key: StateFlow<String?> = _key.asStateFlow()

    override fun save(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "Empty key" }
        secrets.put(KEY, trimmed)
        _key.value = trimmed
    }

    override fun remove() {
        secrets.put(KEY, null)
        _key.value = null
    }

    private companion object {
        const val KEY = "google.maps_key"
    }
}
