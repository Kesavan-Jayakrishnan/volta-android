package dev.irshadm.volta.car

import dev.irshadm.volta.core.planner.Charger
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.core.planner.Meal
import dev.irshadm.volta.core.planner.ProfilePoint
import dev.irshadm.volta.core.planner.Stop
import dev.irshadm.volta.data.maps.ChargeVerdict
import dev.irshadm.volta.data.maps.ChargerDto
import dev.irshadm.volta.data.maps.ChargerFeedback
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The car screens' states, built by hand: on plan, behind, out of reach, at a stop, ready, re-planned, last leg, no trip. */
class CarModelsTest {
    private val now = 1_792_990_000_000L
    private val totalM = 450_000.0

    /** Flat route at 80 km/h, a point every 200 m. */
    private val profile = (0..(totalM / 200).toInt()).map { ProfilePoint(it * 200.0, 20.0, 80.0) }

    private fun charger(id: String, name: String, alongKm: Double, kw: Double = 60.0, free: Int? = 2, count: Int? = 4) = Charger(id, name, LatLng(10.0 + alongKm / 1000, 76.0), kw, alongKm * 1000, connectorCount = count, availableCount = free, open24h = true)

    private val palakkad = charger("p", "Highway DC hub, Palakkad", 58.0)
    private val salem = charger("s", "Zeon Charging - Salem bypass", 248.0, free = null, count = null)
    private val krishnagiri = charger("k", "DC charger, Krishnagiri", 362.0, kw = 120.0)
    private val alathur = charger("a", "DC charger, Alathur", 46.0, kw = 50.0, free = 1, count = 2)
    private val chargers = listOf(alathur, palakkad, salem, krishnagiri)
    private val stops = listOf(
        Stop(palakkad, 31.0, 78.0, 35.0, Meal.Breakfast, "Hotel Saravana Bhavan"),
        Stop(salem, 27.0, 70.0, 30.0),
        Stop(krishnagiri, 33.0, 60.0, 25.0, Meal.Lunch, "A2B"),
    )

    private fun facts(alongKm: Double, soc: Double, expected: Double, vararg changes: (TripFacts) -> TripFacts): TripFacts {
        var f = TripFacts(
            destination = "Bengaluru", destinationPosition = LatLng(12.97, 77.59), stops = stops, originalStopIds = setOf("p", "s", "k"),
            chargers = chargers, profile = profile, alongM = alongKm * 1000, totalM = totalM, soc = soc, expectedSoc = expected,
            plannedArrivalSoc = 22.0, now = now,
        )
        changes.forEach { f = it(f) }
        return f
    }

    @Test fun `on plan shows the next stop with its meal and charge`() {
        val ui = CarModels.root(facts(alongKm = 20.0, soc = 64.0, expected = 63.0))
        assertEquals("Trip to Bengaluru", ui.title)
        assertEquals(CarChip("On plan", Tone.Good), ui.chip)
        assertEquals(4, ui.rows.size)
        assertEquals("Next · Highway DC hub, Palakkad", ui.rows[0].title)
        assertContains(ui.rows[0].text!!, "38 km")
        assertEquals("Breakfast here · charge to 78 %", ui.rows[2].title)
        assertContains(ui.rows[2].text!!, "Hotel Saravana Bhavan")
        assertEquals(Tone.Neutral, ui.rows[1].tone)
        assertNotNull(ui.rows[3].dots)
        assertEquals(listOf("Navigate to Palakkad", "Stop details"), ui.actions.map { it.label })
        assertEquals(listOf(CarAction.AllStops), ui.headerActions)
        assertEquals(64, ui.ring?.soc)
    }

    @Test fun `behind plan turns the chip and the ring amber-red`() {
        val ui = CarModels.root(facts(alongKm = 21.0, soc = 57.0, expected = 61.0))
        assertEquals(CarChip("4 % below plan", Tone.Warn), ui.chip)
        assertEquals(Tone.Bad, ui.ring?.tone)
    }

    @Test fun `a stop out of reach is said plainly`() {
        val ui = CarModels.root(facts(alongKm = 27.0, soc = 8.0, expected = 58.0))
        assertEquals(CarChip("Out of reach", Tone.Bad), ui.chip)
        assertEquals(Tone.Bad, ui.rows[1].tone)
        assertContains(ui.rows[1].text!!, "Not safe")
    }

    @Test fun `a re-planned trip marks the new stop`() {
        val replanned = listOf(Stop(alathur, 18.0, 85.0, 45.0, Meal.Breakfast, "Cafe")) + stops.drop(1)
        val f = facts(27.0, 49.0, 58.0, { it.copy(stops = replanned, lastLog = "Re-planned: using more than expected") })
        val ui = CarModels.root(f)
        assertEquals(CarChip("Re-planned", Tone.Warn), ui.chip)
        assertEquals("Next · DC charger, Alathur", ui.rows[0].title)
        assertContains(ui.rows[1].text!!, "New stop")
        val rows = CarModels.stopRows(f)
        assertEquals(Badge.New, rows[0].badge)
        assertEquals("New stop · Breakfast · Cafe", rows[0].text2)
        assertEquals(Badge.Destination, rows.last().badge)
    }

    @Test fun `at a stop while charging shows unplug target and minutes left`() {
        val f = facts(58.0, 52.0, 31.0, { it.copy(visited = setOf("p"), charging = true) })
        val ui = CarModels.root(f)
        assertEquals("Charging at Palakkad", ui.title)
        assertEquals(CarChip("Stop 1 of 3", Tone.Neutral), ui.chip)
        assertEquals("Unplug at 78 %", ui.rows[0].title)
        assertContains(ui.rows[0].text!!, "min left")
        assertEquals("Next leg · 190 km to Salem bypass", ui.rows[1].title)
        assertContains(ui.rows[1].text!!, "with 27 %")
        assertEquals("Breakfast · Hotel Saravana Bhavan", ui.rows[2].title)
        assertEquals(78, ui.ring?.targetSoc)
        assertEquals(listOf("Charger problem"), ui.actions.map { it.label })
    }

    @Test fun `ready to go offers navigation to the next stop`() {
        val f = facts(58.0, 78.0, 31.0, { it.copy(visited = setOf("p"), ready = setOf("p"), charging = true) })
        val ui = CarModels.root(f)
        assertEquals("Ready to go from Palakkad", ui.title)
        assertEquals(CarChip("Unplug now", Tone.Good), ui.chip)
        assertEquals("Reached 78 %", ui.rows[0].title)
        assertEquals(listOf("Navigate to Salem bypass", "Charger problem"), ui.actions.map { it.label })
        assertEquals("target reached", ui.ring?.caption)
    }

    @Test fun `last leg has no stop and navigates to the destination`() {
        val f = facts(400.0, 40.0, 38.0, { it.copy(visited = setOf("p", "s", "k")) })
        val ui = CarModels.root(f)
        assertEquals("Last leg · Bengaluru", ui.rows[0].title)
        assertContains(ui.rows[0].text!!, "50 km")
        assertEquals(1, ui.actions.size)
        assertEquals("Navigate to Bengaluru", ui.actions[0].label)
        assertTrue(ui.rows[1].title.startsWith("Arrive with"))
    }

    @Test fun `stop rows mark done, next and later and end with the destination`() {
        val rows = CarModels.stopRows(facts(100.0, 70.0, 68.0, { it.copy(visited = setOf("p")) }))
        assertEquals(listOf(Badge.Done, Badge.Next, Badge.Later, Badge.Destination), rows.map { it.badge })
        assertEquals(listOf(1, 2, 3, null), rows.map { it.number })
        assertContains(rows[1].text, "148 km")
        assertContains(rows[3].text, "with 22 %")
    }

    @Test fun `reach times run in order and include dwell`() {
        val f = facts(20.0, 64.0, 63.0)
        val t = CarModels.reachTimes(f)
        val a = t.at(stops[0])
        val b = t.at(stops[1])
        val c = t.at(stops[2])
        assertTrue(a < b && b < c && c < t.destination)
        // 38 km at 80 km/h ≈ 28.5 min to the first stop.
        assertEquals(28.5, (a - now) / 60_000.0, 0.6)
        // Then 35 min there (meal) and 190 km ≈ 142.5 min to Salem.
        assertEquals(35 + 142.5, (b - a) / 60_000.0, 1.0)
    }

    @Test fun `stop detail has confidence, backup and both navigation buttons`() {
        val history = mapOf("p" to listOf(ChargerFeedback.Entry(ChargeVerdict.Worked, now - 86_400_000L)))
        val ui = CarModels.stopDetail(facts(20.0, 64.0, 63.0, { it.copy(history = { id -> history[id].orEmpty() }) }), "p")!!
        assertEquals("Stop 1 · Highway DC hub, Palakkad", ui.title)
        assertTrue(ui.rows[2].title.startsWith("Confidence · "))
        assertContains(ui.rows[2].text!!, "worked for you once")
        assertEquals("Backup · DC charger, Alathur", ui.rows[3].title)
        assertContains(ui.rows[3].text!!, "12 km earlier")
        assertEquals(listOf("Navigate", "Navigate to backup"), ui.actions.map { it.label })
        assertNull(CarModels.stopDetail(facts(20.0, 64.0, 63.0), "nope"))
    }

    @Test fun `no trip shows battery, range, state and where to plan`() {
        val ui = CarModels.home(HomeFacts(signedIn = true, soc = 96.0, rangeKm = 300.0, parkedSince = now - 3_600_000L, now = now))
        assertEquals("Volta", ui.title)
        assertEquals("About 300 km of range", ui.rows[0].title)
        assertEquals("Parked", ui.rows[1].title)
        assertEquals("Range watch is on", ui.rows[2].title)
        assertEquals("Plan a trip on your phone", ui.rows[3].title)
        assertEquals(listOf(CarAction.Chargers), ui.actions)
        assertEquals("300 km", ui.ring?.caption)

        val charging = CarModels.home(HomeFacts(signedIn = true, soc = 40.0, rangeKm = 120.0, charging = true, pluggedIn = true, chargeMinutesLeft = 75, now = now))
        assertEquals("Charging", charging.rows[1].title)
        assertEquals("About 1 h 15 min left", charging.rows[1].text)

        val out = CarModels.home(HomeFacts(signedIn = false, soc = null, rangeKm = null, now = now))
        assertEquals("No car data", out.rows[0].title)
    }

    @Test fun `no trip with the link down says so and keeps the saved reading`() {
        val ui = CarModels.home(HomeFacts(signedIn = true, soc = 62.0, rangeKm = 210.0, linkDown = true, lastReadingAt = now - 23 * 60_000L, now = now))
        assertEquals("Link down", ui.chip?.text)
        assertEquals("Car connection lost", ui.rows[0].title)
        assertEquals("Open Volta on your phone to reconnect", ui.rows[1].title)
        assertEquals("Last reading 23 min ago", ui.rows[2].title)
        assertEquals("62 % · about 210 km", ui.rows[2].text)
        assertEquals(62, ui.ring?.soc)
        assertEquals("23 min ago", ui.ring?.caption)
        assertEquals(Tone.Warn, ui.ring?.tone)
    }

    @Test fun `nearby chargers are nearest first with your history`() {
        val here = LatLng(10.0, 76.0)
        fun dto(id: String, name: String, dLat: Double, kw: Double, free: Int?, count: Int?) = ChargerDto(id, name, null, LatLng(10.0 + dLat, 76.0), kw, count, free, null)
        val found = listOf(dto("far", "Aluva", 0.10, 120.0, 1, 4), dto("near", "Edappally", 0.04, 60.0, 2, 2), dto("mid", "Vyttila", 0.06, 30.0, null, null))
        val history = mapOf(
            "near" to List(3) { ChargerFeedback.Entry(ChargeVerdict.Worked, now - 10 * 86_400_000L) },
            "far" to listOf(ChargerFeedback.Entry(ChargeVerdict.Worked, now - 40 * 86_400_000L), ChargerFeedback.Entry(ChargeVerdict.Broken, now - 6 * 86_400_000L)),
        )
        val rows = CarModels.chargerRows(here, found, { history[it].orEmpty() }, now)
        assertEquals(listOf("Edappally", "Vyttila", "Aluva"), rows.map { it.name })
        assertEquals("Worked for you 3 times", rows[0].history)
        assertEquals(Tone.Good, rows[0].historyTone)
        assertEquals("60 kW · 2 of 2 free", rows[0].text)
        assertNull(rows[1].history)
        assertEquals("30 kW · free plugs unknown", rows[1].text)
        assertEquals("Broken when you tried, 6 days ago", rows[2].history)
        assertEquals(Tone.Bad, rows[2].historyTone)
        assertEquals(4.4, rows[0].distanceKm, 0.2)
    }

    @Test fun `short names fit a button`() {
        assertEquals("Hotel Saravana Bhavan", CarModels.short("Tata Power EZ Charge - Hotel Saravana Bhavan"))
        assertEquals("Palakkad", CarModels.short("Highway DC hub, Palakkad"))
        assertEquals("Bengaluru", CarModels.short("Bengaluru"))
        assertTrue(CarModels.short("A very long charging station name indeed").length <= 22)
    }
}
