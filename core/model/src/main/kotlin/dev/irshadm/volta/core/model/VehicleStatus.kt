package dev.irshadm.volta.core.model

import kotlinx.serialization.Serializable

/** A value plus the time the car produced it; every status field carries its own age. */
@Serializable
data class Timed<T>(val value: T, val atMillis: Long)

@Serializable
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Int?,
    val speedKmh: Double?,
    val headingDeg: Int?,
)

/**
 * Everything the app may know about the car, designed for the full roadmap.
 * All fields are nullable: a source fills what it can.
 */
@Serializable
data class VehicleStatus(
    val soc: Timed<SocReading>? = null,
    val pluggedIn: Timed<Boolean>? = null,
    val charging: Timed<Boolean>? = null,
    val chargePowerKw: Timed<Double>? = null,
    val chargeMinutesRemaining: Timed<Int>? = null,
    val energySinceLastChargeKwh: Timed<Double>? = null,
    val distanceSinceLastChargeKm: Timed<Double>? = null,
    val journeyId: Timed<Long>? = null,
    val journeyDistanceRaw: Timed<Int>? = null,
    val locked: Timed<Boolean>? = null,
    val body: Timed<BodyStatus>? = null,
    val lightsOn: Timed<Boolean>? = null,
    val climateRunning: Timed<Boolean>? = null,
    val ignitionOn: Timed<Boolean>? = null,
    val speedKmh: Timed<Double>? = null,
    val tyrePsi: Timed<TyrePressures>? = null,
    val auxBatteryVolts: Timed<Double>? = null,
    val interiorTempC: Timed<Double>? = null,
    val exteriorTempC: Timed<Double>? = null,
    val gps: Timed<GpsFix>? = null,
    val windows: Timed<WindowStatus>? = null,
    val lights: Timed<LightStatus>? = null,
    val handbrakeOn: Timed<Boolean>? = null,
    /** Raw i-SMART alert codes currently active (meaning not documented; shown as codes). */
    val warningCodes: Timed<List<Int>>? = null,
    /** When the car was last seen changing lock state (derived across readings). */
    val lockChangedAtMillis: Long? = null,
    /** When the car was last seen switching on/off — "parked since" / "driving since". */
    val ignitionChangedAtMillis: Long? = null,
    /** Raw charging type from the charge frame (AC vs DC; mapping to be confirmed on a real charge). */
    val chargingType: Timed<Int>? = null,
    val chargeStartedAtMillis: Long? = null,
    val kmToday: Timed<Double>? = null,
    val kwhToday: Timed<Double>? = null,
)

@Serializable
data class WindowStatus(
    val frontRightOpen: Boolean = false,
    val frontLeftOpen: Boolean = false,
    val rearLeftOpen: Boolean = false,
    val rearRightOpen: Boolean = false,
    val sunroofOpen: Boolean = false,
) {
    val anyOpen: Boolean get() = frontRightOpen || frontLeftOpen || rearLeftOpen || rearRightOpen || sunroofOpen
    val openNames: List<String> get() = listOfNotNull(
        "driver window".takeIf { frontRightOpen }, "passenger window".takeIf { frontLeftOpen },
        "rear-left window".takeIf { rearLeftOpen }, "rear-right window".takeIf { rearRightOpen }, "sunroof".takeIf { sunroofOpen },
    )
}

@Serializable
data class LightStatus(val side: Boolean = false, val dipped: Boolean = false, val main: Boolean = false) {
    val anyOn: Boolean get() = side || dipped || main
    val label: String get() = when { main -> "High beam"; dipped -> "Headlights"; side -> "Parking lights"; else -> "Off" }
}

/**
 * Openings as reported by i-SMART. India is right-hand drive, so the API's
 * `driverDoor` is the FRONT RIGHT door and `passengerDoor` the front left.
 */
@Serializable
data class BodyStatus(
    val frontRightOpen: Boolean = false,
    val frontLeftOpen: Boolean = false,
    val rearLeftOpen: Boolean = false,
    val rearRightOpen: Boolean = false,
    val bootOpen: Boolean = false,
    val bonnetOpen: Boolean = false,
) {
    val anyOpen: Boolean get() = frontRightOpen || frontLeftOpen || rearLeftOpen || rearRightOpen || bootOpen || bonnetOpen
    val openNames: List<String> get() = listOfNotNull(
        "driver door".takeIf { frontRightOpen }, "passenger door".takeIf { frontLeftOpen }, "rear-left door".takeIf { rearLeftOpen },
        "rear-right door".takeIf { rearRightOpen }, "boot".takeIf { bootOpen }, "bonnet".takeIf { bonnetOpen },
    )
}

@Serializable
data class TyrePressures(val frontLeft: Double, val frontRight: Double, val rearLeft: Double, val rearRight: Double) {
    val min: Double get() = minOf(frontLeft, frontRight, rearLeft, rearRight)
    val max: Double get() = maxOf(frontLeft, frontRight, rearLeft, rearRight)

    companion object {
        /** Below this a tyre is flagged low. Placeholder — set from the door-jamb placard (editable later). */
        const val LOW_PSI = 28.0
    }
}

/** A source of vehicle data (i-SMART today, maybe OBD later). Manual entry bypasses this. */
interface VehicleDataSource {
    val source: SocSource
    suspend fun fetchStatus(): VehicleStatus
}
