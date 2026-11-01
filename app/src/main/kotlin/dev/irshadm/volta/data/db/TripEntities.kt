package dev.irshadm.volta.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One drive. Started from the planner or detected from the car's journey id. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val destination: String?,
    val plannedDistanceM: Double?,
    val startSoc: Double?,
    val endSoc: Double? = null,
    val startOdometerKm: Double? = null,
    val endOdometerKm: Double? = null,
    val plannedKwh: Double? = null,
    val usedKwh: Double? = null,
    val climbM: Double? = null,
    val source: String = "APP",            // APP = tracked by Volta, CAR = detected from journey id
    val journeyId: Long? = null,
    val demo: Boolean = false,             // unused: kept so existing databases need no migration
)

@Entity(tableName = "trip_samples", indices = [Index("tripId")])
data class TripSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val at: Long,
    val lat: Double?,
    val lng: Double?,
    val altitudeM: Double?,
    val alongRouteM: Double?,
    val soc: Double?,
    val expectedSoc: Double?,
    val odometerKm: Double?,
)

/** A charging session. Category: HOME, PUBLIC_AC, PUBLIC_DC. */
@Entity(tableName = "charge_sessions", indices = [Index("startedAt")])
data class ChargeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val lat: Double?,
    val lng: Double?,
    val startSoc: Double?,
    val endSoc: Double? = null,
    val startKwh: Double?,
    val endKwh: Double? = null,
    val peakKw: Double = 0.0,
    val category: String,
    val placeName: String? = null,
    val gridUnits: Double? = null,         // user-entered actual units (smart plug / meter)
    val costRs: Double? = null,            // user-entered actual cost (receipt)
    val inferred: Boolean = false,
    val userEdited: Boolean = false,
    val demo: Boolean = false,             // unused: kept so existing databases need no migration
)

@Dao
interface TripDao {
    @Insert suspend fun insert(trip: TripEntity): Long
    @Update suspend fun update(trip: TripEntity)
    @Insert suspend fun insertSample(sample: TripSampleEntity)
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun get(id: Long): TripEntity?
    @Query("SELECT * FROM trips ORDER BY startedAt DESC LIMIT :limit") fun recent(limit: Int): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE endedAt IS NOT NULL AND usedKwh IS NOT NULL ORDER BY startedAt DESC LIMIT :limit") suspend fun finished(limit: Int): List<TripEntity>
    @Query("SELECT * FROM trip_samples WHERE tripId = :tripId ORDER BY at") suspend fun samples(tripId: Long): List<TripSampleEntity>
    @Query("SELECT * FROM trips WHERE source = 'CAR' AND journeyId = :journeyId LIMIT 1") suspend fun byJourney(journeyId: Long): TripEntity?
    @Query("SELECT * FROM trips WHERE source = 'CAR' ORDER BY startedAt DESC LIMIT 1") suspend fun lastCarTrip(): TripEntity?
    @Query("SELECT * FROM trips WHERE source = 'CAR' ORDER BY startedAt") suspend fun carTrips(): List<TripEntity>
    @Query("DELETE FROM trips WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM trips WHERE source = 'APP' AND endedAt IS NULL AND startedAt > :since") suspend fun openAppTrips(since: Long): Int
    @Query("SELECT * FROM trips WHERE source = 'APP' AND endedAt IS NULL") suspend fun openAppTripRows(): List<TripEntity>
}

@Dao
interface ChargeDao {
    @Insert suspend fun insert(s: ChargeSessionEntity): Long
    @Update suspend fun update(s: ChargeSessionEntity)
    @Query("SELECT * FROM charge_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun open(): ChargeSessionEntity?
    @Query("SELECT * FROM charge_sessions WHERE startedAt BETWEEN :from AND :to ORDER BY startedAt DESC") fun between(from: Long, to: Long): Flow<List<ChargeSessionEntity>>
    @Query("SELECT * FROM charge_sessions ORDER BY startedAt DESC LIMIT :limit") fun recent(limit: Int): Flow<List<ChargeSessionEntity>>
}
