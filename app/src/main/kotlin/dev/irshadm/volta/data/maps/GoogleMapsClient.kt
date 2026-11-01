package dev.irshadm.volta.data.maps

import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import dev.irshadm.volta.core.planner.Meal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import dev.irshadm.volta.core.maps.MapsKeyProvider
import dev.irshadm.volta.core.maps.MissingMapsKeyException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MapsApiException(message: String) : Exception(message)

/**
 * Identity Google checks against the key's Android-app restriction. [sha1Hex] is the certificate Android says signs
 * the app now; [fallbacks] are any others it reports for the package (past signers), tried in turn when Google
 * refuses the first, since on some phones the reported signer isn't the one Play signed the APK with.
 */
data class AppIdentity(val packageName: String, val sha1Hex: String, val fallbacks: List<String> = emptyList()) {
    val candidates: List<String> get() = (listOf(sha1Hex) + fallbacks).distinct()
}

/**
 * Thin client for the four Google APIs the planner uses. Field masks are kept minimal so each
 * call stays in the cheapest SKU that has the data.
 */
class GoogleMapsClient(
    private val http: OkHttpClient,
    /** The user's own key, read on every request so a key added or changed in Settings works straight away. */
    private val keys: MapsKeyProvider,
    private val identity: AppIdentity,
    private val placesBase: String = "https://places.googleapis.com/v1",
    private val routesBase: String = "https://routes.googleapis.com",
    private val elevationBase: String = "https://maps.googleapis.com/maps/api/elevation/json",
) {
    /** Fails with a message that says where to add the key, rather than letting Google answer "API key missing". */
    private fun apiKey(): String = keys.currentKey()?.takeIf { it.isNotBlank() } ?: throw MissingMapsKeyException()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    /** The certificate Google last accepted; starts with the one Android reports. */
    @Volatile private var cert: String = identity.sha1Hex

    private fun request(url: String, fieldMask: String? = null) = Request.Builder().url(url)
        .header("X-Goog-Api-Key", apiKey())
        .header("X-Android-Package", identity.packageName)
        .header("X-Android-Cert", cert)
        .apply { fieldMask?.let { header("X-Goog-FieldMask", it) } }

    /** A 403 that names this app means the key refused the package + certificate pair, not the request itself. */
    private class IdentityRefused(message: String) : Exception(message)

    private suspend fun execute(req: Request): String {
        try {
            return executeWithRetry(req)
        } catch (e: IdentityRefused) {
            // Try the other certificates Android knows for this app; keep the one that works for later calls.
            for (c in identity.candidates.filter { it != req.header("X-Android-Cert") }) {
                val retry = req.newBuilder().header("X-Android-Cert", c).build()
                val body = runCatching { executeWithRetry(retry) }.getOrElse { if (it is IdentityRefused) null else throw it }
                if (body != null) { cert = c; return body }
            }
            val sent = req.header("X-Android-Cert").orEmpty()
            throw MapsApiException("${e.message} (sent ${identity.packageName} · ${sent.chunked(2).take(4).joinToString(":")}…, ${identity.candidates.size} certificate(s) tried)")
        }
    }

    private suspend fun executeWithRetry(req: Request): String {
        var attempt = 0
        while (true) {
            try {
                return executeOnce(req)
            } catch (e: TransientException) {
                if (++attempt >= 3) throw MapsApiException(e.message ?: "Google service unavailable")
                kotlinx.coroutines.delay(800L * attempt)
            }
        }
    }

    private class TransientException(message: String) : Exception(message)

    private suspend fun executeOnce(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (res.code == 429 && "per day" in body) throw MapsApiException(DAILY_LIMIT)
            if (res.code >= 500 || res.code == 429) throw TransientException("HTTP ${res.code}")
            if (!res.isSuccessful) {
                val msg = runCatching { json.decodeFromString<GoogleError>(body).error?.message }.getOrNull()
                if (res.code == 403 && msg != null && "Android client application" in msg) throw IdentityRefused("HTTP 403: $msg")
                throw MapsApiException("HTTP ${res.code}: ${msg ?: body.take(200)}")
            }
            body
        }
    }

    private suspend fun post(url: String, body: JsonObject, fieldMask: String?): String =
        execute(request(url, fieldMask).post(body.toString().toRequestBody(jsonType)).build())

    // ---- Places Autocomplete (Essentials) ----

    /** Places matching [input], nearest to [near] first (Google ranks by prominence; "Lulu Mall" should be the one in town). */
    suspend fun autocomplete(input: String, sessionToken: String, near: LatLng?): List<PlaceSuggestion> {
        val body = buildJsonObject {
            put("input", input)
            put("sessionToken", sessionToken)
            putJsonArray("includedRegionCodes") { add("in") }
            near?.let {
                putJsonObject("locationBias") {
                    putJsonObject("circle") {
                        putJsonObject("center") { put("latitude", it.lat); put("longitude", it.lng) }
                        put("radius", 50_000.0)
                    }
                }
                putJsonObject("origin") { put("latitude", it.lat); put("longitude", it.lng) }
            }
        }
        val res = json.decodeFromString<AutocompleteResponse>(post("$placesBase/places:autocomplete", body, null))
        return res.suggestions.mapNotNull { s ->
            s.placePrediction?.let {
                PlaceSuggestion(it.placeId, it.structuredFormat?.mainText?.text ?: it.text.text, it.structuredFormat?.secondaryText?.text, it.distanceMeters?.toDouble())
            }
        }.sortedBy { it.distanceM ?: Double.MAX_VALUE }
    }

    /** Place Details Essentials — just the coordinates and name; ends the autocomplete session. */
    suspend fun placeLocation(placeId: String, sessionToken: String): NamedPlace {
        val url = "$placesBase/places/$placeId".toHttpUrl().newBuilder().addQueryParameter("sessionToken", sessionToken).build()
        val p = json.decodeFromString<PlaceDto>(execute(request(url.toString(), "id,displayName,location").get().build()))
        val loc = p.location ?: throw MapsApiException("Place has no location")
        return NamedPlace(p.id ?: placeId, p.displayName?.text ?: "Destination", LatLng(loc.latitude, loc.longitude))
    }

    /**
     * Live CCS2 plugs at one charger right now: (free, total), or null when Google has no live data for it.
     * Used about 30 minutes before reaching a planned stop.
     */
    suspend fun chargerAvailability(placeId: String): Pair<Int, Int>? {
        val p = json.decodeFromString<PlaceDto>(execute(request("$placesBase/places/$placeId", "id,evChargeOptions").get().build()))
        val ccs = p.evChargeOptions?.connectorAggregation.orEmpty().filter { it.type == "EV_CONNECTOR_TYPE_CCS_COMBO_2" }
        val free = ccs.mapNotNull { it.availableCount }.takeIf { it.isNotEmpty() }?.sum() ?: return null
        return free to ccs.sumOf { it.count ?: 0 }
    }

    /** The first photo Google has for a place (a charger), as a resource name for [photo]; null when it has none. */
    suspend fun placePhotoName(placeId: String): String? =
        json.decodeFromString<PhotosDto>(execute(request("$placesBase/places/$placeId", "photos").get().build())).photos.firstOrNull()?.name

    /** A place photo's bytes, at most [maxWidthPx] wide (Place Photos: asks for the image link, then downloads it). */
    suspend fun photo(name: String, maxWidthPx: Int = 900): ByteArray? {
        val url = "$placesBase/$name/media".toHttpUrl().newBuilder()
            .addQueryParameter("maxWidthPx", maxWidthPx.toString()).addQueryParameter("skipHttpRedirect", "true").build()
        val uri = json.decodeFromString<PhotoMediaDto>(execute(request(url.toString()).get().build())).photoUri ?: return null
        return withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(uri).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
        }
    }

    // ---- Routes (with traffic and Google's alternatives) ----

    /**
     * Routes worth choosing between, each named by its main roads ("NH 66"). Google's own alternatives are often
     * missing or silly (Kochi → Malappuram via Tamil Nadu), so it also asks for toll-free routes, which brings in
     * the coastal NH 66; then drops near-duplicates and detours far longer than the best route.
     */
    suspend fun computeRoutes(origin: LatLng, destination: LatLng): List<RouteResult> = kotlinx.coroutines.coroutineScope {
        val main = async { computeRoutes(origin, destination, avoidTolls = false) }
        val tollFree = async { runCatching { computeRoutes(origin, destination, avoidTolls = true) }.getOrDefault(emptyList()) }
        val google = main.await() + tollFree.await()
        // Corridors Google left out (e.g. MC Road via Kottayam): ask for a route through that hub.
        val extra = Corridors.missingHubs(origin, destination, google.map { it.path }).take(2).map { hub ->
            async { runCatching { computeRoutes(origin, destination, avoidTolls = false, via = hub.at) }.getOrDefault(emptyList()) }
        }.flatMap { it.await() }
        val all = (google + extra).map { r -> r.copy(towns = Corridors.via(r.path), slow = r.slow.map { it.copy(near = Corridors.nearest(r.path[(it.startIndex + it.endIndex) / 2])) }) }
        val best = all.minOf { it.durationS }
        val shortest = all.minOf { it.distanceM }
        val kept = ArrayList<RouteResult>()
        for (r in all.sortedBy { it.durationS }) {
            val detour = r.distanceM > shortest * 1.3 && r.durationS > best + 3_600
            // Same towns (or, without towns, the same road name) and about the same length = the same route.
            val same = { k: RouteResult -> if (k.towns.isNotEmpty() || r.towns.isNotEmpty()) k.towns == r.towns else k.description == r.description }
            val duplicate = kept.any { k -> same(k) && kotlin.math.abs(k.distanceM - r.distanceM) < k.distanceM * 0.03 }
            if (!detour && !duplicate) kept += r
        }
        kept.take(4)
    }

    private suspend fun computeRoutes(origin: LatLng, destination: LatLng, avoidTolls: Boolean, via: LatLng? = null): List<RouteResult> {
        fun waypoint(p: LatLng) = buildJsonObject {
            putJsonObject("location") { putJsonObject("latLng") { put("latitude", p.lat); put("longitude", p.lng) } }
        }
        val body = buildJsonObject {
            put("origin", waypoint(origin))
            put("destination", waypoint(destination))
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE")
            put("computeAlternativeRoutes", via == null)
            via?.let { v -> putJsonArray("intermediates") { add(buildJsonObject { put("via", true); put("location", buildJsonObject { putJsonObject("latLng") { put("latitude", v.lat); put("longitude", v.lng) } }) }) } }
            putJsonArray("extraComputations") { add("TOLLS"); add("TRAFFIC_ON_POLYLINE") }
            put("polylineQuality", "HIGH_QUALITY")
            put("regionCode", "IN")
            put("languageCode", "en-IN")
            if (avoidTolls) putJsonObject("routeModifiers") { put("avoidTolls", true) }
        }
        val mask = "routes.description,routes.distanceMeters,routes.duration,routes.staticDuration,routes.polyline.encodedPolyline," +
            "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration,routes.travelAdvisory.tollInfo,routes.travelAdvisory.speedReadingIntervals,routes.warnings"
        val res = json.decodeFromString<RoutesResponse>(post("$routesBase/directions/v2:computeRoutes", body, mask))
        if (res.routes.isEmpty()) throw MapsApiException("No drivable route found")
        return res.routes.map { r ->
            val path = Geo.decodePolyline(r.polyline.encodedPolyline)
            RouteResult(
                path = path,
                slow = slowStretches(path, r.travelAdvisory?.speedReadingIntervals.orEmpty()),
                warnings = r.warnings.filter { it.isNotBlank() },
                encodedPolyline = r.polyline.encodedPolyline,
                distanceM = r.distanceMeters.toDouble(),
                durationS = r.duration.removeSuffix("s").toDouble(),
                steps = r.legs.flatMap { it.steps }.map { RouteStep(it.distanceMeters.toDouble(), it.staticDuration?.removeSuffix("s")?.toDoubleOrNull() ?: 0.0) },
                description = r.description?.takeIf { it.isNotBlank() },
                normalDurationS = r.staticDuration?.removeSuffix("s")?.toDoubleOrNull(),
                tolls = r.travelAdvisory?.tollInfo != null,
            )
        }
    }

    // ---- Elevation (Pro) ----

    /** Elevation for each point, batched to stay well under the URL and 512-sample limits. */
    suspend fun elevations(points: List<LatLng>): List<Double> {
        val out = ArrayList<Double>(points.size)
        for (batch in points.chunked(ELEVATION_BATCH)) {
            val url = elevationBase.toHttpUrl().newBuilder()
                .addQueryParameter("locations", "enc:" + Geo.encodePolyline(batch))
                .addQueryParameter("key", apiKey())
                .build()
            val res = json.decodeFromString<ElevationResponse>(execute(request(url.toString()).get().build()))
            if (res.status != "OK") throw MapsApiException("Elevation ${res.status}: ${res.errorMessage ?: ""}")
            if (res.results.size != batch.size) throw MapsApiException("Elevation returned ${res.results.size} of ${batch.size} points")
            out += res.results.map { it.elevation }
        }
        return out
    }

    // ---- Amenities near a stop (Nearby Search Pro) ----

    suspend fun nearbyAmenities(at: LatLng, radiusM: Double = 400.0): List<String> {
        val body = buildJsonObject {
            putJsonArray("includedTypes") { listOf("restaurant", "cafe", "bakery", "shopping_mall", "gas_station").forEach { add(it) } }
            put("maxResultCount", 6)
            put("rankPreference", "DISTANCE")
            putJsonObject("locationRestriction") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", at.lat); put("longitude", at.lng) }
                    put("radius", radiusM)
                }
            }
        }
        val res = json.decodeFromString<NearbyResponse>(post("$placesBase/places:searchNearby", body, "places.displayName,places.primaryTypeDisplayName"))
        return res.places.mapNotNull { p -> p.displayName?.text?.takeIf { it.isNotBlank() }?.let { n -> p.primaryTypeDisplayName?.text?.takeIf { it.isNotBlank() }?.let { "$n · $it" } ?: n } }
    }

    /**
     * Proper restaurants within [radiusM] for [meal] at that time, best first (at most 3): no cafés, tea stalls,
     * bakeries or snack bars; open then with 30 min to eat; rated 3.8+ with 50+ reviews. Breakfast needs Google to
     * confirm the place serves breakfast; lunch and dinner only skip places that say they don't. When nothing
     * passes, looks again up to 1.5 km and takes restaurants that don't say they skip the meal (30+ reviews), so a
     * meal stop still gets somewhere to eat.
     */
    suspend fun mealPlaces(at: LatLng, meal: Meal, dayOfWeek: Int, minuteOfDay: Int, radiusM: Double = 500.0): List<MealSpot> =
        mealPlaces(at, meal, dayOfWeek, minuteOfDay, radiusM, strict = true).ifEmpty { mealPlaces(at, meal, dayOfWeek, minuteOfDay, 1_500.0, strict = false) }

    private suspend fun mealPlaces(at: LatLng, meal: Meal, dayOfWeek: Int, minuteOfDay: Int, radiusM: Double, strict: Boolean): List<MealSpot> {
        val body = buildJsonObject {
            putJsonArray("includedTypes") { add("restaurant") }
            putJsonArray("excludedPrimaryTypes") { NOT_A_MEAL.forEach { add(it) } }
            put("maxResultCount", if (strict) 12 else 20)
            put("rankPreference", "POPULARITY")
            putJsonObject("locationRestriction") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", at.lat); put("longitude", at.lng) }
                    put("radius", radiusM)
                }
            }
        }
        val mask = "places.displayName,places.primaryType,places.primaryTypeDisplayName,places.location,places.servesBreakfast,places.servesLunch,places.servesDinner," +
            "places.rating,places.userRatingCount,places.regularOpeningHours.periods,places.photos"
        val res = json.decodeFromString<MealResponse>(post("$placesBase/places:searchNearby", body, mask))
        val t = dayOfWeek * 1440 + minuteOfDay
        return res.places.asSequence()
            .filter { p -> if (meal == Meal.Breakfast && strict) p.serves(meal) == true else p.serves(meal) != false }
            .filter { p -> p.openFor(t, 30) != false }
            .filter { p -> (p.rating ?: 0.0) >= 3.8 && (p.userRatingCount ?: 0) >= if (strict) 50 else 30 }
            .filter { p -> meal != Meal.Breakfast || p.primaryType !in NOT_BREAKFAST }
            .sortedWith(compareBy({ it.serves(meal) != true }, { -(it.rating ?: 0.0) * kotlin.math.ln((it.userRatingCount ?: 1).toDouble()) }))
            .mapNotNull { p ->
                val name = p.displayName?.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val d = p.location?.let { Geo.distanceM(at, LatLng(it.latitude, it.longitude)) } ?: 0.0
                MealSpot(name, p.rating, d, p.userRatingCount, p.primaryTypeDisplayName?.text, p.photos.firstOrNull()?.name)
            }
            .take(3).toList()
    }

    /** The closest named place (any type) within [radiusM] — a landmark to describe where the car is parked. */
    suspend fun nearestPlaceName(at: LatLng, radiusM: Double = 250.0): String? {
        val body = buildJsonObject {
            put("maxResultCount", 1)
            put("rankPreference", "DISTANCE")
            putJsonObject("locationRestriction") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", at.lat); put("longitude", at.lng) }
                    put("radius", radiusM)
                }
            }
        }
        val res = json.decodeFromString<NearbyResponse>(post("$placesBase/places:searchNearby", body, "places.displayName"))
        return res.places.firstOrNull()?.displayName?.text?.takeIf { it.isNotBlank() }
    }

    /** The closest CCS2 charger of at least 25 kW within [radiusM], or null. */
    suspend fun nearestFastCharger(at: LatLng, radiusM: Double = 15_000.0): ChargerDto? = nearbyFastChargers(at, radiusM, 1).firstOrNull()

    /**
     * CCS2 chargers of at least 25 kW around [at], nearest first (Places Nearby Search, one page). Used for
     * range watch ("Charge before heading home") and the car screen's "Fast chargers nearby".
     */
    suspend fun nearbyFastChargers(at: LatLng, radiusM: Double = 15_000.0, max: Int = 6): List<ChargerDto> {
        val body = buildJsonObject {
            putJsonArray("includedTypes") { add("electric_vehicle_charging_station") }
            put("maxResultCount", 20)
            put("rankPreference", "DISTANCE")
            putJsonObject("locationRestriction") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", at.lat); put("longitude", at.lng) }
                    put("radius", radiusM)
                }
            }
        }
        val res = json.decodeFromString<TextSearchResponse>(post("$placesBase/places:searchNearby", body, "places.id,places.displayName,places.location,places.formattedAddress,places.evChargeOptions"))
        return res.places.mapNotNull { p ->
            val loc = p.location ?: return@mapNotNull null
            val ccs = p.evChargeOptions?.connectorAggregation.orEmpty().filter { it.type == "EV_CONNECTOR_TYPE_CCS_COMBO_2" }
            val kw = ccs.maxOfOrNull { it.maxChargeRateKw ?: 0.0 } ?: 0.0
            if (kw < 25) return@mapNotNull null
            ChargerDto(id = p.id ?: return@mapNotNull null, name = p.displayName?.text ?: "EV charger", address = p.formattedAddress,
                position = LatLng(loc.latitude, loc.longitude), maxKw = kw, connectorCount = ccs.sumOf { it.count ?: 0 }.takeIf { it > 0 },
                availableCount = ccs.mapNotNull { it.availableCount }.takeIf { it.isNotEmpty() }?.sum(), viaDistanceM = null, open24h = null)
        }.take(max)
    }

    // ---- Charger search along the route (Text Search Enterprise + Atmosphere) ----

    /**
     * Chargers along a route of any length: the path is thinned to ~500 m spacing and split into
     * ~120 km segments, each searched separately (one page each), then de-duplicated.
     */
    suspend fun chargersAlongPath(path: List<LatLng>, minKw: Double): List<ChargerDto> {
        val thin = Geo.resample(path, 500.0).map { it.position }
        val segments = ArrayList<List<LatLng>>()
        var start = 0
        while (start < thin.lastIndex) {
            val end = minOf(start + SEGMENT_POINTS, thin.lastIndex)
            segments += thin.subList(start, end + 1)
            start = end
        }
        val out = LinkedHashMap<String, ChargerDto>()
        for (seg in segments) {
            val found = searchedNear(seg) ?: chargersAlongRoute(Geo.encodePolyline(seg), seg.first(), minKw, maxPages = 1)
                .map { it.copy(viaDistanceM = null) }.also { synchronized(searched) { searched += Searched(seg, it, System.currentTimeMillis()) } }
            found.forEach { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    /**
     * Charger searches are the costly, rate-limited call, so a stretch of road searched in the last few hours
     * (the same route planned again, or the shared part of two alternatives) reuses those results.
     */
    private class Searched(val path: List<LatLng>, val chargers: List<ChargerDto>, val atMillis: Long)
    private val searched = ArrayList<Searched>()

    private fun searchedNear(seg: List<LatLng>): List<ChargerDto>? {
        val fresh = synchronized(searched) {
            searched.removeAll { System.currentTimeMillis() - it.atMillis > SEARCH_TTL_MS }
            searched.toList()
        }
        if (fresh.isEmpty()) return null
        val probe = seg.filterIndexed { i, _ -> i % 4 == 0 } + seg.last()
        val covered = probe.all { p -> fresh.any { s -> s.path.any { Geo.distanceM(it, p) < 2_500 } } }
        if (!covered) return null
        return fresh.filter { s -> probe.any { p -> s.path.any { Geo.distanceM(it, p) < 2_500 } } }.flatMap { it.chargers }.distinctBy { it.id }
    }

    suspend fun chargersAlongRoute(encodedPolyline: String, origin: LatLng, minKw: Double, maxPages: Int = 3): List<ChargerDto> {
        val out = ArrayList<ChargerDto>()
        var pageToken: String? = null
        repeat(maxPages) {
            val body = buildJsonObject {
                put("textQuery", "EV charging station")
                put("pageSize", 20)
                putJsonObject("searchAlongRouteParameters") { putJsonObject("polyline") { put("encodedPolyline", encodedPolyline) } }
                putJsonObject("routingParameters") { putJsonObject("origin") { put("latitude", origin.lat); put("longitude", origin.lng) } }
                putJsonObject("evOptions") {
                    put("minimumChargingRateKw", minKw)
                    putJsonArray("connectorTypes") { add("EV_CONNECTOR_TYPE_CCS_COMBO_2") }
                }
                pageToken?.let { put("pageToken", it) }
            }
            val mask = "places.id,places.displayName,places.location,places.formattedAddress,places.evChargeOptions,places.regularOpeningHours,routingSummaries,nextPageToken"
            val res = json.decodeFromString<TextSearchResponse>(post("$placesBase/places:searchText", body, mask))
            res.places.forEachIndexed { i, p ->
                val loc = p.location ?: return@forEachIndexed
                val legs = res.routingSummaries.getOrNull(i)?.legs.orEmpty()
                val ccs = p.evChargeOptions?.connectorAggregation.orEmpty().filter { it.type == "EV_CONNECTOR_TYPE_CCS_COMBO_2" }
                out += ChargerDto(
                    id = p.id ?: return@forEachIndexed,
                    name = p.displayName?.text ?: "EV charger",
                    address = p.formattedAddress,
                    position = LatLng(loc.latitude, loc.longitude),
                    maxKw = ccs.maxOfOrNull { it.maxChargeRateKw ?: 0.0 } ?: 0.0,
                    connectorCount = ccs.sumOf { it.count ?: 0 }.takeIf { it > 0 },
                    availableCount = ccs.mapNotNull { it.availableCount }.takeIf { it.isNotEmpty() }?.sum(),
                    viaDistanceM = if (legs.size >= 2) legs.sumOf { it.distanceMeters?.toDouble() ?: 0.0 } else null,
                    // Google encodes "open 24 hours" as a single period that opens and never closes.
                    open24h = p.regularOpeningHours?.periods?.let { it.size == 1 && it[0].close == null },
                )
            }
            pageToken = res.nextPageToken ?: return out
        }
        return out
    }

    companion object {
        const val ELEVATION_BATCH = 256
        /** 240 × 500 m ≈ 120 km per charger-search segment. */
        const val SEGMENT_POINTS = 240
        /** Charger search results are reused for this long. */
        const val SEARCH_TTL_MS = 6 * 3_600_000L
        const val DAILY_LIMIT = "Google's daily limit for charger searches is used up. It resets at 1:30 pm India time — " +
            "or raise the \"SearchTextRequest per day\" quota for Places API (New) in Google Cloud."
    }
}

data class PlaceSuggestion(val placeId: String, val title: String, val subtitle: String?, val distanceM: Double? = null)
data class NamedPlace(val placeId: String, val name: String, val position: LatLng)
data class RouteStep(val distanceM: Double, val durationS: Double)
data class RouteResult(
    val path: List<LatLng>,
    val encodedPolyline: String,
    val distanceM: Double,
    /** With today's traffic. */
    val durationS: Double,
    val steps: List<RouteStep>,
    /** Google's name for the route, e.g. "NH 66" or "MC Rd". */
    val description: String? = null,
    /** Without traffic; [durationS] minus this is the traffic delay. */
    val normalDurationS: Double? = null,
    val tolls: Boolean = false,
    /** Main towns on the way, e.g. ["Kottayam", "Muvattupuzha", "Angamaly"]. */
    val towns: List<String> = emptyList(),
    /** Stretches Google reports as slow or jammed right now (traffic, and the works or diversions causing it). */
    val slow: List<SlowStretch> = emptyList(),
    /** Google's own notes on the route, if any. */
    val warnings: List<String> = emptyList(),
) {
    /** "Kottayam, Muvattupuzha, Angamaly", else Google's road name. */
    val label: String? get() = towns.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: description
}

/** A slow or jammed stretch of the route: where it starts on [RouteResult.path], how long, and the nearest town. */
data class SlowStretch(val startIndex: Int, val endIndex: Int, val lengthM: Double, val jam: Boolean, val near: String? = null)

/** Joins Google's speed readings into stretches of 1 km or more (jams from 500 m), longest first. */
private fun slowStretches(path: List<LatLng>, intervals: List<RoutesResponse.Speed>): List<SlowStretch> {
    val out = ArrayList<SlowStretch>()
    var start = -1; var end = -1; var jam = false
    fun flush() {
        if (start < 0) return
        val len = (start until end.coerceAtMost(path.lastIndex)).sumOf { Geo.distanceM(path[it], path[it + 1]) }
        if (len >= if (jam) 500.0 else 1_000.0) out += SlowStretch(start, end, len, jam)
        start = -1; jam = false
    }
    intervals.sortedBy { it.startPolylinePointIndex }.forEach { iv ->
        val slow = iv.speed == "SLOW" || iv.speed == "TRAFFIC_JAM"
        if (!slow) { flush(); return@forEach }
        if (start < 0) start = iv.startPolylinePointIndex
        else if (iv.startPolylinePointIndex - end > 3) { flush(); start = iv.startPolylinePointIndex }
        end = iv.endPolylinePointIndex
        if (iv.speed == "TRAFFIC_JAM") jam = true
    }
    flush()
    return out.sortedByDescending { it.lengthM }
}

/** A restaurant for a meal stop. */
data class MealSpot(
    val name: String,
    val rating: Double?,
    val distanceM: Double,
    val reviews: Int? = null,
    /** Google's word for it, e.g. "South Indian Restaurant". */
    val kind: String? = null,
    /** Places photo resource name, for [GoogleMapsClient.photo]. */
    val photo: String? = null,
)

/** A charger as returned by Places; [viaDistanceM] = origin→charger→destination when Google provides it. */
data class ChargerDto(
    val id: String,
    val name: String,
    val address: String?,
    val position: LatLng,
    val maxKw: Double,
    val connectorCount: Int?,
    val availableCount: Int?,
    val viaDistanceM: Double?,
    val open24h: Boolean? = null,
)

// ---- wire models ----

@Serializable private data class GoogleError(val error: Detail? = null) {
    @Serializable data class Detail(val message: String? = null)
}

@Serializable private data class TextDto(val text: String = "")
@Serializable private data class PhotoDto(val name: String = "")
@Serializable private data class PhotosDto(val photos: List<PhotoDto> = emptyList())
@Serializable private data class PhotoMediaDto(val photoUri: String? = null)
@Serializable private data class LatLngDto(val latitude: Double, val longitude: Double)

@Serializable private data class AutocompleteResponse(val suggestions: List<Suggestion> = emptyList()) {
    @Serializable data class Suggestion(val placePrediction: Prediction? = null)
    @Serializable data class Prediction(val placeId: String, val text: TextDto = TextDto(), val structuredFormat: Structured? = null, val distanceMeters: Int? = null)
    @Serializable data class Structured(val mainText: TextDto? = null, val secondaryText: TextDto? = null)
}

@Serializable private data class PlaceDto(
    val id: String? = null,
    val displayName: TextDto? = null,
    val location: LatLngDto? = null,
    val formattedAddress: String? = null,
    val evChargeOptions: EvOptions? = null,
    val regularOpeningHours: Hours? = null,
) {
    @Serializable data class Hours(val periods: List<Period> = emptyList())
    @Serializable data class Period(val open: Point? = null, val close: Point? = null)
    @Serializable data class Point(val day: Int? = null, val hour: Int? = null, val minute: Int? = null)
    @Serializable data class EvOptions(val connectorCount: Int? = null, val connectorAggregation: List<Agg> = emptyList())
    @Serializable data class Agg(
        val type: String? = null,
        val maxChargeRateKw: Double? = null,
        val count: Int? = null,
        val availableCount: Int? = null,
    )
}

@Serializable private data class MealResponse(val places: List<Place> = emptyList()) {
    @Serializable data class Place(
        val displayName: TextDto? = null,
        val primaryType: String? = null,
        val servesBreakfast: Boolean? = null,
        val servesLunch: Boolean? = null,
        val servesDinner: Boolean? = null,
        val rating: Double? = null,
        val userRatingCount: Int? = null,
        val regularOpeningHours: PlaceDto.Hours? = null,
        val location: LatLngDto? = null,
        val primaryTypeDisplayName: TextDto? = null,
        val photos: List<PhotoDto> = emptyList(),
    ) {
        fun serves(meal: Meal) = when (meal) { Meal.Breakfast -> servesBreakfast; Meal.Lunch -> servesLunch; Meal.Dinner -> servesDinner }

        /** Open from [t] (minutes into the week, Sunday 00:00 = 0) for [minutes]? null = hours unknown. */
        fun openFor(t: Int, minutes: Int): Boolean? {
            val periods = regularOpeningHours?.periods?.takeIf { it.isNotEmpty() } ?: return null
            val week = 7 * 1440
            return periods.any { p ->
                val o = p.open ?: return@any false
                val c = p.close ?: return@any true                     // open around the clock
                val from = (o.day ?: 0) * 1440 + (o.hour ?: 0) * 60 + (o.minute ?: 0)
                var to = (c.day ?: 0) * 1440 + (c.hour ?: 0) * 60 + (c.minute ?: 0)
                if (to <= from) to += week
                listOf(t, t + week).any { it >= from && it + minutes <= to }
            }
        }
    }
}

private val NOT_A_MEAL = listOf("cafe", "coffee_shop", "tea_house", "bakery", "snack_bar", "juice_shop", "dessert_shop", "ice_cream_shop", "fast_food_restaurant", "bar")
private val NOT_BREAKFAST = setOf(
    "hamburger_restaurant", "pizza_restaurant", "chinese_restaurant", "steak_house", "barbecue_restaurant", "sushi_restaurant", "bar_and_grill",
    "chicken_wings_restaurant", "chicken_restaurant", "fried_chicken_restaurant", "sandwich_shop",
)

@Serializable private data class NearbyResponse(val places: List<Place> = emptyList()) {
    @Serializable data class Place(val displayName: TextDto? = null, val primaryTypeDisplayName: TextDto? = null)
}

@Serializable private data class RoutesResponse(val routes: List<Route> = emptyList()) {
    @Serializable data class Route(
        val distanceMeters: Int = 0,
        val duration: String = "0s",
        val polyline: Poly = Poly(),
        val legs: List<Leg> = emptyList(),
        val description: String? = null,
        val staticDuration: String? = null,
        val travelAdvisory: Advisory? = null,
        val warnings: List<String> = emptyList(),
    )
    @Serializable data class Advisory(val tollInfo: kotlinx.serialization.json.JsonObject? = null, val speedReadingIntervals: List<Speed> = emptyList())
    @Serializable data class Speed(val startPolylinePointIndex: Int = 0, val endPolylinePointIndex: Int = 0, val speed: String? = null)
    @Serializable data class Poly(val encodedPolyline: String = "")
    @Serializable data class Leg(val steps: List<Step> = emptyList())
    @Serializable data class Step(val distanceMeters: Int = 0, val staticDuration: String? = null)
}

@Serializable private data class ElevationResponse(
    val status: String = "",
    val results: List<Result> = emptyList(),
    @SerialName("error_message") val errorMessage: String? = null,
) {
    @Serializable data class Result(val elevation: Double)
}

@Serializable private data class TextSearchResponse(
    val places: List<PlaceDto> = emptyList(),
    val routingSummaries: List<Summary> = emptyList(),
    val nextPageToken: String? = null,
) {
    @Serializable data class Summary(val legs: List<Leg> = emptyList())
    @Serializable data class Leg(val distanceMeters: Int? = null, val duration: String? = null)
}
