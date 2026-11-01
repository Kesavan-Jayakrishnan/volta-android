package dev.irshadm.volta.feature.onboarding

import dev.irshadm.volta.feature.onboarding.mapskey.maskKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MaskKeyTest {
    @Test fun `shows the start and the last four only`() {
        val masked = maskKey("AIzaSyB0000000000000000000000000000w4Qk")
        assertEquals("AIzaSyB•••••••••••w4Qk", masked)
        assertFalse("0000" in masked)
    }

    @Test fun `short input is fully hidden`() = assertEquals("•••", maskKey("abc"))
}
