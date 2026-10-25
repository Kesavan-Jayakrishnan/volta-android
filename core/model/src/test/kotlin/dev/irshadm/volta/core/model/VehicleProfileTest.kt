package dev.irshadm.volta.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VehicleProfileTest {
    private val profile = VehicleProfile()

    @Test
    fun defaultsAreValid() {
        assertTrue(profile.validate().isEmpty())
        assertTrue(TripSettings().validate().isEmpty())
    }

    @Test
    fun energyMatchesM0Measurement() {
        // Measured: 21.9 kWh at 59 %
        assertEquals(21.9, profile.energyAt(59.0), 0.05)
    }

    @Test
    fun rangeUsesEfficiencyAndReserve() {
        assertEquals(37.1 * 6.0, profile.highwayRangeKm(100.0), 1e-9)
        assertEquals(37.1 * 0.5 * 7.0, profile.rangeKm(60.0, 7.0, reservePercent = 10.0), 1e-9)
        assertEquals(0.0, profile.rangeKm(5.0, 7.0, reservePercent = 10.0))
    }

    @Test
    fun socIsClamped() {
        assertEquals(37.1, profile.energyAt(150.0), 1e-9)
        assertEquals(0.0, profile.energyAt(-5.0))
    }

    @Test
    fun invalidProfileIsReported() {
        val bad = profile.copy(usableCapacityKwh = 2.0, dcCurve = listOf(ChargeCurvePoint(50, 40.0), ChargeCurvePoint(10, 40.0)))
        assertEquals(2, bad.validate().size)
        assertTrue(TripSettings(tripPollIntervalMinutes = 1).validate().isNotEmpty())
        assertTrue(TripSettings(maxChargeTargetPercent = 60, minArrivalAtChargerPercent = 60).validate().isNotEmpty())
    }

    @Test
    fun socReadingRejectsOutOfRangeAndComputesAge() {
        assertFailsWith<IllegalArgumentException> { SocReading(101.0, SocSource.MANUAL, 0) }
        assertEquals(5, SocReading(50.0, SocSource.MANUAL, 0).ageMinutes(5 * 60_000 + 59_000))
    }
}
