package dev.irshadm.volta.core.maps

import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleErrorParserTest {

    private fun body(status: String, message: String, reason: String?) = """
        {"error": {"code": 403, "message": "$message", "status": "$status",
          "details": [${reason?.let { """{"@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "$it"}""" } ?: ""}]}}
    """.trimIndent()

    @Test fun `reason wins over message text`() {
        val e = GoogleErrorParser.parse(403, body("PERMISSION_DENIED", "Routes API has not been used in project 1", "SERVICE_DISABLED"))
        assertEquals(KeyProblem.ApiNotEnabled, e.problem)
        assertEquals("Routes API has not been used in project 1", e.googleMessage)
    }

    @Test fun `each documented reason maps to its problem`() {
        mapOf(
            "API_KEY_INVALID" to KeyProblem.InvalidKey,
            "API_KEY_SERVICE_BLOCKED" to KeyProblem.ApiNotAllowedForKey,
            "API_KEY_ANDROID_APP_BLOCKED" to KeyProblem.AppNotAllowed,
            "BILLING_DISABLED" to KeyProblem.BillingDisabled,
            "RATE_LIMIT_EXCEEDED" to KeyProblem.QuotaExceeded,
        ).forEach { (reason, problem) ->
            assertEquals(problem, GoogleErrorParser.parse(403, body("PERMISSION_DENIED", "x", reason)).problem, reason)
        }
    }

    @Test fun `falls back to the message when there is no reason`() {
        val e = GoogleErrorParser.parse(400, body("INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.", null))
        assertEquals(KeyProblem.InvalidKey, e.problem)
    }

    @Test fun `android app restriction message`() {
        val msg = "Requests from this Android client application dev.example are blocked."
        assertEquals(KeyProblem.AppNotAllowed, GoogleErrorParser.parse(403, body("PERMISSION_DENIED", msg, null)).problem)
    }

    @Test fun `legacy status body`() {
        val e = GoogleErrorParser.parse(200, """{"status": "REQUEST_DENIED", "error_message": "The provided API key is invalid."}""")
        assertEquals(KeyProblem.InvalidKey, e.problem)
    }

    @Test fun `unreadable body keeps the HTTP code`() {
        val e = GoogleErrorParser.parse(502, "")
        assertEquals(KeyProblem.Other, e.problem)
        assertEquals("HTTP 502", e.googleMessage)
    }
}
