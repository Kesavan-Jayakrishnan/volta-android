package dev.irshadm.volta.ui.components

import dev.irshadm.volta.R
import dev.irshadm.volta.data.ismart.CarModel

/** Which picture of the car to show, by model and state. Each model has one angle; only the Windsor also has a 360° view. */
object CarPictures {
    // In CarShot's order: locked, unlocked, charging, lights.
    private val models = mapOf(
        CarModel.Comet to listOf(R.drawable.model_comet_locked, R.drawable.model_comet_unlocked, R.drawable.model_comet_charging, R.drawable.model_comet_lights),
        CarModel.ZsEv to listOf(R.drawable.model_zs_locked, R.drawable.model_zs_unlocked, R.drawable.model_zs_charging, R.drawable.model_zs_lights),
        CarModel.M9 to listOf(R.drawable.model_m9_locked, R.drawable.model_m9_unlocked, R.drawable.model_m9_charging, R.drawable.model_m9_lights),
        CarModel.Cyberster to listOf(R.drawable.model_cyberster_locked, R.drawable.model_cyberster_unlocked, R.drawable.model_cyberster_charging, R.drawable.model_cyberster_lights),
        CarModel.Tomahawk to listOf(R.drawable.model_tomahawk_locked, R.drawable.model_tomahawk_unlocked, R.drawable.model_tomahawk_charging, R.drawable.model_tomahawk_lights),
    )

    /** The dashboard's picture. A model without its own shows the Windsor. */
    fun hero(model: CarModel, shot: CarShot): Int = models[model]?.get(shot.ordinal) ?: shot.res

    /** The picture for small places outside the dashboard: widgets, quick actions, notifications. */
    fun small(model: CarModel, shot: CarShot): Int = models[model]?.get(shot.ordinal) ?: shot.frame
}
