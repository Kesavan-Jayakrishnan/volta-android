package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.ChargerPreferences
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MealPlannerTest {
    private val car = VehicleProfile()
    private val settings = TripSettings()
    private val meals = MealPlanner(car, settings, ChargerPreferences())

    private fun road(km: Double, speed: Double = 60.0) = (0..(km * 5).toInt()).map { ProfilePoint(it * 200.0, 0.0, speed) }
    private fun charger(id: String, km: Double, detourM: Double = 0.0, open24h: Boolean? = null) =
        Charger(id, "C$id", LatLng(0.0, km / 111.0), 50.0, alongRouteM = km * 1000, detourM = detourM, open24h = open24h)

    private fun stops(p: TripPlan) = (p.outcome as PlanOutcome.Ok).stops

    @Test
    fun morningDriveGetsABreakfastStopWithFood() {
        // 180 km at 60 km/h from 6:00 → breakfast window 7–10 is mid-drive; a short trip needs no charge otherwise.
        val points = road(180.0)
        val chargers = listOf(charger("a", 40.0), charger("b", 70.0), charger("c", 100.0))
        val r = meals.plan(points, 90.0, chargers, 180.0, departMin = 6 * 60, food = mapOf(("b" to Meal.Breakfast) to "Udupi Hotel"))
        val meal = stops(r.plan).firstOrNull { it.meal == Meal.Breakfast }
        assertNotNull(meal)
        assertEquals("b", meal.charger.id)                    // the one with food
        assertEquals("Udupi Hotel", meal.mealPlace)
        assertTrue(meal.departSoc > meal.arriveSoc)           // charges while eating
    }

    @Test
    fun existingStopInWindowBecomesTheMealStop() {
        // Needs a charge anyway; at 12:00 departure the stop lands around lunch.
        val points = road(280.0)
        val chargers = listOf(charger("x", 90.0), charger("y", 130.0), charger("z", 160.0))
        val r = meals.plan(points, 60.0, chargers, 280.0, departMin = 12 * 60, food = emptyMap())
        val s = stops(r.plan)
        assertTrue(s.any { it.meal == Meal.Lunch }, "lunch stop expected, got $s")
        assertTrue(s.first { it.meal == Meal.Lunch }.chargeMinutes >= 15)
    }

    @Test
    fun nightDriveUsesOnlyOnRoadChargersAndNoMeals() {
        val points = road(250.0)
        val chargers = listOf(charger("off", 120.0, detourM = 3_000.0), charger("on", 130.0, detourM = 200.0, open24h = true))
        val r = meals.plan(points, 80.0, chargers, 250.0, departMin = 21 * 60 + 30)
        val s = stops(r.plan)
        assertTrue(s.all { it.charger.id == "on" }, "night stops: $s")
        assertNull(s.firstOrNull { it.meal != null })
    }

    @Test
    fun earlyStartGetsBreakfastAndLunchAndDaytimeStopsCanBeOffRoad() {
        // 4:00 start, 480 km at 60 km/h → till about 1 pm. Night only covers the first hour.
        val points = road(480.0)
        val chargers = listOf(30.0, 90.0, 160.0, 200.0, 260.0, 330.0, 380.0, 420.0, 460.0).mapIndexed { i, km -> charger("c$i", km, detourM = 2_000.0) }
        val r = meals.plan(points, 90.0, chargers, 480.0, departMin = 4 * 60, food = chargers.flatMap { c -> Meal.entries.map { (c.id to it) to "Hotel ${c.id}" } }.toMap())
        val s = stops(r.plan)
        assertTrue(s.any { it.meal == Meal.Breakfast }, "breakfast expected, got $s")
        assertTrue(s.any { it.meal == Meal.Lunch }, "lunch expected, got $s")
        assertTrue(r.notes.none { it.contains("night", ignoreCase = true) }, "notes: ${r.notes}")
    }

    @Test
    fun leavingInsideAMealWindowSkipsThatMeal() {
        // Leave at 8:00 (breakfast time) for a 3 h drive: no breakfast stop.
        val points = road(180.0)
        val chargers = listOf(charger("a", 40.0), charger("b", 70.0), charger("c", 100.0))
        val r = meals.plan(points, 90.0, chargers, 180.0, departMin = 8 * 60, food = mapOf(("b" to Meal.Breakfast) to "Udupi Hotel"))
        assertNull(stops(r.plan).firstOrNull { it.meal != null })
    }

    @Test
    fun shortTripOutsideMealTimesIsUnchanged() {
        val points = road(60.0)
        val r = meals.plan(points, 80.0, listOf(charger("a", 30.0)), 60.0, departMin = 15 * 60)
        assertIs<PlanOutcome.Ok>(r.plan.outcome)
        assertTrue(stops(r.plan).isEmpty())
    }

    @Test
    fun chosenChargerAndSkippedMealAreRespected() {
        val points = road(180.0)
        val chargers = listOf(charger("a", 40.0), charger("b", 70.0), charger("c", 100.0))
        val food = mapOf(("b" to Meal.Breakfast) to "Udupi Hotel", ("c" to Meal.Breakfast) to "Hotel Aryaas")
        val chosen = meals.plan(points, 90.0, chargers, 180.0, departMin = 6 * 60, food = food, choices = MealChoices(at = mapOf(Meal.Breakfast to "c")))
        assertEquals("c", stops(chosen.plan).first { it.meal == Meal.Breakfast }.charger.id)
        val skipped = meals.plan(points, 90.0, chargers, 180.0, departMin = 6 * 60, food = food, choices = MealChoices(skip = setOf(Meal.Breakfast)))
        assertNull(stops(skipped.plan).firstOrNull { it.meal != null })
    }

    @Test
    fun mealMovesToANearbyChargerWithFood() {
        // From 10:00 the stop the car needs (z, 160 km) lands at 12:40 in lunch (12:30–14:30) with no restaurant;
        // y, 30 km earlier and 20 min before the window, has one, so lunch moves there.
        val points = road(280.0)
        val chargers = listOf(charger("x", 90.0), charger("y", 130.0), charger("z", 160.0))
        val r = meals.plan(points, 60.0, chargers, 280.0, departMin = 10 * 60, food = mapOf(("y" to Meal.Lunch) to "Hotel Y"))
        val lunch = stops(r.plan).first { it.meal == Meal.Lunch }
        assertEquals("y", lunch.charger.id)
        assertEquals("Hotel Y", lunch.mealPlace)
        assertEquals(1, stops(r.plan).count { it.meal != null })
    }

    @Test
    fun mealStaysWhenNoChargerNearbyHasFood() {
        val points = road(280.0)
        val chargers = listOf(charger("x", 90.0), charger("y", 130.0), charger("z", 160.0))
        val r = meals.plan(points, 60.0, chargers, 280.0, departMin = 10 * 60, food = emptyMap())
        assertEquals("z", stops(r.plan).first { it.meal == Meal.Lunch }.charger.id)
    }
}
