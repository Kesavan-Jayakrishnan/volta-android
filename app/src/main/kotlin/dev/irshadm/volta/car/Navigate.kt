package dev.irshadm.volta.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import dev.irshadm.volta.core.planner.LatLng

/** Hands navigation to the car's default navigation app (Google Maps). Volta does no turn-by-turn itself. */
object Navigate {
    fun to(carContext: CarContext, name: String, to: LatLng) {
        val uri = Uri.parse("geo:0,0?q=${to.lat},${to.lng}(${Uri.encode(name)})")
        runCatching { carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri)) }
            .onFailure { CarToast.makeText(carContext, "Couldn't start navigation", CarToast.LENGTH_SHORT).show() }
    }
}
