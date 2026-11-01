package dev.irshadm.volta.data.energy

import dev.irshadm.volta.data.db.ChargeSessionEntity
import dev.irshadm.volta.data.energy.ChargeTracker.Companion.isStale
import dev.irshadm.volta.data.energy.ChargeTracker.Companion.startLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeTrackerTest {
    private val yesterday = 1_000_000_000_000L
    private val open = ChargeSessionEntity(startedAt = yesterday, lat = null, lng = null, startSoc = 24.0, startKwh = 8.9, endSoc = 80.0, category = "HOME")

    @Test fun `a lower battery than the open session reached means the car was driven since`() {
        assertTrue(isStale(open, socNow = 31.0, carStartedAt = null))
    }

    @Test fun `the car starting this charge later than the open session means a new charge`() {
        // The unplug was missed and the old session followed the drive down and this charge back up to 56 %.
        val followed = open.copy(endSoc = 55.0)
        assertTrue(isStale(followed, socNow = 56.0, carStartedAt = yesterday + 14 * 3_600_000L))
    }

    @Test fun `the same charge carrying on is not stale`() {
        val going = open.copy(endSoc = 55.0)
        assertFalse(isStale(going, socNow = 56.0, carStartedAt = yesterday + 60_000L))
        assertFalse(isStale(going, socNow = 54.5, carStartedAt = null))          // a reading wobble, not a drive
    }

    @Test fun `a charge starts from the last reading before it unless that was before a drive`() {
        assertEquals(31.0, startLevel(lastBefore = 31.0, socNow = 56.0), 0.0)
        assertEquals(31.0, startLevel(lastBefore = 80.0, socNow = 31.0), 0.0)
        assertEquals(40.0, startLevel(lastBefore = null, socNow = 40.0), 0.0)
    }
}
