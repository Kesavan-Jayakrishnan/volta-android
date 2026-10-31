package dev.irshadm.volta.core.maps

/**
 * The package name and signing-certificate SHA-1 that Google checks when a key is restricted to Android apps.
 * REST calls carry them in the `X-Android-Package` and `X-Android-Cert` headers.
 */
data class AppIdentity(val packageName: String, val sha1Hex: String) {
    /** `AB:CD:…`, the form Google Cloud Console shows. */
    val sha1Colons: String get() = sha1Hex.chunked(2).joinToString(":")
}
