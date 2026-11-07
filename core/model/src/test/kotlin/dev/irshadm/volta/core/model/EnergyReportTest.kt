package dev.irshadm.volta.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnergyReportTest {
    private val s = EnergySettings(householdUnitsPerCycle = 360.0)
    private val t = Tariff()

    @Test
    fun splitsByCategoryAndConvertsToGridUnits() {
        val r = EnergyReport.compute(
            listOf(SessionEnergy(ChargeCategory.HOME, 88.0), SessionEnergy(ChargeCategory.PUBLIC_DC, 20.0), SessionEnergy(ChargeCategory.PUBLIC_AC, 8.8)),
            s,
            t,
            periodMonths = 1.0,
            km = 700.0,
        )
        assertEquals(116.8, r.totalKwh, 1e-9)
        assertEquals(100.0, r.homeUnits, 1e-9) // 88 / 0.88
        assertEquals(20 / 0.93 * 22, r.publicCost[ChargeCategory.PUBLIC_DC]!!, 1e-9)
        assertEquals(10.0 * 14, r.publicCost[ChargeCategory.PUBLIC_AC]!!, 1e-9)
        assertTrue(r.costPerKm!! > 0)
    }

    @Test
    fun homeCostIsShareOfCycleBillDifference() {
        // 100 home units in one month = 200 per 2-month cycle; 360 + 200 = 560 per cycle = 280/month → over 250.
        val r = EnergyReport.compute(listOf(SessionEnergy(ChargeCategory.HOME, 88.0)), s, t, 1.0, null)
        assertTrue(r.crossesLimit)
        assertEquals((t.billForCycle(560.0) - t.billForCycle(360.0)) / 2, r.homeCost, 1e-6)
        assertTrue(r.costPerHomeUnit > 8.5) // the jump makes each EV unit expensive
    }

    @Test
    fun userEnteredActualsWin() {
        val r = EnergyReport.compute(listOf(SessionEnergy(ChargeCategory.HOME, 10.0, gridUnits = 12.0), SessionEnergy(ChargeCategory.PUBLIC_DC, 15.0, costRs = 300.0)), s, t, 1.0, null)
        assertEquals(12.0, r.homeUnits, 1e-9)
        assertEquals(300.0, r.publicCost[ChargeCategory.PUBLIC_DC]!!, 1e-9)
    }
}
