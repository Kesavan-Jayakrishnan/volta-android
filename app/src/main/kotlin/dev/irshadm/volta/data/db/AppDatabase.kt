package dev.irshadm.volta.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SocReadingEntity::class, TripEntity::class, TripSampleEntity::class, ChargeSessionEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun socReadingDao(): SocReadingDao
    abstract fun tripDao(): TripDao
    abstract fun chargeDao(): ChargeDao

    companion object {
        /** v2 adds trips, trip samples and charging sessions; keeps every stored battery reading. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `trips` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `destination` TEXT, `plannedDistanceM` REAL, `startSoc` REAL, `endSoc` REAL, `startOdometerKm` REAL, `endOdometerKm` REAL, `plannedKwh` REAL, `usedKwh` REAL, `climbM` REAL, `source` TEXT NOT NULL, `journeyId` INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `trip_samples` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tripId` INTEGER NOT NULL, `at` INTEGER NOT NULL, `lat` REAL, `lng` REAL, `altitudeM` REAL, `alongRouteM` REAL, `soc` REAL, `expectedSoc` REAL, `odometerKm` REAL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_samples_tripId` ON `trip_samples` (`tripId`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `charge_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `lat` REAL, `lng` REAL, `startSoc` REAL, `endSoc` REAL, `startKwh` REAL, `endKwh` REAL, `peakKw` REAL NOT NULL, `category` TEXT NOT NULL, `placeName` TEXT, `gridUnits` REAL, `costRs` REAL, `inferred` INTEGER NOT NULL, `userEdited` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_sessions_startedAt` ON `charge_sessions` (`startedAt`)")
            }
        }

        /** v3 added a `demo` flag to three tables. Nothing sets it any more; the columns stay so no migration is needed. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `soc_readings` ADD COLUMN `demo` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trips` ADD COLUMN `demo` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `charge_sessions` ADD COLUMN `demo` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
