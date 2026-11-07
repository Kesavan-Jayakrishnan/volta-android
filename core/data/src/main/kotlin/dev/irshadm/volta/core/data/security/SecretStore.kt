package dev.irshadm.volta.core.data.security

/** A small key-value store for secrets. The app uses [KeystoreSecretStore]; tests use [InMemorySecretStore]. */
interface SecretStore {
    fun get(name: String): String?

    /** Stores [value], or removes the entry when it is null. */
    fun put(name: String, value: String?)

    fun clear()
}

class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(name: String) = values[name]
    override fun put(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
    }
    override fun clear() = values.clear()
}
