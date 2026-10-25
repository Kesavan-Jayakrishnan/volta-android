package dev.irshadm.volta.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TariffTest {
    private val monthly = Tariff(billingMonths = 1, dutyPercent = 0.0)

    @Test
    fun telescopicSlabsAddUp() {
        assertEquals(50 * 3.35, monthly.billForCycle(50.0), 1e-9)
        assertEquals(50 * 3.35 + 30 * 4.25, monthly.billForCycle(80.0), 1e-9)
        assertEquals(50 * 3.35 + 50 * 4.25 + 50 * 5.35 + 50 * 7.20 + 50 * 8.50, monthly.billForCycle(250.0), 1e-9)
    }

    @Test
    fun aboveLimitAllUnitsAtBandRate() {
        assertEquals(251 * 6.75, monthly.billForCycle(251.0), 1e-9)
        assertEquals(341 * 7.60, monthly.billForCycle(341.0), 1e-9)
        assertEquals(600 * 9.20, monthly.billForCycle(600.0), 1e-9)
    }

    @Test
    fun biMonthlyDoublesSlabsAndDutyApplies() {
        val bi = Tariff(billingMonths = 2, dutyPercent = 10.0)
        assertEquals(2 * monthly.billForCycle(180.0) * 1.10, bi.billForCycle(360.0), 1e-9)
    }

    @Test
    fun evCostIsTheDifferenceAndJumpsPastTheLimit() {
        val small = monthly.evCost(150.0, 50.0)
        assertEquals(50 * 7.20, small, 1e-9)
        val jump = monthly.evCost(200.0, 100.0)
        assertTrue(jump > 100 * 8.50, "crossing 250 re-prices every unit, was $jump")
        assertEquals(300 * 6.75 - monthly.billForCycle(200.0), jump, 1e-9)
    }

    @Test
    fun headroom() {
        assertEquals(70.0, Tariff().headroomPerMonth(360.0), 1e-9)
        assertTrue(Tariff().headroomPerMonth(600.0) < 0)
    }
}
