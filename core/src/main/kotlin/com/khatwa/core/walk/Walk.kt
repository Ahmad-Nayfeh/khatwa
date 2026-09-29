package com.khatwa.core.walk

import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import kotlin.math.roundToInt

/** Step length: first from the user's height, then corrected by walks measured with GPS. */
object Stride {
    const val DEFAULT_M = 0.70
    private const val MIN_M = 0.40
    private const val MAX_M = 1.10

    /** A common estimate for walking: step length ≈ 41.5% of height. */
    fun fromHeightCm(heightCm: Int?): Double =
        if (heightCm == null || heightCm !in 100..230) DEFAULT_M else heightCm * 0.415 / 100.0

    /**
     * Blends a GPS-measured step length into [currentM]. Short walks, few steps and impossible
     * values (GPS drift, a car ride) are ignored. Each good walk moves the estimate 30% of the way.
     */
    fun calibrate(currentM: Double, gpsDistanceM: Double, steps: Long): Double {
        if (gpsDistanceM < 500 || steps < 600) return currentM
        val measured = gpsDistanceM / steps
        if (measured !in MIN_M..MAX_M) return currentM
        return currentM + (measured - currentM) * 0.3
    }

    fun distanceM(steps: Long, strideM: Double): Double = steps * strideM
}

/** One GPS reading. */
data class Fix(val point: LatLon, val accuracyM: Float, val timeMs: Long)

/**
 * Turns raw GPS readings into a walk: inaccurate readings are skipped, jitter while standing
 * still adds no distance, and jumps faster than a brisk run (a car, a bad fix) are dropped.
 */
class TrackBuilder(
    private val maxAccuracyM: Float = 30f,
    private val maxSpeedMps: Double = 4.0,
) {
    private val kept = ArrayList<LatLon>()
    private var last: Fix? = null
    var distanceM: Double = 0.0
        private set

    val points: List<LatLon> get() = kept

    /** Returns true when the fix was used. */
    fun add(fix: Fix): Boolean {
        if (fix.accuracyM > maxAccuracyM) return false
        val prev = last
        if (prev == null) {
            last = fix
            kept += fix.point
            return true
        }
        val d = Geo.distanceM(prev.point, fix.point)
        // Movement smaller than the readings' own uncertainty is jitter, not walking.
        if (d < maxOf(5.0, (fix.accuracyM + prev.accuracyM) / 2.0)) return false
        val dt = (fix.timeMs - prev.timeMs) / 1000.0
        if (dt <= 0 || d / dt > maxSpeedMps) return false
        distanceM += d
        last = fix
        kept += fix.point
        return true
    }
}

/** Distance and pace formatting helpers kept free of Android. */
object Distances {
    /** "4.2" style kilometers with one decimal (or two under 1 km). */
    fun km(meters: Double): String {
        val km = meters / 1000.0
        return if (km < 1) String.format(java.util.Locale.US, "%.2f", km) else String.format(java.util.Locale.US, "%.1f", km)
    }

    /** Minutes needed at a normal walking pace (≈ 4.7 km/h). */
    fun walkingMinutes(meters: Double): Int = (meters / 1.3 / 60.0).roundToInt().coerceAtLeast(1)
}
