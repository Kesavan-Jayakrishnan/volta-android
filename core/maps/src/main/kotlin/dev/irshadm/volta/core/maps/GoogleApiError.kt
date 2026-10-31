package dev.irshadm.volta.core.maps

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What went wrong with a key, in terms a person can act on. */
enum class KeyProblem {
    /** The key doesn't exist, was deleted, or was mistyped. */
    InvalidKey,
    /** The API isn't enabled in the key's Google Cloud project. */
    ApiNotEnabled,
    /** The key's "API restrictions" leave this API out. */
    ApiNotAllowedForKey,
    /** The key is restricted to Android apps, and this app's package or SHA-1 isn't on the list. */
    AppNotAllowed,
    /** The project has no billing account, which Google Maps Platform requires. */
    BillingDisabled,
    /** Daily or per-minute quota used up. */
    QuotaExceeded,
    /** No answer from Google (offline, DNS, timeout). */
    Network,
    /** Anything else; [GoogleApiException.message] carries Google's own words. */
    Other,
}

/** A Google API call failed. [googleMessage] is Google's text, kept for the details line. */
class GoogleApiException(
    val problem: KeyProblem,
    val httpCode: Int,
    val googleMessage: String,
    cause: Throwable? = null,
) : Exception(googleMessage, cause)

/**
 * Reads Google's error body (`{"error": {"code", "message", "status", "details": [{"reason": …}]}}`) into a
 * [KeyProblem]. The `reason` in `google.rpc.ErrorInfo` is the reliable signal; the message text is the fallback.
 */
object GoogleErrorParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Envelope(val error: Body? = null)

    @Serializable
    private data class Body(
        val code: Int = 0,
        val message: String = "",
        val status: String = "",
        val details: List<JsonObject> = emptyList(),
    )

    @Serializable
    private data class LegacyEnvelope(@SerialName("error_message") val errorMessage: String? = null, val status: String? = null)

    fun parse(httpCode: Int, body: String): GoogleApiException {
        val parsed = runCatching { json.decodeFromString<Envelope>(body).error }.getOrNull()
        if (parsed == null) {
            // Older APIs (Elevation) answer 200 with {"status": "REQUEST_DENIED", "error_message": …}.
            val legacy = runCatching { json.decodeFromString<LegacyEnvelope>(body) }.getOrNull()
            val message = legacy?.errorMessage ?: body.take(200).ifBlank { "HTTP $httpCode" }
            return GoogleApiException(classify(httpCode, reason = null, message), httpCode, message)
        }
        val reason = parsed.details.firstNotNullOfOrNull { it["reason"]?.jsonPrimitive?.contentOrNull }
        return GoogleApiException(classify(httpCode, reason, parsed.message), httpCode, parsed.message.ifBlank { parsed.status })
    }

    internal fun classify(httpCode: Int, reason: String?, message: String): KeyProblem = when (reason) {
        "API_KEY_INVALID" -> KeyProblem.InvalidKey
        "SERVICE_DISABLED", "ACCESS_NOT_CONFIGURED" -> KeyProblem.ApiNotEnabled
        "API_KEY_SERVICE_BLOCKED" -> KeyProblem.ApiNotAllowedForKey
        "API_KEY_ANDROID_APP_BLOCKED" -> KeyProblem.AppNotAllowed
        "BILLING_DISABLED" -> KeyProblem.BillingDisabled
        "RATE_LIMIT_EXCEEDED", "RESOURCE_EXHAUSTED" -> KeyProblem.QuotaExceeded
        else -> classifyByText(httpCode, message.lowercase())
    }

    private fun classifyByText(httpCode: Int, m: String): KeyProblem = when {
        "api key not valid" in m || "invalid api key" in m || "provided api key is invalid" in m -> KeyProblem.InvalidKey
        "has not been used in project" in m || "is disabled" in m || "not activated" in m -> KeyProblem.ApiNotEnabled
        "android client application" in m -> KeyProblem.AppNotAllowed
        "not authorized to use this service" in m || "are blocked" in m -> KeyProblem.ApiNotAllowedForKey
        "billing" in m -> KeyProblem.BillingDisabled
        httpCode == 429 || "quota" in m -> KeyProblem.QuotaExceeded
        else -> KeyProblem.Other
    }
}
