package dev.irshadm.volta.core.planner

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

/** A point on the route [distanceM] metres from the start. */
data class RoutePoint(val position: LatLng, val distanceM: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Google encoded-polyline decoder (precision 5). */
    fun decodePolyline(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0L
        var lng = 0L
        while (i < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0L
                var b: Int
                do {
                    b = encoded[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lng += delta
            }
            out += LatLng(lat / 1e5, lng / 1e5)
        }
        return out
    }

    fun encodePolyline(points: List<LatLng>): String {
        val sb = StringBuilder()
        var pLat = 0L
        var pLng = 0L
        fun enc(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) {
                sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
                x = x shr 5
            }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = (p.lat * 1e5).roundToLong()
            val lng = (p.lng * 1e5).roundToLong()
            enc(lat - pLat); enc(lng - pLng)
            pLat = lat; pLng = lng
        }
        return sb.toString()
    }

    /** Points every [stepM] metres along the path (always including start and end). */
    fun resample(path: List<LatLng>, stepM: Double): List<RoutePoint> {
        require(stepM > 0)
        if (path.isEmpty()) return emptyList()
        val out = arrayListOf(RoutePoint(path.first(), 0.0))
        var travelled = 0.0
        var nextMark = stepM
        for (k in 1 until path.size) {
            val a = path[k - 1]
            val b = path[k]
            val seg = distanceM(a, b)
            if (seg == 0.0) continue
            while (travelled + seg >= nextMark) {
                val t = (nextMark - travelled) / seg
                out += RoutePoint(LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t), nextMark)
                nextMark += stepM
            }
            travelled += seg
        }
        if (travelled - out.last().distanceM > 1.0) out += RoutePoint(path.last(), travelled)
        return out
    }
}
