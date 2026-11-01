package dev.irshadm.volta.core.ismart

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionInvalidTest {
    @Test fun `messages that mean the session is gone`() {
        assertTrue(sessionInvalid("Login is invalid, please login again."))
        assertTrue(sessionInvalid("Token expired"))
    }

    @Test fun `other gateway errors are not mistaken for it`() {
        assertFalse(sessionInvalid("Vehicle is offline"))
        assertFalse(sessionInvalid(null))
    }
}
