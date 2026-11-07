package dev.irshadm.volta.data

import dev.irshadm.volta.core.model.SocReading
import dev.irshadm.volta.core.model.SocSource
import dev.irshadm.volta.data.db.SocReadingDao
import dev.irshadm.volta.data.db.SocReadingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SocRepository @Inject constructor(private val dao: SocReadingDao) {

    val latest: Flow<SocReading?> = dao.observeLatest().map { it?.toModel() }

    fun recent(limit: Int = 20): Flow<List<SocReading>> = dao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    /** A manual reading overrides older cloud data simply by being newer. */
    suspend fun addManual(socPercent: Int, nowMillis: Long = System.currentTimeMillis()) {
        dao.insert(SocReadingEntity.from(SocReading(socPercent.toDouble(), SocSource.MANUAL, nowMillis)))
    }

    /** Stores a reading unless it is identical to the last one (same car timestamp and values). */
    suspend fun add(reading: SocReading) {
        val last = latest.first()
        if (last != null && last.measuredAtMillis == reading.measuredAtMillis && last.socPercent == reading.socPercent &&
            last.odometerKm == reading.odometerKm
        ) {
            return
        }
        dao.insert(SocReadingEntity.from(reading))
    }
}
