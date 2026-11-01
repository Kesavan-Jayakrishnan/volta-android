package dev.irshadm.volta.car

import dev.irshadm.volta.data.maps.ChargeVerdict
import dev.irshadm.volta.data.maps.ChargerDto
import dev.irshadm.volta.data.maps.ChargerFeedback
import dev.irshadm.volta.data.maps.Confidence
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.planner.ChargeTime
import dev.irshadm.volta.core.planner.Charger
import dev.irshadm.volta.core.planner.EnergyModel
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import dev.irshadm.volta.core.planner.ProfilePoint
import dev.irshadm.volta.core.planner.Stop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * What the car screens show, as plain data: no Android or Car App Library types here, so every state
 * (on plan, behind, at a stop, ready to unplug, re-planned, last leg, no trip) is unit-tested like the planner.
 * CarUi turns these into templates; the screens only collect flows and call invalidate().
 */

enum class Tone { Neutral, Good, Warn, Bad }

/** One pane row: a title and one line under it. [dots] prefixes the line with a 1–5 confidence rating. */
data class CarRow(val title: String, val text: String? = null, val tone: Tone = Tone.Neutral, val dots: Int? = null)

data class CarChip(val text: String, val tone: Tone)

/** The battery ring beside the rows. [caption] is short: it's drawn inside the ring under the percentage. */
data class CarRing(val soc: Int, val caption: String, val tone: Tone, val targetSoc: Int? = null)

sealed interface CarAction {
    val label: String

    data class Navigate(override val label: String, val name: String, val to: LatLng) : CarAction
    data class StopDetails(val chargerId: String, override val label: String = "Stop details") : CarAction
    data class ChargerProblem(val chargerId: String, override val label: String = "Charger problem") : CarAction
    data object AllStops : CarAction { override val label = "All stops" }
    data object Chargers : CarAction { override val label = "Chargers nearby" }
}

/** A PaneTemplate: title, status chip, up to 4 rows, the ring, up to 2 buttons and header actions. */
data class PaneUi(
    val title: String,
    val rows: List<CarRow>,
    val chip: CarChip? = null,
    val ring: CarRing? = null,
    val actions: List<CarAction> = emptyList(),
    val headerActions: List<CarAction> = emptyList(),
)

enum class Badge { Done, Next, Later, New, Destination }

/** One row of the "All stops" list. */
data class StopRowUi(val chargerId: String?, val number: Int?, val badge: Badge, val title: String, val text: String, val text2: String? = null)

/** One row of "Fast chargers nearby". */
data class ChargerRowUi(
    val id: String, val name: String, val distanceKm: Double, val text: String, val position: LatLng,
    val history: String? = null, val historyTone: Tone = Tone.Neutral,
)

/** Everything the live-trip screens need, lifted off ActiveTrip so it can be built by hand in tests. */
data class TripFacts(
    val destination: String,
    val destinationPosition: LatLng,
    val stops: List<Stop>,
    /** Stop charger ids in the plan the trip started with; anything else was added by a re-plan. */
    val originalStopIds: Set<String>,
    val chargers: List<Charger>,
    val profile: List<ProfilePoint>,
    val alongM: Double,
    val totalM: Double,
    val soc: Double,
    val expectedSoc: Double,
    val plannedArrivalSoc: Double?,
    val socEstimated: Boolean = false,
    val visited: Set<String> = emptySet(),
    val ready: Set<String> = emptySet(),
    val lastLog: String? = null,
    val charging: Boolean = false,
    val vehicle: VehicleProfile = VehicleProfile(),
    val minArrivalPercent: Double = 10.0,
    val history: (String) -> List<ChargerFeedback.Entry> = { emptyList() },
    val now: Long = System.currentTimeMillis(),
) {
    val deviation: Double get() = soc - expectedSoc
    /** Same rule as TripRepository: a stop stays "next" until the car is 300 m past it. */
    val nextStop: Stop? get() = stops.firstOrNull { it.charger.alongRouteM > alongM - CarModels.PAST_STOP_M }
    val stopsAhead: List<Stop> get() = stops.filter { it.charger.alongRouteM > alongM - CarModels.PAST_STOP_M }
    /** The next stop once the car has been within 400 m of it. */
    val atStop: Stop? get() = nextStop?.takeIf { it.charger.id in visited }
}

/** The no-trip screen's inputs. */
data class HomeFacts(
    val signedIn: Boolean,
    val soc: Double?,
    val rangeKm: Double?,
    val charging: Boolean = false,
    val pluggedIn: Boolean = false,
    val chargeMinutesLeft: Int? = null,
    val ignitionOn: Boolean = false,
    val parkedSince: Long? = null,
    val rangeWatchOn: Boolean = true,
    /** The saved login needs a Reconnect on the phone (session taken by the MG app, or expired). */
    val linkDown: Boolean = false,
    val lastReadingAt: Long? = null,
    val now: Long = System.currentTimeMillis(),
)

object CarModels {
    const val PAST_STOP_M = 300.0
    /** Early warnings come at 3, 6, 9 … % below plan (TripRepository.GUARD_STEP). */
    const val GUARD = 3.0
    const val MEAL_MIN = 35.0
    const val DOTS = 5

    // ---------------------------------------------------------------- the root pane

    fun root(f: TripFacts): PaneUi = f.atStop?.let { atStop(f, it) } ?: liveTrip(f)

    fun liveTrip(f: TripFacts): PaneUi {
        val next = f.nextStop
        val times = reachTimes(f)
        val ring = CarRing(
            f.soc.roundToInt(),
            if (f.socEstimated) "estimated" else "plan ${f.expectedSoc.roundToInt()} %",
            if (f.deviation <= -GUARD) Tone.Bad else Tone.Neutral,
        )
        if (next == null) {
            val km = ((f.totalM - f.alongM) / 1000).coerceAtLeast(0.0)
            val arrive = arrivalSoc(f, f.totalM)
            val arriveTone = when {
                arrive < f.minArrivalPercent -> Tone.Bad
                f.plannedArrivalSoc != null && arrive < f.plannedArrivalSoc - GUARD -> Tone.Warn
                else -> Tone.Neutral
            }
            return PaneUi(
                title = "Trip to ${f.destination}",
                chip = chip(f, outOfReach = arrive < f.minArrivalPercent),
                rows = listOf(
                    CarRow("Last leg · ${f.destination}", "${km.roundToInt()} km · arrive ${clock(times.destination)}"),
                    CarRow("Arrive with ${arrive.roundToInt()} %", f.plannedArrivalSoc?.let { "Plan said ${it.roundToInt()} %" } ?: "No more charging stops", arriveTone),
                    CarRow("No more charging stops", if (arriveTone == Tone.Bad) "Ease off and watch the battery" else "Enough battery to the end with your buffer",
                        if (arriveTone == Tone.Bad) Tone.Bad else Tone.Good),
                ),
                ring = ring,
                actions = listOf(CarAction.Navigate("Navigate to ${short(f.destination)}", f.destination, f.destinationPosition)),
                headerActions = listOf(CarAction.AllStops),
            )
        }
        val km = ((next.charger.alongRouteM - f.alongM) / 1000).coerceAtLeast(0.0)
        val arrive = arrivalSoc(f, next.charger.alongRouteM)
        val outOfReach = arrive < f.minArrivalPercent
        val arriveTone = when {
            outOfReach -> Tone.Bad
            arrive < next.arriveSoc - GUARD -> Tone.Warn
            else -> Tone.Neutral
        }
        val conf = Confidence.of(next.charger, f.chargers, f.history(next.charger.id), f.now)
        val isNew = next.charger.id !in f.originalStopIds
        return PaneUi(
            title = "Trip to ${f.destination}",
            chip = chip(f, outOfReach),
            rows = listOf(
                CarRow("Next · ${next.charger.name}", "${km.roundToInt()} km · reach ${clock(times.at(next))}"),
                CarRow(
                    "Arrive with ${arrive.roundToInt()} %",
                    when {
                        outOfReach -> "Not safe · plan said ${next.arriveSoc.roundToInt()} %"
                        isNew -> "New stop · plan said ${next.arriveSoc.roundToInt()} %"
                        else -> "Plan said ${next.arriveSoc.roundToInt()} %"
                    },
                    arriveTone,
                ),
                CarRow(chargeTitle(next), chargeText(next)),
                CarRow("Charger · ${conf.label}", plugs(next.charger, conf), dots = conf.dots),
            ),
            ring = ring,
            actions = listOf(
                CarAction.Navigate("Navigate to ${short(next.charger.name)}", next.charger.name, next.charger.position),
                CarAction.StopDetails(next.charger.id),
            ),
            headerActions = listOf(CarAction.AllStops),
        )
    }

    fun atStop(f: TripFacts, s: Stop): PaneUi {
        val ready = s.charger.id in f.ready || f.soc >= s.departSoc - 0.5
        val idx = f.stops.indexOf(s) + 1
        val left = ChargeTime.minutes(f.vehicle, f.soc, s.departSoc, s.charger.maxKw).roundToInt()
        val after = f.stops.firstOrNull { it.charger.alongRouteM > s.charger.alongRouteM + PAST_STOP_M }
        val legEndM = after?.charger?.alongRouteM ?: f.totalM
        val legName = after?.let { short(it.charger.name) } ?: short(f.destination)
        val legKm = ((legEndM - s.charger.alongRouteM) / 1000).coerceAtLeast(0.0)
        val legSoc = after?.arriveSoc ?: f.plannedArrivalSoc
        val times = reachTimes(f)
        val legReach = after?.let { times.at(it) } ?: times.destination
        val first = when {
            ready -> CarRow("Reached ${f.soc.roundToInt()} %", "Unplug and go · enough for $legName with your buffer", Tone.Good)
            f.charging -> CarRow("Unplug at ${s.departSoc.roundToInt()} %", "About $left min left · I'll tell you when you can go")
            else -> CarRow("Charge to ${s.departSoc.roundToInt()} %", "Plug in · about $left min at ${s.charger.maxKw.roundToInt()} kW")
        }
        return PaneUi(
            title = when {
                ready -> "Ready to go from ${short(s.charger.name)}"
                f.charging -> "Charging at ${short(s.charger.name)}"
                else -> "At ${short(s.charger.name)}"
            },
            chip = if (ready) CarChip("Unplug now", Tone.Good) else CarChip("Stop $idx of ${f.stops.size}", Tone.Neutral),
            rows = listOf(
                first,
                CarRow("Next leg · ${legKm.roundToInt()} km to $legName", "Reach ${clock(legReach)}" + (legSoc?.let { " with ${it.roundToInt()} %" } ?: "")),
                s.meal?.let { m -> CarRow("${m.label} · ${s.mealPlace ?: "eat here"}", "${maxOf(s.chargeMinutes, MEAL_MIN).roundToInt()} min planned here") }
                    ?: CarRow("No meal here", "${s.chargeMinutes.roundToInt()} min planned here"),
                CarRow("Arrival in ${f.destination}", "${clock(times.destination)}" + (f.plannedArrivalSoc?.let { " with ${it.roundToInt()} %" } ?: "")),
            ),
            ring = CarRing(f.soc.roundToInt(), if (ready) "target reached" else "target ${s.departSoc.roundToInt()} %", Tone.Good, s.departSoc.roundToInt()),
            actions = listOfNotNull(
                if (ready) CarAction.Navigate("Navigate to $legName", after?.charger?.name ?: f.destination, after?.charger?.position ?: f.destinationPosition) else null,
                CarAction.ChargerProblem(s.charger.id),
            ),
            headerActions = listOf(CarAction.AllStops),
        )
    }

    private fun chip(f: TripFacts, outOfReach: Boolean): CarChip {
        val d = f.deviation
        return when {
            outOfReach -> CarChip("Out of reach", Tone.Bad)
            f.lastLog?.startsWith("Re-planned") == true -> CarChip("Re-planned", Tone.Warn)
            d <= -GUARD -> CarChip("${(-d).roundToInt()} % below plan", Tone.Warn)
            d >= 2 -> CarChip("+${d.roundToInt()} % vs plan", Tone.Good)
            else -> CarChip("On plan", Tone.Good)
        }
    }

    // ---------------------------------------------------------------- lists and details

    fun stopRows(f: TripFacts): List<StopRowUi> {
        val times = reachTimes(f)
        val next = f.nextStop
        val at = f.atStop
        val rows = f.stops.mapIndexed { i, s ->
            val id = s.charger.id
            val past = s.charger.alongRouteM <= f.alongM - PAST_STOP_M || (id in f.visited && s != at)
            val badge = when {
                past -> Badge.Done
                s == at -> Badge.Next
                id !in f.originalStopIds -> Badge.New
                s == next -> Badge.Next
                else -> Badge.Later
            }
            val text = when {
                past -> "Done · charged to ${s.departSoc.roundToInt()} %"
                s == at -> "Here now · charge to ${s.departSoc.roundToInt()} %"
                else -> "${((s.charger.alongRouteM - f.alongM) / 1000).roundToInt()} km · reach ${clock(times.at(s))} · ${s.arriveSoc.roundToInt()} % → ${s.departSoc.roundToInt()} %"
            }
            val text2 = listOfNotNull(
                if (badge == Badge.New) "New stop" else null,
                s.meal?.label?.let { if (s.mealPlace != null) "$it · ${s.mealPlace}" else it },
            ).joinToString(" · ").ifEmpty { null }
            StopRowUi(id, i + 1, badge, s.charger.name, text, text2)
        }
        val dest = StopRowUi(null, null, Badge.Destination, f.destination,
            "Arrive ${clock(times.destination)}" + (f.plannedArrivalSoc?.let { " with ${it.roundToInt()} %" } ?: ""),
            "${(f.totalM / 1000).roundToInt()} km in total")
        return rows + dest
    }

    fun stopDetail(f: TripFacts, chargerId: String): PaneUi? {
        val s = f.stops.firstOrNull { it.charger.id == chargerId } ?: return null
        val idx = f.stops.indexOf(s) + 1
        val times = reachTimes(f)
        val past = s.charger.alongRouteM <= f.alongM - PAST_STOP_M
        val km = (s.charger.alongRouteM - f.alongM) / 1000
        val conf = Confidence.of(s.charger, f.chargers, f.history(chargerId), f.now)
        val backup = conf.backup
        return PaneUi(
            title = "Stop $idx · ${s.charger.name}",
            rows = listOf(
                if (past) CarRow("Done", "${(-km).roundToInt()} km back · ${s.charger.maxKw.roundToInt()} kW")
                else CarRow("Reach ${clock(times.at(s))} with ${s.arriveSoc.roundToInt()} %", "${km.coerceAtLeast(0.0).roundToInt()} km · ${s.charger.maxKw.roundToInt()} kW"),
                CarRow("Charge to ${s.departSoc.roundToInt()} % · about ${s.chargeMinutes.roundToInt()} min",
                    s.meal?.let { m -> "${m.label}${s.mealPlace?.let { " · $it" } ?: " here"}" } ?: "No meal planned here"),
                CarRow("Confidence · ${conf.label}", conf.reasons.joinToString(" · ").ifEmpty { plugs(s.charger, conf) }, dots = conf.dots),
                backup?.let { b ->
                    val d = conf.backupKm ?: 0.0
                    CarRow("Backup · ${b.name}", "${abs(d).roundToInt()} km ${if (d >= 0) "further" else "earlier"} · ${b.maxKw.roundToInt()} kW")
                } ?: CarRow("No backup within 15 km", "The next charger on the plan is the fallback"),
            ),
            actions = listOfNotNull(
                CarAction.Navigate("Navigate", s.charger.name, s.charger.position),
                backup?.let { CarAction.Navigate("Navigate to backup", it.name, it.position) },
            ),
        )
    }

    fun home(h: HomeFacts): PaneUi {
        if (!h.signedIn) {
            return PaneUi(
                title = "Volta",
                rows = listOf(
                    CarRow("No car data", "Sign in to MG i-SMART on your phone"),
                    CarRow("Plan a trip on your phone", "Volta follows along here once it starts"),
                ),
                actions = listOf(CarAction.Chargers),
            )
        }
        val soc = h.soc
        val range = h.rangeKm
        if (h.linkDown) {
            val age = h.lastReadingAt?.let { ((h.now - it) / 60_000).toInt() }
            return PaneUi(
                title = "Volta",
                chip = CarChip("Link down", Tone.Warn),
                rows = listOf(
                    CarRow("Car connection lost", "The MG app signed in, or the session expired", Tone.Warn),
                    CarRow("Open Volta on your phone to reconnect", "One tap, when you're parked"),
                    CarRow(age?.let { "Last reading ${duration(it)} ago" } ?: "Last reading saved on the phone",
                        listOfNotNull(soc?.let { "${it.roundToInt()} %" }, range?.let { "about ${it.roundToInt()} km" }).joinToString(" · ").ifEmpty { null }),
                    CarRow("Trip alerts are paused", "Navigation and chargers nearby still work"),
                ),
                ring = soc?.let { CarRing(it.roundToInt(), age?.let { a -> "${duration(a)} ago" } ?: "saved", Tone.Warn) },
                actions = listOf(CarAction.Chargers),
            )
        }
        val state = when {
            h.charging -> CarRow("Charging", h.chargeMinutesLeft?.let { "About ${duration(it)} left" } ?: "Plugged in and charging", Tone.Good)
            h.pluggedIn -> CarRow("Plugged in", "Not charging right now")
            h.ignitionOn -> CarRow("Driving", "No planned trip · range watch keeps an eye on the battery")
            else -> CarRow("Parked", h.parkedSince?.let { "Since ${clock(it)}" })
        }
        return PaneUi(
            title = "Volta",
            rows = listOf(
                if (soc == null) CarRow("Waiting for the car", "Last reading not available yet")
                else CarRow(range?.let { "About ${it.roundToInt()} km of range" } ?: "Battery ${soc.roundToInt()} %",
                    listOfNotNull(range?.let { "Battery ${soc.roundToInt()} %" }).joinToString(" · ").ifEmpty { null }),
                state,
                CarRow(if (h.rangeWatchOn) "Range watch is on" else "Range watch is off",
                    if (h.rangeWatchOn) "Tells you to charge before heading home" else "Turn it on in Settings → Alerts on your phone"),
                CarRow("Plan a trip on your phone", "Volta follows along here once it starts"),
            ),
            ring = soc?.let { CarRing(it.roundToInt(), range?.let { r -> "${r.roundToInt()} km" } ?: "battery", if (h.charging) Tone.Good else Tone.Neutral) },
            actions = listOf(CarAction.Chargers),
        )
    }

    /** Nearby DC chargers with your own history at each one, nearest first. */
    fun chargerRows(at: LatLng, found: List<ChargerDto>, history: (String) -> List<ChargerFeedback.Entry>, now: Long = System.currentTimeMillis()): List<ChargerRowUi> =
        found.map { c ->
            val km = Geo.distanceM(at, c.position) / 1000
            val plugs = c.availableCount?.let { free -> c.connectorCount?.let { "$free of $it free" } ?: "$free free" } ?: "free plugs unknown"
            val h = history(c.id)
            val worked = h.count { it.verdict == ChargeVerdict.Worked }
            val last = h.lastOrNull()
            val (line, tone) = when {
                last?.verdict == ChargeVerdict.Broken && now - last.at < 30 * ChargerFeedback.DAY -> "Broken when you tried, ${daysAgo(now - last.at)}" to Tone.Bad
                last?.verdict == ChargeVerdict.Waited && now - last.at < 60 * ChargerFeedback.DAY -> "You had to wait here ${daysAgo(now - last.at)}" to Tone.Warn
                worked > 0 -> "Worked for you ${times(worked)}" to Tone.Good
                else -> null to Tone.Neutral
            }
            ChargerRowUi(c.id, c.name, km, "${c.maxKw.roundToInt()} kW · $plugs", c.position, line, tone)
        }.sortedBy { it.distanceKm }

    // ---------------------------------------------------------------- timing and words

    class ReachTimes(private val stops: Map<String, Long>, val destination: Long) {
        fun at(s: Stop): Long = stops[s.charger.id] ?: destination
    }

    /**
     * Clock times for every stop ahead and the destination: driving at the profile's speeds, plus the planned
     * dwell at each stop in between (what's left of this charge when you're at one now).
     */
    fun reachTimes(f: TripFacts): ReachTimes {
        var t = f.now
        var fromM = f.alongM
        val out = HashMap<String, Long>()
        val at = f.atStop
        for (s in f.stopsAhead) {
            t += (minutesBetween(f.profile, fromM, s.charger.alongRouteM) * 60_000).toLong()
            out[s.charger.id] = t
            val dwell = if (s == at) ChargeTime.minutes(f.vehicle, f.soc, s.departSoc, s.charger.maxKw)
            else maxOf(s.chargeMinutes, if (s.meal != null) MEAL_MIN else 0.0)
            t += (dwell * 60_000).toLong()
            fromM = maxOf(fromM, s.charger.alongRouteM)
        }
        t += (minutesBetween(f.profile, fromM, f.totalM) * 60_000).toLong()
        return ReachTimes(out, t)
    }

    /** Minutes of driving between two points on the route, at the profile's speeds (as TripRepository does). */
    fun minutesBetween(profile: List<ProfilePoint>, fromM: Double, toM: Double): Double {
        if (toM <= fromM) return 0.0
        val pts = profile.filter { it.distanceM in fromM..toM }
        if (pts.size < 2) {
            val speed = profile.lastOrNull { it.distanceM <= fromM }?.speedKmh ?: profile.firstOrNull()?.speedKmh ?: 60.0
            return (toM - fromM) / 1000 / speed.coerceAtLeast(5.0) * 60
        }
        return pts.zipWithNext { a, b -> (b.distanceM - a.distanceM) / 1000 / b.speedKmh.coerceAtLeast(5.0) * 60 }.sum()
    }

    /** Battery on reaching [toM] from where the car is now, with the plan's energy model. */
    fun arrivalSoc(f: TripFacts, toM: Double): Double {
        val seg = f.profile.filter { it.distanceM in f.alongM..toM }
        return if (seg.size >= 2) EnergyModel(f.vehicle).simulate(seg, f.soc).last().socPercent else f.soc
    }

    private fun chargeTitle(s: Stop) = s.meal?.let { "${it.label} here · charge to ${s.departSoc.roundToInt()} %" } ?: "Charge to ${s.departSoc.roundToInt()} %"
    private fun chargeText(s: Stop) = if (s.meal != null) "About ${maxOf(s.chargeMinutes, MEAL_MIN).roundToInt()} min" + (s.mealPlace?.let { " · $it" } ?: "")
    else "About ${s.chargeMinutes.roundToInt()} min · ${s.charger.maxKw.roundToInt()} kW"

    private fun plugs(c: Charger, conf: Confidence): String {
        val live = c.availableCount?.let { free -> c.connectorCount?.let { "$free of $it plugs free" } ?: "$free plugs free" }
        return (listOfNotNull(live) + conf.reasons).joinToString(" · ").ifEmpty { "No live plug data" }
    }

    /** "Tata Power EZ Charge - Hotel Saravana Bhavan" → "Hotel Saravana Bhavan"; long names are cut for a button. */
    fun short(name: String): String {
        val tail = name.substringAfterLast(" - ").substringAfterLast(", ").trim()
        return if (tail.length <= 22) tail else tail.take(21).trimEnd() + "…"
    }

    private val clockFmt = SimpleDateFormat("h:mm a", Locale.ENGLISH)
    fun clock(ms: Long): String = synchronized(clockFmt) { clockFmt.format(Date(ms)).lowercase() }

    fun duration(min: Int): String = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    private fun times(n: Int) = if (n == 1) "once" else "$n times"
    private fun daysAgo(ms: Long) = (ms / ChargerFeedback.DAY).let { if (it == 0L) "today" else if (it == 1L) "yesterday" else "$it days ago" }
}
