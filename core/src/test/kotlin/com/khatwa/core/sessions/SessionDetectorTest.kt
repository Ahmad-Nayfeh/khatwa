package com.khatwa.core.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDetectorTest {

    /** Feeds a steady walk: one event per [intervalMs] carrying [stepsPerEvent] steps. */
    private fun walk(
        d: SessionDetector,
        fromMs: Long,
        durationMs: Long,
        cadencePerMin: Int = 100,
        out: MutableList<WalkSession>,
    ): Long {
        val intervalMs = 60_000L / cadencePerMin
        var t = fromMs
        val end = fromMs + durationMs
        while (t <= end) {
            d.onSteps(t, 1)?.let(out::add)
            t += intervalMs
        }
        return end
    }

    private val min = 60_000L

    @Test
    fun `12 minutes of walking is one session`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        val end = walk(d, 0, 12 * min, out = out)
        assertTrue(out.isEmpty())
        val s = d.flush(end + 5 * min)
        assertNotNull(s)
        assertTrue(s.durationMs in (11 * min)..(12 * min + 1000), "duration was ${s.durationMs}")
        assertTrue(s.steps in 1150..1250, "steps were ${s.steps}")
    }

    @Test
    fun `6 minutes of walking is not a session`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        val end = walk(d, 0, 6 * min, out = out)
        assertNull(d.flush(end + 5 * min))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a pause of 90 seconds does not split a session`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        var t = walk(d, 0, 5 * min, out = out)
        t += 90_000
        t = walk(d, t, 6 * min, out = out)
        val s = d.flush(t + 5 * min)
        assertTrue(out.isEmpty())
        assertNotNull(s)
        assertTrue(s.durationMs >= 12 * min, "duration was ${s.durationMs}")
    }

    @Test
    fun `a pause of 3 minutes splits into two short walks and no session`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        var t = walk(d, 0, 5 * min, out = out)
        t += 3 * min
        t = walk(d, t, 6 * min, out = out)
        assertNull(d.flush(t + 5 * min))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `slow shuffling never counts as walking`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        val end = walk(d, 0, 20 * min, cadencePerMin = 30, out = out)
        assertNull(d.flush(end + 5 * min))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a new walk after a long gap closes the previous session`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        var t = walk(d, 0, 15 * min, out = out)
        t += 10 * min
        walk(d, t, 2 * min, out = out) // the first event here closes the 15-min session
        assertEquals(1, out.size)
        assertTrue(out[0].durationMs >= 14 * min)
    }

    @Test
    fun `batched events with larger deltas still detect a session`() {
        val d = SessionDetector()
        var t = 0L
        var s: WalkSession? = null
        // One event every 10 seconds carrying 17 steps (~100/min) for 11 minutes.
        repeat(67) {
            d.onSteps(t, 17)?.let { s = it }
            t += 10_000
        }
        val flushed = d.flush(t + 5 * min)
        assertNotNull(flushed ?: s)
    }

    @Test
    fun `an out-of-order timestamp is clamped and does not break detection`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        var t = walk(d, 0, 6 * min, out = out)
        d.onSteps(t - 30_000, 1) // arrives late with an older timestamp
        t = walk(d, t + 600, 6 * min, out = out)
        val s = d.flush(t + 5 * min)
        assertNotNull(s)
        assertTrue(s.durationMs >= 11 * min)
    }

    @Test
    fun `reset closes a running candidate`() {
        val d = SessionDetector()
        val out = ArrayList<WalkSession>()
        walk(d, 0, 11 * min, out = out)
        val s = d.reset()
        assertNotNull(s)
        assertNull(d.reset())
    }
}
