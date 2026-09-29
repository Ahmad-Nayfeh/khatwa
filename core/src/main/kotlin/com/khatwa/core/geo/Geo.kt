package com.khatwa.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the map in degrees (WGS84). */
data class LatLon(val lat: Double, val lon: Double)

/** Distances, bearings and the compact route format used for walks. */
object Geo {
    private const val EARTH_M = 6_371_008.8

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** Great-circle distance in meters. */
    fun distanceM(a: LatLon, b: LatLon): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], 0..360 degrees clockwise from north. */
    fun bearingDeg(a: LatLon, b: LatLon): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (deg(atan2(y, x)) + 360.0) % 360.0
    }

    /** The point [distanceM] away from [from] along [bearingDeg]. */
    fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_M
        val b = rad(bearingDeg)
        val lat1 = rad(from.lat)
        val lon1 = rad(from.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(deg(lat2), (deg(lon2) + 540.0) % 360.0 - 180.0)
    }

    fun pathLengthM(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceM(a, b) }

    /**
     * Drops points that change the shape by less than [toleranceM] (Douglas–Peucker on a local
     * flat projection; fine for walks, which span a few kilometers).
     */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val lat0 = rad(points.first().lat)
        val xs = DoubleArray(points.size) { rad(points[it].lon) * cos(lat0) * EARTH_M }
        val ys = DoubleArray(points.size) { rad(points[it].lat) * EARTH_M }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { addLast(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = segmentDistance(xs[i], ys[i], xs[s], ys[s], xs[e], ys[e])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(s to idx)
                stack.addLast(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    /** Google's encoded polyline format (5 decimals): about 1 m precision in a few bytes per point. */
    fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        for (p in points) {
            val lat = (p.lat * 1e5).roundToLong()
            val lon = (p.lon * 1e5).roundToLong()
            writeValue(sb, lat - pLat)
            writeValue(sb, lon - pLon)
            pLat = lat
            pLon = lon
        }
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }

    fun decode(encoded: String): List<LatLon> {
        val out = ArrayList<LatLon>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < encoded.length) {
            val (dLat, n1) = readValue(encoded, i)
            val (dLon, n2) = readValue(encoded, n1)
            i = n2
            lat += dLat
            lon += dLon
            out += LatLon(lat / 1e5, lon / 1e5)
        }
        return out
    }

    private fun readValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val v = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return v to i
    }
}

/**
 * Streets walked, as a set of ~25 m grid cells. The grid is fixed (Saudi latitudes: cells are
 * 23–27 m wide), so the same street always lands in the same cells.
 */
object Explored {
    const val CELL_M = 25.0
    private const val LAT_STEP = CELL_M / 111_320.0
    private val LON_STEP = CELL_M / (111_320.0 * cos(24.0 * PI / 180.0))

    fun cell(p: LatLon): Long {
        val y = kotlin.math.floor(p.lat / LAT_STEP).toLong()
        val x = kotlin.math.floor(p.lon / LON_STEP).toLong()
        return (y shl 32) xor (x and 0xffffffffL)
    }

    /** Every cell a route passes through (segments are sampled every ~10 m). */
    fun cellsOf(route: List<LatLon>): Set<Long> {
        val out = HashSet<Long>()
        if (route.isEmpty()) return out
        out += cell(route.first())
        for ((a, b) in route.zipWithNext()) {
            val n = (Geo.distanceM(a, b) / 10.0).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toDouble() / n
                out += cell(LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
            }
        }
        return out
    }

    /** Distinct distance walked, from the number of cells (one cell ≈ 25 m of street). */
    fun distinctKm(cells: Set<Long>): Double = cells.size * CELL_M / 1000.0

    /**
     * Share (0..1) of the straight way from [from] to [to] that is new: cells not walked yet (a
     * neighbouring walked cell counts as walked, so a parallel sidewalk is not "new").
     */
    fun novelty(from: LatLon, to: LatLon, walked: Set<Long>): Double {
        if (walked.isEmpty()) return 1.0
        val n = (Geo.distanceM(from, to) / CELL_M).toInt().coerceAtLeast(1)
        var fresh = 0
        for (k in 0..n) {
            val t = k.toDouble() / n
            val p = LatLon(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t)
            if (!nearWalked(p, walked)) fresh++
        }
        return fresh.toDouble() / (n + 1)
    }

    private fun nearWalked(p: LatLon, walked: Set<Long>): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            if (cell(LatLon(p.lat + dy * LAT_STEP, p.lon + dx * LON_STEP)) in walked) return true
        }
        return false
    }
}
