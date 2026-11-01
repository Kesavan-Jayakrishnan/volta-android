package dev.irshadm.volta.core.data

import dev.irshadm.volta.core.data.maps.SecretMapsKeyRepository
import dev.irshadm.volta.core.data.security.InMemorySecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SecretMapsKeyRepositoryTest {
    private val store = InMemorySecretStore()

    @Test fun `save trims, persists and publishes`() {
        val repo = SecretMapsKeyRepository(store)
        repo.save("  KEY  ")
        assertEquals("KEY", repo.key.value)
        assertEquals("KEY", repo.currentKey())
        assertEquals("KEY", SecretMapsKeyRepository(store).key.value, "a new instance reads the stored key")
    }

    @Test fun `remove clears both`() {
        val repo = SecretMapsKeyRepository(store).apply { save("KEY") }
        repo.remove()
        assertNull(repo.key.value)
        assertNull(SecretMapsKeyRepository(store).key.value)
    }

    @Test fun `blank keys are refused`() {
        assertFailsWith<IllegalArgumentException> { SecretMapsKeyRepository(store).save("   ") }
    }
}
