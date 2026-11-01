package dev.irshadm.volta.data.ismart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsDrivingTest {
    @Test fun `switched off is parked`() = assertFalse(isDriving(0, 0, false, 0.0, true))
    /** What the car actually sent on 31 Oct 2026: AC on from the app, parked and locked, brake reported off. */
    @Test fun `remote AC while parked with the brake reported off`() = assertFalse(isDriving(2, 1, true, 0.0, false))
    @Test fun `remote AC and no brake reading`() = assertFalse(isDriving(2, 0, true, null, null))
    @Test fun `powered, standing still, brake on`() = assertFalse(isDriving(2, 1, false, 0.0, true))
    @Test fun `moving is driving`() = assertTrue(isDriving(2, 1, false, 42.0, false))
    @Test fun `moving with the remote AC flag still set is driving`() = assertTrue(isDriving(2, 1, true, 35.0, false))
    @Test fun `stopped at a light, no remote AC, brake off`() = assertTrue(isDriving(2, 1, false, 0.0, false))
}

class IgnitionChangedAtTest {
    private val nine56 = 1_000_000L
    private val evening = 9_000_000L

    @Test fun `still parked, odometer still - keeps the time`() =
        org.junit.Assert.assertEquals(nine56, ignitionChangedAt(false, nine56, 1200.0, false, 1200.0, evening, nine56))
    @Test fun `switched on - this read`() =
        org.junit.Assert.assertEquals(evening, ignitionChangedAt(false, nine56, 1200.0, true, 1200.0, evening, null))
    /** Driven all day between reads, both reads parked. */
    @Test fun `drove between reads - parked at the last GPS fix`() =
        org.junit.Assert.assertEquals(evening - 600_000L, ignitionChangedAt(false, nine56, 1200.0, false, 1265.4, evening, evening - 600_000L))
    @Test fun `drove between reads, stale GPS - this read`() =
        org.junit.Assert.assertEquals(evening, ignitionChangedAt(false, nine56, 1200.0, false, 1265.4, evening, nine56))
}
