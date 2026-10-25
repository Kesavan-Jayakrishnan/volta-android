package dev.irshadm.volta.core.model

/** One point on the DC charging curve: charging power available at a given SoC. */
data class ChargeCurvePoint(val socPercent: Int, val powerKw: Double)

/**
 * Energy model inputs for the car.
 *
 * Defaults are for the MG Windsor 38 kWh. Usable capacity was measured on a real car
 * (21.9 kWh at 59 %); efficiencies are starting points that calibration
 * replaces with learned values.
 */
data class VehicleProfile(
    val usableCapacityKwh: Double = 37.1,
    val cityKmPerKwh: Double = 7.0,
    val highwayKmPerKwh: Double = 6.0,
    val massKg: Double = 1600.0,
    val driveEfficiency: Double = 0.88,
    val regenEfficiency: Double = 0.60,
    val dcCurve: List<ChargeCurvePoint> = WINDSOR_38_DC_CURVE,
) {
    fun energyAt(socPercent: Double): Double = usableCapacityKwh * socPercent.coerceIn(0.0, 100.0) / 100.0

    /** Flat-road range from [socPercent] down to [reservePercent] at the given efficiency. */
    fun rangeKm(socPercent: Double, kmPerKwh: Double, reservePercent: Double = 0.0): Double =
        (energyAt(socPercent) - energyAt(reservePercent)).coerceAtLeast(0.0) * kmPerKwh

    fun cityRangeKm(socPercent: Double) = rangeKm(socPercent, cityKmPerKwh)
    fun highwayRangeKm(socPercent: Double) = rangeKm(socPercent, highwayKmPerKwh)

    /** Returns a list of human-readable problems; empty when the profile is usable. */
    fun validate(): List<String> = buildList {
        if (usableCapacityKwh !in 10.0..120.0) add("Usable capacity must be 10–120 kWh")
        if (cityKmPerKwh !in 2.0..15.0) add("City efficiency must be 2–15 km/kWh")
        if (highwayKmPerKwh !in 2.0..15.0) add("Highway efficiency must be 2–15 km/kWh")
        if (massKg !in 800.0..3500.0) add("Mass must be 800–3500 kg")
        if (driveEfficiency !in 0.5..1.0) add("Drive efficiency must be 0.5–1.0")
        if (regenEfficiency !in 0.0..0.9) add("Regen efficiency must be 0–0.9")
        if (dcCurve.size < 2 || dcCurve.zipWithNext().any { (a, b) -> b.socPercent <= a.socPercent }) {
            add("Charging curve needs at least 2 points in increasing SoC order")
        }
    }

    companion object {
        /** Assumed curve (~45 kW peak, tapering above 50 %); calibrated from charge sessions later. */
        val WINDSOR_38_DC_CURVE = listOf(
            ChargeCurvePoint(0, 40.0),
            ChargeCurvePoint(10, 45.0),
            ChargeCurvePoint(50, 45.0),
            ChargeCurvePoint(65, 35.0),
            ChargeCurvePoint(80, 25.0),
            ChargeCurvePoint(90, 12.0),
            ChargeCurvePoint(100, 5.0),
        )
    }
}
