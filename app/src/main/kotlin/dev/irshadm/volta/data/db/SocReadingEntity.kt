package dev.irshadm.volta.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.irshadm.volta.core.model.SocReading
import dev.irshadm.volta.core.model.SocSource

@Entity(tableName = "soc_readings", indices = [Index("measuredAtMillis")])
data class SocReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val socPercent: Double,
    val source: String,
    val measuredAtMillis: Long,
    val batteryEnergyKwh: Double?,
    val carRangeKm: Double?,
    val odometerKm: Double?,
    val demo: Boolean = false,             // unused: kept so existing databases need no migration
) {
    fun toModel() = SocReading(
        socPercent = socPercent,
        source = SocSource.valueOf(source),
        measuredAtMillis = measuredAtMillis,
        batteryEnergyKwh = batteryEnergyKwh,
        carRangeKm = carRangeKm,
        odometerKm = odometerKm,
    )

    companion object {
        fun from(r: SocReading) = SocReadingEntity(
            socPercent = r.socPercent,
            source = r.source.name,
            measuredAtMillis = r.measuredAtMillis,
            batteryEnergyKwh = r.batteryEnergyKwh,
            carRangeKm = r.carRangeKm,
            odometerKm = r.odometerKm,
        )
    }
}
