package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReplannerTest {
    private val car = VehicleProfile()
    private val settings = TripSettings()
    private fun road(km: Double) = (0..(km * 5).toInt()).map { ProfilePoint(it * 200.0, 0.0, 90.0) }
    private fun charger(id: String, km: Double) = Charger(id, id, LatLng(0.0, km / 111.0), 50.0, alongRouteM = km * 1000)

    @Test
    fun replanUsesOnlyChargersAheadAndKeepsRouteDistances() {
        val chargers = listOf(charger("behind", 30.0), charger("mid", 140.0), charger("late", 200.0))
        val opts = Replanner.replanFrom(car, settings, ChargerPreferences(), road(300.0), chargers, alongM = 100_000.0, soc = 45.0, remainingDriveMinutes = 140.0)
        val ok = assertIs<PlanOutcome.Ok>(opts.first().plan.outcome)
        assertTrue(ok.stops.none { it.charger.id == "behind" })
        assertTrue(ok.stops.all { it.charger.alongRouteM >= 100_000.0 })
        assertTrue(opts.first().plan.socTrace.first().distanceM >= 99_000.0)
    }

    @Test
    fun expectedSocFollowsTrace() {
        val plan = TripPlanner(car, settings).plan(road(60.0), 80.0, emptyList(), 40.0)
        val mid = Replanner.expectedSoc(plan, 30_000.0)
        assertEquals(80.0 - 30.0 / 6 / 37.1 * 100, mid, 0.3)
    }
}
