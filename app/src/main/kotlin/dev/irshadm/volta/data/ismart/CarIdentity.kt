package dev.irshadm.volta.data.ismart

import android.content.Context
import dev.irshadm.volta.core.ismart.Vehicle

internal const val CAR_PREFS = "car"
internal const val CAR_NAME = "car.name"

/**
 * The models Volta has pictures of. MG's model name can carry the trim ("COMET EV EXCLUSIVE FC") and its series can be
 * a code ("E230"), so [pattern] is tried against both. The ZS Astor is a petrol car, not the ZS EV.
 */
enum class CarModel(val label: String?, private val pattern: Regex?) {
    Windsor("Windsor", Regex("windsor|\\bEQ100\\b", RegexOption.IGNORE_CASE)),
    Comet("Comet EV", Regex("comet|\\bEC32\\b|\\bE230\\b", RegexOption.IGNORE_CASE)),
    ZsEv("ZS EV", Regex("^(?!.*astor).*\\bzs\\b", RegexOption.IGNORE_CASE)),
    M9("M9", Regex("\\bm9\\b", RegexOption.IGNORE_CASE)),
    Cyberster("Cyberster", Regex("cyberster", RegexOption.IGNORE_CASE)),
    Tomahawk("Hector Tomahawk EV", Regex("tomahawk", RegexOption.IGNORE_CASE)),
    Other(null, null);

    companion object {
        fun of(text: String): CarModel = entries.firstOrNull { it.pattern?.containsMatchIn(text) == true } ?: Other
    }
}

/**
 * Which MG the signed-in account drives, from the vehicle list MG returns at sign-in. Volta works with any MG EV on
 * i-SMART; the picture follows the [model], and the Windsor also has a 360° view.
 */
data class CarIdentity(val name: String) {
    val model: CarModel get() = CarModel.of(name)

    companion object {
        /** Before the first sign-in, or when MG sent no model. */
        val Unknown = CarIdentity("Your MG")

        /** The car saved at sign-in, for places that run outside the app's screens: widgets and notifications. */
        fun saved(context: Context): CarIdentity =
            context.getSharedPreferences(CAR_PREFS, Context.MODE_PRIVATE).getString(CAR_NAME, null)?.let(::CarIdentity) ?: Unknown

        /** "COMET EV EXCLUSIVE FC" → "Comet EV". A model Volta doesn't know keeps MG's name, without the brand. */
        fun of(vehicle: Vehicle?): CarIdentity {
            val raw = listOfNotNull(vehicle?.model, vehicle?.series).firstOrNull { it.isNotBlank() } ?: return Unknown
            CarModel.of("${vehicle?.model.orEmpty()} ${vehicle?.series.orEmpty()}").label?.let { return CarIdentity(it) }
            return CarIdentity(raw.trim().removePrefix("MG ").removePrefix("mg ").trim().ifBlank { return Unknown })
        }
    }
}
