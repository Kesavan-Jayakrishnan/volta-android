package dev.irshadm.volta.data.maps

import dev.irshadm.volta.core.planner.Geo
import dev.irshadm.volta.core.planner.LatLng

/**
 * Towns used to name routes ("via Kottayam, Muvattupuzha, Angamaly") and, for the [hub]s, to ask Google for a
 * route through a corridor it didn't offer (it often leaves out MC Road or NH 66). Kerala and its neighbours.
 */
object Corridors {
    data class Town(val name: String, val at: LatLng, val hub: Boolean = false) {
        /** Cities people navigate by come first when naming a route. */
        val city: Boolean get() = name in CITIES
    }

    private val CITIES = setOf(
        "Thiruvananthapuram", "Kollam", "Alappuzha", "Kottayam", "Kochi", "Angamaly", "Thrissur", "Palakkad", "Malappuram",
        "Kozhikode", "Kannur", "Kasaragod", "Munnar", "Nagercoil", "Tirunelveli", "Madurai", "Coimbatore", "Salem",
        "Mysuru", "Bengaluru", "Mangaluru",
    )

    val towns = listOf(
        Town("Thiruvananthapuram", LatLng(8.5241, 76.9366)), Town("Attingal", LatLng(8.6964, 76.8155)),
        Town("Kollam", LatLng(8.8932, 76.6141)), Town("Kottarakkara", LatLng(9.0005, 76.7735)),
        Town("Punalur", LatLng(9.0168, 76.9257), hub = true), Town("Adoor", LatLng(9.1530, 76.7356)),
        Town("Pathanamthitta", LatLng(9.2648, 76.7870)), Town("Kayamkulam", LatLng(9.1748, 76.5013)),
        Town("Chengannur", LatLng(9.3182, 76.6114)), Town("Thiruvalla", LatLng(9.3835, 76.5741)),
        Town("Changanassery", LatLng(9.4446, 76.5410)), Town("Alappuzha", LatLng(9.4981, 76.3388), hub = true),
        Town("Kottayam", LatLng(9.5916, 76.5222), hub = true), Town("Cherthala", LatLng(9.6845, 76.3360)),
        Town("Ettumanoor", LatLng(9.6705, 76.5602)), Town("Pala", LatLng(9.7139, 76.6831)),
        Town("Vaikom", LatLng(9.7486, 76.3936)), Town("Thodupuzha", LatLng(9.8959, 76.7184), hub = true),
        Town("Kochi", LatLng(10.0261, 76.3125)), Town("Muvattupuzha", LatLng(9.9894, 76.5790)),
        Town("Kothamangalam", LatLng(10.0602, 76.6351)), Town("Perumbavoor", LatLng(10.1155, 76.4769)),
        Town("Aluva", LatLng(10.1004, 76.3570)), Town("Angamaly", LatLng(10.1960, 76.3860)),
        Town("Chalakudy", LatLng(10.3070, 76.3340)), Town("Kodungallur", LatLng(10.2269, 76.1960)),
        Town("Thrissur", LatLng(10.5276, 76.2144)), Town("Kunnamkulam", LatLng(10.6500, 76.0700)),
        Town("Guruvayur", LatLng(10.5946, 76.0400)), Town("Ponnani", LatLng(10.7700, 75.9250)),
        Town("Shoranur", LatLng(10.7600, 76.2700)), Town("Pattambi", LatLng(10.8000, 76.1900)),
        Town("Ottapalam", LatLng(10.7700, 76.3700)), Town("Palakkad", LatLng(10.7867, 76.6548), hub = true),
        Town("Perinthalmanna", LatLng(10.9760, 76.2250)), Town("Kuttippuram", LatLng(10.8440, 76.0310)),
        Town("Tirur", LatLng(10.9160, 75.9220)), Town("Malappuram", LatLng(11.0510, 76.0711)),
        Town("Manjeri", LatLng(11.1200, 76.1200)), Town("Kozhikode", LatLng(11.2588, 75.7804)),
        Town("Vadakara", LatLng(11.6080, 75.5917)), Town("Kannur", LatLng(11.8745, 75.3704)),
        Town("Kasaragod", LatLng(12.4996, 74.9869)), Town("Munnar", LatLng(10.0889, 77.0595)),
        Town("Adimali", LatLng(10.0100, 76.9500)), Town("Kumily", LatLng(9.6040, 77.1640)),
        Town("Nagercoil", LatLng(8.1833, 77.4119)), Town("Tirunelveli", LatLng(8.7139, 77.7567)),
        Town("Madurai", LatLng(9.9252, 78.1198)), Town("Coimbatore", LatLng(11.0168, 76.9558), hub = true),
        Town("Salem", LatLng(11.6643, 78.1460)), Town("Mysuru", LatLng(12.2958, 76.6394)),
        Town("Bengaluru", LatLng(12.9716, 77.5946)), Town("Mangaluru", LatLng(12.9141, 74.8560)),
    )

    /** Up to three well-spread towns the route passes (not the start or the end), e.g. "Kottayam, Muvattupuzha, Angamaly". */
    /** The closest known town to [p] within 15 km, to name a stretch of road ("near Kottarakkara"). */
    fun nearest(p: LatLng): String? = towns.minByOrNull { Geo.distanceM(it.at, p) }?.takeIf { Geo.distanceM(it.at, p) < 15_000 }?.name

    fun via(path: List<LatLng>): List<String> {
        if (path.size < 2) return emptyList()
        val pts = Geo.resample(path, 1_000.0)
        val total = pts.last().distanceM
        val passed = towns.mapNotNull { t ->
            val nearest = pts.minBy { Geo.distanceM(it.position, t.at) }
            val off = Geo.distanceM(nearest.position, t.at)
            // Bypasses skirt city centres (Kollam, Kottayam), so cities count from further away than towns.
            if (off > (if (t.city) 6_000 else 3_000) || nearest.distanceM < 20_000 || nearest.distanceM > total - 20_000) null else t to nearest.distanceM
        }.sortedBy { it.second }
        // Cities first (Kollam, Kottayam, Kochi…); towns fill in when the route passes fewer than two cities.
        // At most three, spread over the trip so the names read as a journey.
        fun spread(list: List<Pair<Town, Double>>, n: Int) =
            if (list.size <= n) list else List(n) { k -> list.minBy { kotlin.math.abs(it.second - total * (k + 1) / (n + 1)) } }.distinct()
        val cities = spread(passed.filter { it.first.city }, 3)
        val picked = if (cities.size >= 2) cities else (cities + spread(passed.filter { !it.first.city }, 3 - cities.size))
        return picked.sortedBy { it.second }.map { it.first.name }
    }

    /**
     * Hubs worth asking Google to route through: roughly between the two ends (not near either), not too far off the
     * straight line, and not on any route already found. Closest to the straight line first.
     */
    fun missingHubs(origin: LatLng, destination: LatLng, found: List<List<LatLng>>): List<Town> {
        val len = Geo.distanceM(origin, destination)
        if (len < 60_000) return emptyList()
        return towns.filter { it.hub }.mapNotNull { t ->
            val a = Geo.distanceM(origin, t.at)
            val b = Geo.distanceM(t.at, destination)
            val between = a > len * 0.15 && b > len * 0.15 && a + b < len * 1.35
            val covered = found.any { path -> path.any { Geo.distanceM(it, t.at) < 6_000 } }
            if (between && !covered) t to (a + b) else null
        }.sortedBy { it.second }.map { it.first }
    }
}
