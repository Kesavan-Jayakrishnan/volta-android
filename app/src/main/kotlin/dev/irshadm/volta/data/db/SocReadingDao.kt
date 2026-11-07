package dev.irshadm.volta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SocReadingDao {
    @Insert
    suspend fun insert(reading: SocReadingEntity): Long

    @Query("SELECT * FROM soc_readings ORDER BY measuredAtMillis DESC LIMIT 1")
    fun observeLatest(): Flow<SocReadingEntity?>

    @Query("SELECT MAX(odometerKm) - MIN(odometerKm) FROM soc_readings WHERE odometerKm IS NOT NULL AND measuredAtMillis BETWEEN :from AND :to")
    fun kmBetween(from: Long, to: Long): Flow<Double?>

    // The two below feed DriveLogger's one-off rebuilds of car drives.
    @Query("SELECT * FROM soc_readings WHERE odometerKm IS NOT NULL AND demo = 0 ORDER BY measuredAtMillis ASC")
    suspend fun allWithOdometer(): List<SocReadingEntity>

    @Query("SELECT * FROM soc_readings WHERE odometerKm IS NOT NULL AND demo = 0 AND measuredAtMillis BETWEEN :from AND :to ORDER BY measuredAtMillis ASC")
    suspend fun withOdometerBetween(from: Long, to: Long): List<SocReadingEntity>

    /** The last real reading before [t]: the battery just before a charge began. */
    @Query("SELECT * FROM soc_readings WHERE demo = 0 AND measuredAtMillis < :t ORDER BY measuredAtMillis DESC LIMIT 1")
    suspend fun lastBefore(t: Long): SocReadingEntity?

    /** The fullest real reading in [from, to): where a charge that was never seen to end got to. */
    @Query("SELECT * FROM soc_readings WHERE demo = 0 AND measuredAtMillis >= :from AND measuredAtMillis < :to ORDER BY socPercent DESC, measuredAtMillis DESC LIMIT 1")
    suspend fun highestBetween(from: Long, to: Long): SocReadingEntity?

    @Query("SELECT * FROM soc_readings ORDER BY measuredAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SocReadingEntity>>
}
