package com.khatwa.core.geo

import com.khatwa.core.walk.Fix
import com.khatwa.core.walk.Stride
import com.khatwa.core.walk.TrackBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val riyadh = LatLon(24.68773, 46.72185)

    @Test
    fun `distance between two known points`() {
        // Riyadh to Jeddah is about 850 km in a straight line.
        val d = Geo.distanceM(riyadh, LatLon(21.49012, 39.18624))
        assertTrue(abs(d - 850_000) < 15_000, "got $d")
        assertEquals(0.0, Geo.distanceM(riyadh, riyadh), 1e-6)
    }

    @Test
    fun `destination and bearing agree`() {
        for (b in listOf(0.0, 45.0, 135.0, 270.0)) {
            val p = Geo.destination(riyadh, b, 1500.0)
            assertEquals(1500.0, Geo.distanceM(riyadh, p), 1.0)
            assertEquals(b, Geo.bearingDeg(riyadh, p), 0.5)
        }
    }

    @Test
    fun `polyline round trip keeps about a meter of precision`() {
        val pts = (0 until 50).map { LatLon(24.7 + it * 0.00013, 46.6 - it * 0.00021) }
        val back = Geo.decode(Geo.encode(pts))
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) -> assertTrue(Geo.distanceM(a, b) < 1.5) }
        // The reference example from the format's documentation.
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Geo.encode(listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))))
    }

    @Test
    fun `simplify keeps corners and drops points on straight lines`() {
        val straight = (0..100).map { Geo.destination(riyadh, 90.0, it * 10.0) }
        val corner = (1..100).map { Geo.destination(straight.last(), 0.0, it * 10.0) }
        val s = Geo.simplify(straight + corner, 3.0)
        assertEquals(3, s.size)
        assertEquals(Geo.pathLengthM(straight + corner), Geo.pathLengthM(s), 2.0)
    }

    @Test
    fun `explored cells count the distinct distance, not repeats`() {
        val there = (0..40).map { Geo.destination(riyadh, 0.0, it * 25.0) }
        val once = Explored.cellsOf(there)
        val twice = Explored.cellsOf(there + there.reversed())
        assertEquals(once, twice)
        assertEquals(1.0, Explored.distinctKm(once), 0.15)
        // Walking the same street again is not new; a street to the east is.
        assertTrue(Explored.novelty(riyadh, there.last(), once) < 0.05)
        assertTrue(Explored.novelty(riyadh, Geo.destination(riyadh, 90.0, 1000.0), once) > 0.9)
    }

    @Test
    fun `stride from height and GPS calibration`() {
        assertEquals(0.7055, Stride.fromHeightCm(170), 1e-4)
        assertEquals(Stride.DEFAULT_M, Stride.fromHeightCm(null), 0.0)
        assertEquals(Stride.DEFAULT_M, Stride.fromHeightCm(20), 0.0)
        // 2 km in 2 500 steps = 0.8 m: the estimate moves 30% of the way.
        assertEquals(0.7 + 0.1 * 0.3, Stride.calibrate(0.7, 2000.0, 2500), 1e-9)
        // Too short, or impossible (a car ride), is ignored.
        assertEquals(0.7, Stride.calibrate(0.7, 300.0, 400), 0.0)
        assertEquals(0.7, Stride.calibrate(0.7, 5000.0, 1000), 0.0)
    }

    @Test
    fun `track ignores jitter, bad accuracy and car-speed jumps`() {
        val t = TrackBuilder()
        var time = 0L
        fun at(p: LatLon, acc: Float = 8f, dtS: Long = 10) = t.add(Fix(p, acc, (time + dtS * 1000).also { time = it }))
        assertTrue(at(riyadh))
        // Standing still: jitter of 3 m is not walking.
        assertTrue(!at(Geo.destination(riyadh, 10.0, 3.0)))
        // A reading with 80 m accuracy is skipped.
        assertTrue(!at(Geo.destination(riyadh, 0.0, 30.0), acc = 80f))
        // Walking north 13 m every 10 s (1.3 m/s) for 100 readings: 1.3 km.
        var p = riyadh
        repeat(100) { p = Geo.destination(p, 0.0, 13.0); assertTrue(at(p)) }
        assertEquals(1300.0, t.distanceM, 5.0)
        // A 500 m jump in 10 s (a car) is dropped.
        assertTrue(!at(Geo.destination(p, 0.0, 500.0)))
        assertEquals(1300.0, t.distanceM, 5.0)
        assertEquals(101, t.points.size)
        // Duration from the readings used: first at 10 s, last at 1 030 s (two rejected readings in between also took 10 s each).
        assertEquals(1_020_000L, t.lastTimeMs!! - t.firstTimeMs!!)
    }
}
