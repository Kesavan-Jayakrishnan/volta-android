package dev.irshadm.volta.core.model

import kotlinx.serialization.Serializable

enum class SocSource { MANUAL, ISMART }

/** A single state-of-charge observation, stored as a time series. */
@Serializable
data class SocReading(
    val socPercent: Double,
    val source: SocSource,
    val measuredAtMillis: Long,
    val batteryEnergyKwh: Double? = null,
    val carRangeKm: Double? = null,
    val odometerKm: Double? = null,
) {
    init {
        require(socPercent in 0.0..100.0) { "SoC must be 0–100 %, was $socPercent" }
    }

    fun ageMinutes(nowMillis: Long): Long = ((nowMillis - measuredAtMillis).coerceAtLeast(0)) / 60_000
}
