package dev.irshadm.volta.car

import android.content.Context
import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.data.ismart.IsmartRepository
import dev.irshadm.volta.data.ismart.LinkState
import dev.irshadm.volta.data.maps.ChargerFeedback
import dev.irshadm.volta.data.maps.GoogleMapsClient
import dev.irshadm.volta.data.trip.ActiveTrip
import dev.irshadm.volta.data.trip.LocationProvider
import dev.irshadm.volta.data.trip.TripRepository
import dev.irshadm.volta.service.Alerts
import dev.irshadm.volta.core.model.TripSettings
import dev.irshadm.volta.core.model.VehicleProfile
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.planner.PlanOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the car screens read: the live trip, the car (as on the phone's dashboard), your charger
 * history and settings, folded into one flow. Screens map a [CarState] to a UI model with [CarModels] and only
 * call invalidate() when that model changes, so the host's refresh budget isn't spent on ticks that change nothing.
 */
@Singleton
class CarDeps @Inject constructor(
    @ApplicationContext val context: Context,
    val trips: TripRepository,
    val ismart: IsmartRepository,
    private val settings: SettingsRepository,
    val feedback: ChargerFeedback,
    val maps: GoogleMapsClient,
    val location: LocationProvider,
    val inCar: InCarRefresh,
) {
    data class CarState(
        val trip: ActiveTrip?,
        val status: VehicleStatus?,
        val signedIn: Boolean,
        val linkDown: Boolean,
        val vehicle: VehicleProfile,
        val tripSettings: TripSettings,
        val feedbackVersion: Int,
    )

    /** Work that must outlive a screen (a "charger problem" report after the screen pops). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class Car(val status: VehicleStatus?, val signedIn: Boolean, val linkDown: Boolean)

    private val car = combine(ismart.status, ismart.link) { live, link ->
        Car(live, link !is LinkState.SignedOut, link is LinkState.NeedsReconnect)
    }

    val state: Flow<CarState> = combine(trips.active, car, feedback.version, settings.vehicleProfile, settings.tripSettings) { trip, car, version, vehicle, tripSettings ->
        CarState(trip, car.status, car.signedIn, car.linkDown, vehicle, tripSettings, version)
    }

    fun facts(s: CarState, now: Long = System.currentTimeMillis()): TripFacts? {
        val t = s.trip ?: return null
        val original = ((t.planned.plan.outcome as? PlanOutcome.Ok)?.stops ?: (t.planned.plan.outcome as? PlanOutcome.Unreachable)?.stops.orEmpty()).map { it.charger.id }.toSet()
        return TripFacts(
            destination = t.planned.destination.name,
            destinationPosition = t.planned.destination.position,
            stops = t.stops,
            originalStopIds = original,
            chargers = t.planned.chargers,
            profile = t.planned.profile,
            alongM = t.alongM,
            totalM = t.totalM,
            soc = t.soc,
            expectedSoc = t.expectedSoc,
            plannedArrivalSoc = (t.plan.outcome as? PlanOutcome.Ok)?.arrivalSoc,
            socEstimated = t.socEstimated,
            visited = t.visitedStops,
            ready = t.readyStops,
            lastLog = t.log.lastOrNull(),
            charging = s.status?.charging?.value == true,
            vehicle = s.vehicle,
            minArrivalPercent = s.tripSettings.minArrivalAtChargerPercent.toDouble(),
            history = feedback::history,
            now = now,
        )
    }

    fun home(s: CarState, now: Long = System.currentTimeMillis()): HomeFacts {
        val v = s.status
        val soc = v?.soc?.value?.socPercent
        val ignitionOn = v?.ignitionOn?.value == true
        return HomeFacts(
            signedIn = s.signedIn,
            soc = soc,
            // Volta's expected range, the same figure as the phone dashboard and widget (midpoint of the city and
            // highway estimates), not MG's own range reading.
            rangeKm = soc?.let { (s.vehicle.highwayRangeKm(it) + s.vehicle.cityRangeKm(it)) / 2 },
            charging = v?.charging?.value == true,
            pluggedIn = v?.pluggedIn?.value == true,
            chargeMinutesLeft = v?.chargeMinutesRemaining?.value,
            ignitionOn = ignitionOn,
            parkedSince = if (ignitionOn) null else v?.ignitionChangedAtMillis,
            rangeWatchOn = Alerts.isOn(context, "rangewatch"),
            linkDown = s.linkDown,
            lastReadingAt = v?.soc?.value?.measuredAtMillis,
            now = now,
        )
    }
}
