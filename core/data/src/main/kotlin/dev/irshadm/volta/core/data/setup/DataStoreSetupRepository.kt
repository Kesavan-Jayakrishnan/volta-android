package dev.irshadm.volta.core.data.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.irshadm.volta.core.domain.SetupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreSetupRepository(private val store: DataStore<Preferences>) : SetupRepository {
    override val mapsKeyStepDone: Flow<Boolean> = store.data.map { it[MAPS_KEY_STEP_DONE] ?: false }

    override suspend fun setMapsKeyStepDone(done: Boolean) {
        store.edit { it[MAPS_KEY_STEP_DONE] = done }
    }

    private companion object {
        val MAPS_KEY_STEP_DONE = booleanPreferencesKey("setup.maps_key_step_done")
    }
}
