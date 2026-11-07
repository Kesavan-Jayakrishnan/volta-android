package dev.irshadm.volta.data.trip

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.irshadm.volta.core.planner.EnergyModel
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.PlanOutcome
import dev.irshadm.volta.core.planner.Replanner
import dev.irshadm.volta.core.planner.RoutePoint
import dev.irshadm.volta.core.planner.Stop
import dev.irshadm.volta.core.planner.TripPlan
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.db.TripDao
import dev.irshadm.volta.data.db.TripEntity
import dev.irshadm.volta.data.db.TripSampleEntity
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.maps.PlannedTrip
import dev.irshadm.volta.service.Notifications
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class ActiveTrip(
    val tripId: Long,
    val planned: PlannedTrip,
    val plan: TripPlan,
    val startedAt: Long,
    val startSoc: Double,
    val path: List<RoutePoint>,
    val alongM: Double = 0.0,
    val soc: Double,
    /** True when [soc] is estimated from distance because the car reading is too old. */
    val socEstimated: Boolean = false,
    val expectedSoc: Double,
    val lastCarReadingAt: Long,
    val lastCheckAt: Long = 0,
    val nextCheckAt: Long = 0,
    val energyUsedKwh: Double = 0.0,
    val replans: Int = 0,
    val log: List<String> = emptyList(),
    val notifiedStops: Set<String> = emptySet(),
    val readyStops: Set<String> = emptySet(),
    /** Stops whose plugs were re-checked ~30 min before arriving. */
    val occupancyChecked: Set<String> = emptySet(),
    /** Stops you've been at (within 400 m), and those already asked "how was it?" after leaving. */
    val visitedStops: Set<String> = emptySet(),
    val askedStops: Set<String> = emptySet(),
    /** How far behind plan the last early warning was, in steps of 3 %. */
    val warnedBand: Int = 0,
) {
    val stops: List<Stop> get() = (plan.outcome as? PlanOutcome.Ok)?.stops ?: (plan.outcome as? PlanOutcome.Unreachable)?.stops.orEmpty()
    val nextStop: Stop? get() = stops.firstOrNull { it.charger.alongRouteM > alongM - 300 }
    val totalM: Double get() = planned.route.distanceM
    val deviation: Double get() = soc - expectedSoc
}

/**
 * Live trip: every check reads the car and the phone's GPS, compares battery with the
 * plan, re-plans when behind, tells you when you can unplug, and logs samples for calibration.
 */
@Singleton
class TripRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ismart: IsmartRepository,
    private val settings: SettingsRepository,
    private val location: LocationProvider,
    private val dao: TripDao,
    private val maps: dev.irshadm.volta.data.maps.GoogleMapsClient,
    private val feedback: dev.irshadm.volta.data.maps.ChargerFeedback,
) {
    private val _active = MutableStateFlow<ActiveTrip?>(null)
    val active: StateFlow<ActiveTrip?> = _active
    private val mutex = Mutex()

    /** Pokes the trip service out of its wait so the next [tick] runs now. */
    private val _wake = Channel<Unit>(Channel.CONFLATED)
    val wake: ReceiveChannel<Unit> get() = _wake
    fun wake() {
        _wake.trySend(Unit)
    }

    /**
     * True while the phone is in the car (Android Auto connected). Ticks then come every [PROJECTED_POLL_MS]
     * instead of the settings interval, and [nudge] moves the battery estimate between them.
     */
    @Volatile var projected: Boolean = false
        private set
    fun setProjected(on: Boolean) {
        val was = projected
        projected = on
        if (on && !was) wake()
    }

    suspend fun start(planned: PlannedTrip, startSoc: Double) = mutex.withLock {
        val now = System.currentTimeMillis()
        val odo = ismart.status.value?.soc?.value?.odometerKm
        val id = dao.insert(
            TripEntity(
                startedAt = now,
                destination = planned.destination.name,
                plannedDistanceM = planned.route.distanceM,
                startSoc = startSoc,
                startOdometerKm = odo,
                plannedKwh = planned.plan.energy.totalKwh,
                source = "APP",
            ),
        )
        _active.value = ActiveTrip(
            tripId = id, planned = planned, plan = planned.plan, startedAt = now, startSoc = startSoc,
            path = Geo.resample(planned.route.path, 200.0), soc = startSoc, expectedSoc = startSoc, lastCarReadingAt = now,
            log = listOf("Started · ${startSoc.roundToInt()} %"),
        )
    }

    suspend fun end() = mutex.withLock { endLocked() }

    private suspend fun endLocked() {
        val t = _active.value ?: return
        val odo = ismart.status.value?.soc?.value?.odometerKm
        dao.get(t.tripId)?.let { e ->
            dao.update(e.copy(endedAt = System.currentTimeMillis(), endSoc = t.soc, endOdometerKm = odo, usedKwh = t.energyUsedKwh))
        }
        _active.value = null
    }

    /**
     * Moves the trip along between ticks without reading the car: phone GPS → position on the route, the plan's
     * expected battery there, and the battery projected from the last reading over the distance driven (the same
     * model [tick] uses when the car's reading is stale). The car screen's ring follows this every minute while
     * Android Auto is connected; the next tick snaps it back to the real reading.
     */
    suspend fun nudge() = mutex.withLock {
        val t = _active.value ?: return@withLock
        val fix = location.current(5_000) ?: return@withLock
        val along = Replanner.locate(t.planned.profile, t.path, fix.position).first
        if (along <= t.alongM + NUDGE_MIN_M || along >= t.totalM - ARRIVED_M) return@withLock // not moved, or let tick() handle arrival
        val vehicle = settings.vehicleProfile.first()
        val seg = t.planned.profile.filter { it.distanceM in t.alongM..along }
        val soc = if (seg.size >= 2) EnergyModel(vehicle).simulate(seg, t.soc).last().socPercent else t.soc
        val used = t.energyUsedKwh + ((t.soc - soc).coerceAtLeast(0.0) * vehicle.usableCapacityKwh / 100.0)
        _active.value = t.copy(
            alongM = along,
            soc = soc,
            expectedSoc = Replanner.expectedSoc(t.plan, along),
            energyUsedKwh = used,
            socEstimated = t.socEstimated || System.currentTimeMillis() - t.lastCarReadingAt > STALE_MS,
        )
    }

    /** One live check; returns the next interval in ms. */
    suspend fun tick(): Long = mutex.withLock {
        val t = _active.value ?: return@withLock 0L
        val trip = settings.tripSettings.first()
        val vehicle = settings.vehicleProfile.first()
        val prefs = settings.chargerPreferences.first()
        val now = System.currentTimeMillis()
        val interval = if (projected) PROJECTED_POLL_MS else trip.tripPollIntervalMinutes * 60_000L

        // 1. where are we (phone GPS; car GPS only updates occasionally)
        val fix = location.current()
        val along = fix?.let { Replanner.locate(t.planned.profile, t.path, it.position).first } ?: t.alongM

        // 2. car battery
        ismart.refresh(includeCharge = true)
        val status = ismart.status.value
        val carSoc = status?.soc?.value?.takeIf { it.measuredAtMillis > t.lastCarReadingAt - 60_000 }
        val fresh = carSoc != null && now - carSoc.measuredAtMillis < STALE_MS && ismart.link.value is LinkState.Connected
        val model = EnergyModel(vehicle)
        val soc: Double
        val estimated: Boolean
        if (fresh) {
            soc = carSoc!!.socPercent
            estimated = false
        } else {
            // Estimate from distance driven since the last good reading.
            val seg = t.planned.profile.filter { it.distanceM in t.alongM..along }
            soc = if (seg.size >= 2) model.simulate(seg, t.soc).last().socPercent else t.soc
            estimated = true
        }
        val expected = Replanner.expectedSoc(t.plan, along)
        val used = t.energyUsedKwh + ((t.soc - soc).coerceAtLeast(0.0) * vehicle.usableCapacityKwh / 100.0)
        dao.insertSample(
            TripSampleEntity(
                tripId = t.tripId, at = now, lat = fix?.position?.lat, lng = fix?.position?.lng,
                altitudeM = fix?.altitudeM, alongRouteM = along, soc = soc, expectedSoc = expected, odometerKm = carSoc?.odometerKm,
            ),
        )

        var next = t.copy(
            alongM = along,
            soc = soc,
            socEstimated = estimated,
            expectedSoc = expected,
            lastCheckAt = now,
            nextCheckAt = now + interval,
            energyUsedKwh = used,
            lastCarReadingAt = if (fresh) carSoc!!.measuredAtMillis else t.lastCarReadingAt,
        )

        // 3. arrived?
        if (along >= t.totalM - ARRIVED_M) {
            next = next.copy(log = next.log + "Arrived · ${soc.roundToInt()} %")
            _active.value = next
            Notifications.alert(context, "Arrived at ${t.planned.destination.name}", "Battery ${soc.roundToInt()} %. Trip saved.")
            endLocked()
            return@withLock 0L
        }

        // 3b. left a stop you were at → "how was it?" (one tap; feeds the charger's rating)
        if (fix != null) {
            t.stops.filter { it.charger.id in next.visitedStops && it.charger.id !in next.askedStops }.forEach { s ->
                if (Geo.distanceM(fix.position, s.charger.position) > LEFT_STOP_M) {
                    dev.irshadm.volta.service.ChargerFeedbackReceiver.ask(context, s.charger.id, s.charger.name)
                    next = next.copy(askedStops = next.askedStops + s.charger.id)
                }
            }
        }

        // 4. at a charger: charging guidance
        val stop = next.nextStop
        val charging = status?.charging?.value == true
        if (stop != null && fix != null && Geo.distanceM(fix.position, stop.charger.position) < AT_STOP_M && stop.charger.id !in next.visitedStops) {
            next = next.copy(visitedStops = next.visitedStops + stop.charger.id)
        }

        // 4a. ~30 min before the next stop: are its plugs free right now? If not, say so and name the backup.
        if (stop != null && stop.charger.id !in next.occupancyChecked) {
            val eta = minutesAlong(t.planned.profile, along, stop.charger.alongRouteM)
            if (eta in 0.0..OCCUPANCY_LEAD_MIN) {
                next = next.copy(occupancyChecked = next.occupancyChecked + stop.charger.id)
                val live = runCatching { maps.chargerAvailability(stop.charger.id) }.getOrNull()
                if (live != null && live.first == 0) {
                    val backup = dev.irshadm.volta.data.maps.Confidence.of(stop.charger, t.planned.chargers, feedback.history(stop.charger.id)).backup
                    val alt = backup?.let { b ->
                        val km = (b.alongRouteM - stop.charger.alongRouteM) / 1000
                        " Backup: ${b.name} (${"%.0f".format(kotlin.math.abs(km))} km ${if (km >= 0) "further" else "earlier"})."
                    } ?: ""
                    Notifications.alert(
                        context,
                        "All plugs taken at ${stop.charger.name}",
                        "You're about ${eta.roundToInt()} min away and ${if (live.second > 0) "0 of ${live.second}" else "none"} are free right now.$alt",
                        actions = listOfNotNull(backup?.let { Notifications.navigate(context, "Navigate to backup", it.position) }, Notifications.show(context, "Show plan")),
                    )
                    next = next.copy(log = next.log + "Plugs busy at ${stop.charger.name}")
                }
            }
        }
        if (stop != null && fix != null && Geo.distanceM(fix.position, stop.charger.position) < AT_STOP_M) {
            val id = stop.charger.id
            if (charging && id !in next.notifiedStops) {
                Notifications.alert(context, "Charge to ${stop.departSoc.roundToInt()} %", "About ${stop.chargeMinutes.roundToInt()} min at ${stop.charger.name}. I'll tell you when you can go.")
                next = next.copy(notifiedStops = next.notifiedStops + id, log = next.log + "Charging at ${stop.charger.name}")
            }
            if (soc >= stop.departSoc - 0.5 && id !in next.readyStops) {
                Notifications.alert(context, "You can unplug", "${soc.roundToInt()} % is enough to reach the next stop with your buffer.")
                next = next.copy(readyStops = next.readyStops + id, log = next.log + "Ready to leave at ${soc.roundToInt()} %")
            }
            _active.value = next
            return@withLock if (charging) minOf(CHARGING_POLL_MS, interval) else interval
        }

        // 5a. trip guardian: an early, specific heads-up each time you slip another 3 % behind plan (before it's
        // bad enough to re-plan), with what easing off would give you at the next stop.
        val band = (-next.deviation / GUARD_STEP).toInt()
        if (band >= 1 && band > t.warnedBand && next.deviation >= -trip.replanDeviationPercent) {
            val target = stop?.charger?.alongRouteM ?: t.totalM
            val name = stop?.charger?.name ?: t.planned.destination.name
            val seg = t.planned.profile.filter { it.distanceM in along..target }
            if (seg.size >= 2) {
                val asIs = model.simulate(seg, soc).last().socPercent
                val eased = model.simulate(seg.map { it.copy(speedKmh = minOf(it.speedKmh, EASE_KMH)) }, soc).last().socPercent
                val gain = eased - asIs
                val text = if (gain >= 1) {
                    "Ease to ${EASE_KMH.toInt()} km/h and you reach $name with ${eased.roundToInt()} % instead of ${asIs.roundToInt()} %."
                } else {
                    "You'll still reach $name with about ${asIs.roundToInt()} %. I'll keep watching."
                }
                val backup = stop?.let { dev.irshadm.volta.data.maps.Confidence.of(it.charger, t.planned.chargers, feedback.history(it.charger.id)).backup }
                Notifications.alert(
                    context,
                    "Running ${(-next.deviation).roundToInt()} % below plan",
                    text,
                    actions = listOfNotNull(backup?.let { Notifications.navigate(context, "Navigate to backup", it.position) }, Notifications.show(context, "Show plan")),
                )
                next = next.copy(warnedBand = band, log = next.log + "Below plan by ${(-next.deviation).roundToInt()} %")
            }
        }

        // 5. behind plan or next stop out of reach → re-plan
        val behind = next.deviation < -trip.replanDeviationPercent
        val nextReach = stop?.let { s ->
            val seg = t.planned.profile.filter { it.distanceM in along..s.charger.alongRouteM }
            if (seg.size >= 2) model.simulate(seg, soc).last().socPercent else soc
        }
        val unreachable = nextReach != null && nextReach < trip.minArrivalAtChargerPercent
        if (behind || unreachable) {
            val remainingMin = t.planned.plan.driveMinutes * (1 - along / t.totalM)
            val options = Replanner.replanFrom(vehicle, trip, prefs, t.planned.profile, t.planned.chargers, along, soc, remainingMin)
            val fresher = options.firstOrNull()?.plan
            if (fresher != null) {
                val newStops = (fresher.outcome as? PlanOutcome.Ok)?.stops.orEmpty()
                val msg = when {
                    fresher.outcome is PlanOutcome.Unreachable -> "Battery is running low — no reachable charger found. Slow down and look for the nearest charger."
                    newStops.isEmpty() -> "Using more than expected, but you can still reach the destination."
                    else -> "Using more than expected — next stop: ${newStops.first().charger.name} (${(newStops.first().charger.alongRouteM / 1000).roundToInt()} km)."
                }
                val first = newStops.firstOrNull()
                // Out of reach is an act-now (bright chime); a calmer re-plan is a normal alert.
                Notifications.alert(
                    context,
                    if (unreachable && stop != null) "${stop.charger.name} is now out of reach" else "Re-planned",
                    msg,
                    level = if (unreachable || fresher.outcome is PlanOutcome.Unreachable) Notifications.Level.Urgent else Notifications.Level.Normal,
                    actions = listOfNotNull(first?.let { Notifications.navigate(context, "Navigate there", it.charger.position) }, Notifications.show(context, "Show plan")),
                )
                next = next.copy(plan = fresher, replans = next.replans + 1, log = next.log + "Re-planned: $msg")
            }
        }
        if (!fresh && ismart.link.value is LinkState.NeedsReconnect && (t.log.lastOrNull()?.startsWith("Connection lost") != true)) {
            Notifications.alert(context, "Car connection lost", "Live re-planning is paused. Battery is estimated from distance. Tap to reconnect.")
            next = next.copy(log = next.log + "Connection lost")
        }
        _active.value = next
        interval
    }

    fun recent(limit: Int = 30) = dao.recent(limit)

    /**
     * A planned trip left "in progress" after the process died mid-trip (a reinstall, a crash, a force stop) is
     * closed here when the app comes back with no live trip: its end is the last sample we logged.
     */
    suspend fun closeStale() = mutex.withLock {
        if (_active.value != null) return@withLock
        dao.openAppTripRows().forEach { t ->
            val last = dao.samples(t.id).lastOrNull()
            dao.update(t.copy(endedAt = last?.at ?: t.startedAt, endSoc = last?.soc ?: t.startSoc, endOdometerKm = last?.odometerKm ?: t.startOdometerKm))
        }
    }

    /**
     * "Charger problem" from the car screen (or the phone) while at a stop: records the verdict for that charger's
     * rating. A broken charger is dropped and the rest of the trip is re-planned from here, with the new first
     * stop offered for navigation.
     */
    suspend fun reportChargerProblem(chargerId: String, verdict: dev.irshadm.volta.data.maps.ChargeVerdict) = mutex.withLock {
        feedback.add(chargerId, verdict)
        val t = _active.value ?: return@withLock
        val name = t.stops.firstOrNull { it.charger.id == chargerId }?.charger?.name ?: t.planned.chargers.firstOrNull { it.id == chargerId }?.name ?: "the charger"
        // Already answered: don't ask "how was it?" again after leaving.
        var next = t.copy(askedStops = t.askedStops + chargerId)
        if (verdict != dev.irshadm.volta.data.maps.ChargeVerdict.Broken) {
            _active.value = next.copy(log = next.log + "Had to wait at $name")
            return@withLock
        }
        val trip = settings.tripSettings.first()
        val vehicle = settings.vehicleProfile.first()
        val prefs = settings.chargerPreferences.first()
        val remainingMin = t.planned.plan.driveMinutes * (1 - t.alongM / t.totalM)
        val usable = t.planned.chargers.filterNot { it.id == chargerId || feedback.recentlyBroken(it.id) }
        val fresher = Replanner.replanFrom(vehicle, trip, prefs, t.planned.profile, usable, t.alongM, t.soc, remainingMin).firstOrNull()?.plan
        if (fresher == null) {
            _active.value = next.copy(log = next.log + "$name marked broken")
            return@withLock
        }
        val newStops = (fresher.outcome as? PlanOutcome.Ok)?.stops ?: (fresher.outcome as? PlanOutcome.Unreachable)?.stops.orEmpty()
        val first = newStops.firstOrNull()
        val msg = when {
            fresher.outcome is PlanOutcome.Unreachable -> "No reachable charger found from here. Slow down and look for the nearest charger."
            first == null -> "You can still reach ${t.planned.destination.name} without another stop."
            else -> "Charge at ${first.charger.name} instead (${((first.charger.alongRouteM - t.alongM) / 1000).roundToInt()} km)."
        }
        Notifications.alert(
            context,
            "$name marked broken",
            msg,
            level = if (fresher.outcome is PlanOutcome.Unreachable) Notifications.Level.Urgent else Notifications.Level.Normal,
            actions = listOfNotNull(first?.let { Notifications.navigate(context, "Navigate there", it.charger.position) }, Notifications.show(context, "Show plan")),
        )
        _active.value = next.copy(plan = fresher, replans = next.replans + 1, log = next.log + "Re-planned: $name broken. $msg")
    }

    /** Minutes of driving between two points on the route, at the profile's speeds. */
    private fun minutesAlong(profile: List<dev.irshadm.volta.core.planner.ProfilePoint>, fromM: Double, toM: Double): Double = profile.filter { it.distanceM in fromM..toM }.zipWithNext { a, b -> (b.distanceM - a.distanceM) / 1000 / b.speedKmh.coerceAtLeast(5.0) * 60 }.sum()

    private companion object {
        const val STALE_MS = 15 * 60_000L
        const val ARRIVED_M = 400.0
        const val AT_STOP_M = 400.0
        const val CHARGING_POLL_MS = 3 * 60_000L

        /** Tick interval while Android Auto is connected: the car is on, so reading it often costs nothing extra. */
        const val PROJECTED_POLL_MS = 2 * 60_000L

        /** A nudge needs at least this much progress along the route to count as movement. */
        const val NUDGE_MIN_M = 50.0

        /** Re-check a stop's plugs when it's this many minutes away. */
        const val OCCUPANCY_LEAD_MIN = 35.0
        const val LEFT_STOP_M = 1_000.0

        /** Early warnings come at 3, 6, 9 … % below plan. */
        const val GUARD_STEP = 3.0
        const val EASE_KMH = 80.0
    }
}
