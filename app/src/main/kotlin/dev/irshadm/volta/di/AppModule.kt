package dev.irshadm.volta.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dev.irshadm.volta.data.db.AppDatabase
import dev.irshadm.volta.data.db.SocReadingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "volta.db").addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()

    @Provides
    fun socReadingDao(db: AppDatabase): SocReadingDao = db.socReadingDao()

    @Provides
    fun tripDao(db: AppDatabase): dev.irshadm.volta.data.db.TripDao = db.tripDao()

    @Provides
    fun chargeDao(db: AppDatabase): dev.irshadm.volta.data.db.ChargeDao = db.chargeDao()

    @Provides
    @Singleton
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
}
