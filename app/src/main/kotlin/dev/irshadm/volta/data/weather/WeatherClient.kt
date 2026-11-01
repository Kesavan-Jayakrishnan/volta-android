package dev.irshadm.volta.data.weather

import dev.irshadm.volta.data.SettingsRepository
import dev.irshadm.volta.core.model.VehicleStatus
import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/** Air temperature and sunshine right now at one spot; [sheltered] when that spot is covered parking (no sun on the car). */
data class Weather(val tempC: Double, val sunWm2: Double, val day: Boolean, val atMillis: Long, val sheltered: Boolean = false)

/**
 * Current weather from Open-Meteo (free, no key). The car only reports its temperatures when it's switched
 * off and never updates them while parked, so the outside temperature comes from here instead.
 * Answers are kept for 15 minutes per ~1 km square.
 */
@Singleton
class WeatherClient @Inject constructor(private val http: OkHttpClient, private val settings: SettingsRepository) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, Weather>()

    suspend fun now(lat: Double, lng: Double): Weather? = fetch(lat, lng)?.let { it.copy(sheltered = coveredHome(lat, lng)) }

    /** Inside the home area with "Home parking is covered" on. */
    private suspend fun coveredHome(lat: Double, lng: Double): Boolean {
        if (!settings.homeCovered.first()) return false
        val e = settings.energySettings.first()
        val home = LatLng(e.homeLat ?: return false, e.homeLng ?: return false)
        return Geo.distanceM(home, LatLng(lat, lng)) <= e.homeRadiusM
    }

    private suspend fun fetch(lat: Double, lng: Double): Weather? {
        val key = "%.2f,%.2f".format(lat, lng)
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.atMillis < CACHE_MS }?.let { return it } }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,shortwave_radiation,is_day".format(lat, lng)
        val w = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val c = json.decodeFromString(Reply.serializer(), r.body!!.string()).current
                    Weather(c.temp, c.sun ?: 0.0, c.isDay == 1, System.currentTimeMillis())
                }
            }.getOrNull()
        } ?: return null
        synchronized(cache) { cache[key] = w }
        return w
    }

    @Serializable private data class Reply(val current: Current)
    @Serializable private data class Current(
        @SerialName("temperature_2m") val temp: Double,
        @SerialName("shortwave_radiation") val sun: Double? = null,
        @SerialName("is_day") val isDay: Int = 1,
    )

    private companion object { const val CACHE_MS = 15 * 60_000L }
}

/** Outside and cabin temperatures to show and to decide on pre-cooling; [cabinEstimated] when the cabin is worked out, not measured. */
data class Temps(val outsideC: Double?, val cabinC: Double?, val cabinEstimated: Boolean)

object CabinModel {
    /**
     * How much hotter than the air a closed cabin gets in full sun (~900 W/m²). Kept modest: much of the time the
     * car is under a roof, a tree or in morning sun, and an over-warm guess makes the AC look needed when it isn't.
     */
    private const val FULL_SUN_GAIN_C = 10.0
    /** Minutes for the cabin to get most of the way to its new temperature. */
    private const val SETTLE_MIN = 60.0

    /**
     * While the car is on, its own sensors are live. Once parked, the cabin drifts from the last reading the car
     * gave (when it was switched off) towards the air temperature plus the sun's heating (none under a roof).
     */
    fun temps(v: VehicleStatus?, weather: Weather?, now: Long = System.currentTimeMillis()): Temps {
        val carCabin = v?.interiorTempC?.value
        val running = v?.ignitionOn?.value == true || v?.climateRunning?.value == true
        val outside = weather?.tempC ?: v?.exteriorTempC?.value
        if (running || weather == null) return Temps(outside, carCabin, false)
        val sun = if (weather.sheltered) 0.0 else weather.sunWm2
        val target = weather.tempC + FULL_SUN_GAIN_C * (sun / 900.0).coerceIn(0.0, 1.1)
        val parkedMin = v?.ignitionChangedAtMillis?.let { (now - it) / 60_000.0 }?.coerceAtLeast(0.0)
        val start = carCabin ?: target
        val cabin = if (parkedMin == null) target else target + (start - target) * exp(-parkedMin / SETTLE_MIN)
        return Temps(outside, cabin, true)
    }
}
