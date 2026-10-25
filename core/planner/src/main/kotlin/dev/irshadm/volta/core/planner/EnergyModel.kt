package dev.irshadm.volta.core.planner

import dev.irshadm.volta.core.model.VehicleProfile
import kotlin.math.abs

/** One sample of the route profile: where, how high, and how fast traffic is expected to move. */
data class ProfilePoint(val distanceM: Double, val elevationM: Double, val speedKmh: Double)

/** Predicted battery state at a point on the route. */
data class SocPoint(val distanceM: Double, val socPercent: Double, val elevationM: Double)

data class EnergyBreakdown(
    val flatKwh: Double,
    val climbKwh: Double,
    val regenKwh: Double,
) {
    val totalKwh: Double get() = flatKwh + climbKwh - regenKwh
    val terrainKwh: Double get() = climbKwh - regenKwh
}

/**
 * Terrain-aware energy model.
 *
 * Flat energy = distance ÷ efficiency at the segment's speed (city efficiency up to 40 km/h,
 * highway from 80 km/h, linear between). Climbing costs m·g·Δh / η_drive; descending returns
 * m·g·|Δh| · η_regen. Height changes are accumulated until they exceed [NOISE_M] so GPS/DEM
 * jitter on flat roads doesn't count as climbing.
 */
class EnergyModel(private val vehicle: VehicleProfile) {

    fun kmPerKwh(speedKmh: Double): Double = when {
        speedKmh <= CITY_KMH -> vehicle.cityKmPerKwh
        speedKmh >= HIGHWAY_KMH -> vehicle.highwayKmPerKwh
        else -> {
            val t = (speedKmh - CITY_KMH) / (HIGHWAY_KMH - CITY_KMH)
            vehicle.cityKmPerKwh + (vehicle.highwayKmPerKwh - vehicle.cityKmPerKwh) * t
        }
    }

    private fun potentialKwh(dhM: Double) = vehicle.massKg * G * dhM / JOULES_PER_KWH

    /** Energy for the whole profile, split into flat, climb and regen parts. */
    fun breakdown(points: List<ProfilePoint>): EnergyBreakdown {
        var flat = 0.0
        var climb = 0.0
        var regen = 0.0
        var pendingDh = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            flat += (b.distanceM - a.distanceM) / 1000.0 / kmPerKwh((a.speedKmh + b.speedKmh) / 2)
            pendingDh += b.elevationM - a.elevationM
            if (abs(pendingDh) >= NOISE_M) {
                if (pendingDh > 0) climb += potentialKwh(pendingDh) / vehicle.driveEfficiency
                else regen += potentialKwh(-pendingDh) * vehicle.regenEfficiency
                pendingDh = 0.0
            }
        }
        return EnergyBreakdown(flat, climb, regen)
    }

    /**
     * SoC along the route starting from [startSoc]. Regen never lifts the battery above [capSoc];
     * SoC may go below zero so callers can see how far short a plan falls.
     */
    fun simulate(points: List<ProfilePoint>, startSoc: Double, capSoc: Double = 100.0): List<SocPoint> {
        if (points.isEmpty()) return emptyList()
        val perPercent = vehicle.usableCapacityKwh / 100.0
        var soc = startSoc
        var pendingDh = 0.0
        val out = ArrayList<SocPoint>(points.size)
        out += SocPoint(points[0].distanceM, soc, points[0].elevationM)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            var kwh = (b.distanceM - a.distanceM) / 1000.0 / kmPerKwh((a.speedKmh + b.speedKmh) / 2)
            pendingDh += b.elevationM - a.elevationM
            if (abs(pendingDh) >= NOISE_M) {
                kwh += if (pendingDh > 0) potentialKwh(pendingDh) / vehicle.driveEfficiency
                else -potentialKwh(-pendingDh) * vehicle.regenEfficiency
                pendingDh = 0.0
            }
            soc = minOf(capSoc, soc - kwh / perPercent)
            out += SocPoint(b.distanceM, soc, b.elevationM)
        }
        return out
    }

    companion object {
        const val CITY_KMH = 40.0
        const val HIGHWAY_KMH = 80.0
        const val NOISE_M = 2.0
        private const val G = 9.81
        private const val JOULES_PER_KWH = 3.6e6
    }
}
