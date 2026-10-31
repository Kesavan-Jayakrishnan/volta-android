package dev.irshadm.volta.core.domain

import kotlinx.coroutines.flow.Flow

/** First-run progress that isn't a credential: whether the Google key step was finished or skipped. */
interface SetupRepository {
    val mapsKeyStepDone: Flow<Boolean>

    suspend fun setMapsKeyStepDone(done: Boolean)
}
