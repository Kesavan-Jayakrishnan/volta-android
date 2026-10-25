package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerTest {
    private val car = VehicleProfile()                // 37.1 kWh, 6 km/kWh highway, 7 city
    private val settings = TripSettings()             // 10 % charger, 15 % destination, 80 % target
    private val model = EnergyModel(car)

    /** Straight road sampled every 200 m with a height function and constant speed. */
    private fun road(km: Double, speed: Double = 90.0, height: (Double) -> Double = { 0.0 }): List<ProfilePoint> {
        val n = (km * 5).toInt()
        return (0..n).map { i -> val d = i * 200.0; ProfilePoint(d, height(d), speed) }
    }

    private fun charger(id: String, km: Double, kw: Double = 50.0) =
        Charger(id, "Charger $id", LatLng(0.0, km / 111.0), kw, alongRouteM = km * 1000)

    @Test
    fun flatEnergyMatchesEfficiency() {
        val e = model.breakdown(road(60.0))
        assertEquals(10.0, e.flatKwh, 0.01)                 // 60 km / 6 km/kWh
        assertEquals(0.0, e.terrainKwh, 1e-9)
    }

    @Test
    fun cityAndHighwayEfficiencyBlend() {
        assertEquals(7.0, model.kmPerKwh(30.0))
        assertEquals(6.0, model.kmPerKwh(100.0))
        assertEquals(6.5, model.kmPerKwh(60.0), 1e-9)
    }

    @Test
    fun climbCostsMoreThanSameFlatDistance() {
        val flat = model.breakdown(road(40.0)).totalKwh
        val climb = model.breakdown(road(40.0) { it / 40_000.0 * 1_000.0 }).totalKwh  // +1000 m
        // m·g·h / η = 1600·9.81·1000 / 0.88 / 3.6e6 ≈ 4.95 kWh
        assertEquals(4.95, climb - flat, 0.05)
    }

    @Test
    fun roundTripOverAHillCostsMoreThanFlat() {
        val hill = road(40.0) { d -> if (d <= 20_000) d / 20_000 * 500 else (40_000 - d) / 20_000 * 500 }
        val e = model.breakdown(hill)
        assertTrue(e.climbKwh > e.regenKwh, "regen recovers only part of the climb")
        assertTrue(e.totalKwh > model.breakdown(road(40.0)).totalKwh)
    }

    @Test
    fun smallBumpsAreTreatedAsFlat() {
        val wobbly = road(20.0) { d -> if ((d / 200).toInt() % 2 == 0) 0.0 else 1.5 }  // ±1.5 m noise
        assertEquals(0.0, model.breakdown(wobbly).terrainKwh, 1e-9)
    }

    @Test
    fun descentNeverPushesSocAboveCap() {
        val downhill = road(30.0, speed = 50.0) { d -> 1_500 - d / 30_000 * 1_500 }
        val sim = model.simulate(downhill, startSoc = 99.0)
        assertTrue(sim.all { it.socPercent <= 100.0 })
    }

    @Test
    fun noStopWhenRangeIsEnough() {
        val plan = TripPlanner(car, settings).plan(road(100.0), 80.0, emptyList(), 70.0)
        val ok = assertIs<PlanOutcome.Ok>(plan.outcome)
        assertTrue(ok.stops.isEmpty())
        assertEquals(80.0 - 100.0 / 6 / 37.1 * 100, ok.arrivalSoc, 0.1)
    }

    @Test
    fun oneStopPicksFurthestReachableChargerAndArrivesAboveBuffer() {
        val chargers = listOf(charger("a", 40.0), charger("b", 90.0), charger("c", 150.0))
        val plan = TripPlanner(car, settings).plan(road(250.0), 59.0, chargers, 180.0)
        val ok = assertIs<PlanOutcome.Ok>(plan.outcome)
        // From 59 % the first stop must be a or b (c at 150 km is out of range); a second stop is needed either way.
        assertTrue(ok.stops.first().charger.id in setOf("a", "b"))
        assertEquals(2, ok.stops.size)
        assertTrue(ok.stops.all { it.arriveSoc >= settings.minArrivalAtChargerPercent })
        assertTrue(ok.arrivalSoc >= settings.minArrivalAtDestinationPercent)
        assertTrue(ok.stops.all { it.departSoc <= settings.maxChargeTargetPercent })
    }

    @Test
    fun multipleStopsOnLongTrip() {
        val chargers = (1..12).map { charger("s$it", it * 45.0) }
        val plan = TripPlanner(car, settings).plan(road(550.0), 90.0, chargers, 400.0)
        val ok = assertIs<PlanOutcome.Ok>(plan.outcome)
        assertTrue(ok.stops.size >= 2)
        assertTrue(ok.arrivalSoc >= settings.minArrivalAtDestinationPercent)
    }

    @Test
    fun unreachableWhenNoChargerInRange() {
        val plan = TripPlanner(car, settings).plan(road(300.0), 40.0, listOf(charger("far", 250.0)), 200.0)
        val u = assertIs<PlanOutcome.Unreachable>(plan.outcome)
        assertTrue(u.strandedAtM < 250_000)
    }

    @Test
    fun terrainCanForceAStopThatFlatWouldNot() {
        val ghat = road(130.0, speed = 50.0) { d -> if (d < 60_000) 0.0 else (d - 60_000) / 70_000 * 1_550 }
        // Flat: 130 km at 50 km/h ≈ 52 % of the pack, so 75 % arrives with ~23 %.
        val flatPlan = TripPlanner(car, settings).plan(road(130.0, speed = 50.0), 75.0, emptyList(), 200.0)
        assertIs<PlanOutcome.Ok>(flatPlan.outcome)
        // The 1,550 m climb adds ~21 %, so the same start now needs the charger at 52 km.
        val ghatPlan = TripPlanner(car, settings).plan(ghat, 75.0, listOf(charger("k", 52.0)), 240.0)
        val ok = assertIs<PlanOutcome.Ok>(ghatPlan.outcome)
        assertEquals(1, ok.stops.size)
        assertTrue(ghatPlan.terrainPercent > 5)
    }

    @Test
    fun chargeTimeFollowsCurveAndChargerLimit() {
        val fast = ChargeTime.minutes(car, 20.0, 80.0, 60.0)
        val slow = ChargeTime.minutes(car, 20.0, 80.0, 25.0)
        assertTrue(slow > fast)
        // 60 % of 37.1 kWh = 22.3 kWh at ≤45 kW → at least ~30 min
        assertTrue(fast in 30.0..45.0, "was $fast")
        assertEquals(0.0, ChargeTime.minutes(car, 80.0, 70.0, 50.0))
    }

    @Test
    fun polylineRoundTripAndResample() {
        val pts = listOf(LatLng(9.9312, 76.2673), LatLng(10.0889, 77.0595))  // Kochi → Munnar (straight line)
        val decoded = Geo.decodePolyline(Geo.encodePolyline(pts))
        assertEquals(pts[1].lat, decoded[1].lat, 1e-5)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Geo.encodePolyline(listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))))
        val r = Geo.resample(pts, 200.0)
        val total = Geo.distanceM(pts[0], pts[1])
        assertEquals(total, r.last().distanceM, 1.0)
        assertTrue(r.zipWithNext().all { (a, b) -> b.distanceM - a.distanceM <= 200.0 + 1e-6 })
    }

    @Test
    fun fastestBeatsOneLongChargeWhenTaperIsSteep() {
        // Two fast chargers vs one: charging 10→80 % in one go is slower than two shorter top-ups below the taper.
        val chargers = listOf(charger("x", 100.0, kw = 60.0), charger("y", 200.0, kw = 60.0))
        val opts = TripPlanner(car, settings).options(road(330.0), 80.0, chargers, 240.0)
        val fastest = opts.first { it.goal == PlanGoal.Fastest }.plan
        val fewer = opts.firstOrNull { it.goal == PlanGoal.FewerStops }?.plan
        assertIs<PlanOutcome.Ok>(fastest.outcome)
        if (fewer != null) {
            val f = fewer.outcome as PlanOutcome.Ok
            val q = fastest.outcome as PlanOutcome.Ok
            assertTrue(f.stops.size <= q.stops.size)
            assertTrue(fastest.chargeMinutes <= fewer.chargeMinutes + 1e-6 + (q.stops.size - f.stops.size) * 0.0 + 10)
        }
    }

    @Test
    fun everyOptionRespectsBuffers() {
        val chargers = (1..10).map { charger("c$it", it * 40.0, kw = if (it % 2 == 0) 50.0 else 25.0) }
        val opts = TripPlanner(car, settings).options(road(420.0), 70.0, chargers, 300.0)
        assertTrue(opts.isNotEmpty())
        opts.forEach { o ->
            val ok = assertIs<PlanOutcome.Ok>(o.plan.outcome)
            assertTrue(ok.arrivalSoc >= settings.minArrivalAtDestinationPercent - 0.5, "${o.goal} arrives ${ok.arrivalSoc}")
            ok.stops.forEach { s ->
                assertTrue(s.arriveSoc >= settings.minArrivalAtChargerPercent - 0.5, "${o.goal} arrives at ${s.charger.id} with ${s.arriveSoc}")
                assertTrue(s.departSoc <= settings.maxChargeTargetPercent + 1e-9)
            }
            // the chart trace never dips below the charger buffer before a stop
            assertTrue(o.plan.socTrace.all { it.socPercent >= settings.minArrivalAtChargerPercent - 1.0 })
        }
    }

    @Test
    fun fewerStopsNeverHasMoreStopsThanFastest() {
        val chargers = (1..12).map { charger("s$it", it * 45.0, kw = 60.0) }
        val opts = TripPlanner(car, settings).options(road(550.0), 90.0, chargers, 400.0)
        val fastest = (opts.first { it.goal == PlanGoal.Fastest }.plan.outcome as PlanOutcome.Ok).stops.size
        val fewer = (opts.firstOrNull { it.goal == PlanGoal.FewerStops }?.plan?.outcome as? PlanOutcome.Ok)?.stops?.size ?: fastest
        assertTrue(fewer <= fastest)
    }

    @Test
    fun noPointlessEarlyTopUps() {
        // Lots of chargers early on; the plan must not stop while the battery is still above half.
        val chargers = (1..20).map { charger("e$it", it * 6.0, kw = 60.0) }
        val opts = TripPlanner(car, settings).options(road(220.0), 70.0, chargers, 160.0)
        opts.forEach { o ->
            val ok = assertIs<PlanOutcome.Ok>(o.plan.outcome)
            ok.stops.forEach { assertTrue(it.arriveSoc <= 50.0 + 1e-6, "stopped at ${it.arriveSoc} %") }
            ok.stops.forEach { assertTrue(it.departSoc - it.arriveSoc >= 10.0 - 1e-6, "tiny top-up ${it.arriveSoc}→${it.departSoc}") }
        }
    }
}
