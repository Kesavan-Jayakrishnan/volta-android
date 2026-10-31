package dev.irshadm.volta.core.maps

/**
 * Where the Google Maps Platform key comes from. Read on every request, so a key pasted or removed in Settings
 * takes effect on the next call without restarting the app. Volta never ships a key of its own.
 */
fun interface MapsKeyProvider {
    fun currentKey(): String?
}

/** No key has been added yet: the feature that needs Google should ask for one instead of failing quietly. */
class MissingMapsKeyException : IllegalStateException("No Google Maps key. Add one in Settings → Google Maps key.")
