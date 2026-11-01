package dev.irshadm.volta.data.ismart

import dev.irshadm.volta.core.ismart.Vehicle

/**
 * Which MG the signed-in account drives, from the vehicle list MG returns at sign-in. Volta works with any MG EV on
 * i-SMART; the Windsor additionally gets its own photos and 360° view, everything else a generic car.
 */
data class CarIdentity(val name: String) {
    val isWindsor: Boolean get() = name.contains("windsor", ignoreCase = true)

    companion object {
        /** Before the first sign-in, or when MG sent no model. */
        val Unknown = CarIdentity("Your MG")

        /** "MG Windsor EV" → "Windsor EV": the brand is implied everywhere the name is shown. */
        fun of(vehicle: Vehicle?): CarIdentity {
            val raw = listOfNotNull(vehicle?.model, vehicle?.series).firstOrNull { it.isNotBlank() } ?: return Unknown
            return CarIdentity(raw.trim().removePrefix("MG ").removePrefix("mg ").trim().ifBlank { return Unknown })
        }
    }
}
