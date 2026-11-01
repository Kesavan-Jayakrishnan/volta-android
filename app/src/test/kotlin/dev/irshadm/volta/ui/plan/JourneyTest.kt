package dev.irshadm.volta.ui.plan

import dev.irshadm.volta.core.planner.Charger
import dev.irshadm.volta.core.planner.EnergyBreakdown
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.core.planner.Meal
import dev.irshadm.volta.core.planner.PlanGoal
import dev.irshadm.volta.core.planner.PlanOption
import dev.irshadm.volta.core.planner.PlanOutcome
import dev.irshadm.volta.core.planner.ProfilePoint
import dev.irshadm.volta.core.planner.SocPoint
import dev.irshadm.volta.core.planner.Stop
import dev.irshadm.volta.core.planner.TripPlan
import dev.irshadm.volta.data.maps.NamedPlace
import dev.irshadm.volta.data.maps.PlannedTrip
import dev.irshadm.volta.data.maps.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyTest {
    /** 300 km due north at 60 km/h, one breakfast stop at 150 km: 95 % → 40 %, charge to 85 %, arrive 30 %. */
    private val trip: PlannedTrip = run {
        val path = (0..300).map { LatLng(10.0 + it / 111.0, 76.3) }
        val charger = Charger("c1", "Test Charger", LatLng(10.0 + 150 / 111.0, 76.3), 60.0, 150_000.0)
        val stop = Stop(charger, arriveSoc = 40.0, departSoc = 85.0, chargeMinutes = 30.0, meal = Meal.Breakfast)
        val trace = listOf(SocPoint(0.0, 95.0, 0.0), SocPoint(150_000.0, 40.0, 0.0), SocPoint(150_000.0, 85.0, 0.0), SocPoint(300_000.0, 30.0, 0.0))
        val plan = TripPlan(PlanOutcome.Ok(listOf(stop), 30.0), trace, 300_000.0, 300.0, EnergyBreakdown(0.0, 0.0, 0.0), 38.0)
        PlannedTrip(
            origin = path.first(), destination = NamedPlace("p", "End", path.last()),
            route = RouteResult(path, "", 300_000.0, 18_000.0, emptyList()),
            profile = (0..300 step 5).map { ProfilePoint(it * 1000.0, 0.0, 60.0) },
            chargers = listOf(charger), options = listOf(PlanOption(PlanGoal.Fastest, plan)), departAt = 0L,
        )
    }
    private val journey = Journey(trip, 95.0, schedule(trip, 95.0))

    @Test fun `stops carry the planned clock`() {
        val s = journey.stops.single()
        assertEquals(150 * 60_000.0, s.arriveAt, 60_000.0)               // 150 km at 60 km/h
        assertEquals(35.0, s.minutes, 0.01)                               // a meal lasts at least 35 min
        assertEquals(30.0, journey.arrivalSoc, 0.01)
    }

    @Test fun `a leg runs along the road between its ends`() {
        val leg = journey.pathBetween(100_000.0, 150_000.0)
        assertEquals(10.0 + 100 / 111.0, leg.first().lat, 0.002)
        assertEquals(10.0 + 150 / 111.0, leg.last().lat, 0.002)
        assertEquals(52, leg.size)                                        // both ends plus the 50 points between
    }
}
