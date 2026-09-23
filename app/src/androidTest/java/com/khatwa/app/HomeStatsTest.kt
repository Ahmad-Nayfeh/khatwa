package com.khatwa.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 2 evidence: sessions are detected from timestamped events; home and stats render. */
@RunWith(AndroidJUnit4::class)
class HomeStatsTest {

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
    }

    @Test
    fun twelveMinuteWalkBecomesASessionAndSixMinutesDoesNot() {
        val c = TestSupport.container
        val fake = TestSupport.fake()
        // Fresh in-memory detector: earlier tests fed "now" events, and our synthetic
        // timestamps are in the past.
        runBlocking { c.tracker.reload() }
        val before = runBlocking { c.db.sessions().all().size }
        val now = System.currentTimeMillis()

        // 12 minutes at ~100 steps/min, ending 20 minutes ago, one event every 6 seconds (10 steps).
        var t = now - 40 * 60_000L
        repeat(120) { fake.emitAt(t, 10); t += 6_000 }
        // 6 minutes of walking, ending 5 minutes ago.
        t = now - 11 * 60_000L
        repeat(60) { fake.emitAt(t, 10); t += 6_000 }
        // Wait for the channel to drain, then let the tracker close stale candidates.
        waitUntil { c.tracker.today.value.steps >= 1800 }
        runBlocking { c.tracker.tick() }

        val sessions = runBlocking { c.db.sessions().all() }
        assertEquals("exactly one new session expected", before + 1, sessions.size)
        val s = sessions.last()
        val minutes = (s.endMs - s.startMs) / 60_000.0
        TestSupport.evidence("session detected: ${"%.1f".format(minutes)} min, ${s.steps} steps")
        assertTrue("session should be ~12 min, was $minutes", minutes in 11.0..12.5)

        TestSupport.launchApp()
        val steps = device.wait(Until.findObject(By.res("home_steps")), 15_000)
        assertNotNull(steps)
        assertNotNull("quote card missing", device.wait(Until.findObject(By.res("home_quote")), 5_000))
        TestSupport.screenshot("10-home-with-session")

        // Stats tab
        device.findObject(By.res("tab_stats"))?.click()
        device.wait(Until.hasObject(By.textContains("آخر 30 يوماً")), 5_000)
        runBlocking { c.tracker.snapshot() }
        Thread.sleep(800)
        TestSupport.screenshot("11-stats")
        assertTrue(device.hasObject(By.textContains("جلسات المشي")))
    }

    @Test
    fun weightLogSavesAnEntry() {
        val c = TestSupport.container
        runBlocking {
            c.db.weights().insert(com.khatwa.app.data.WeightEntity(date = java.time.LocalDate.now().toString(), kg = 80.5, createdMs = System.currentTimeMillis()))
        }
        TestSupport.launchApp()
        device.wait(Until.findObject(By.res("home_steps")), 15_000)
        device.findObject(By.res("tab_settings"))?.click()
        device.wait(Until.hasObject(By.text("سجل الوزن")), 5_000)
        device.findObject(By.text("سجل الوزن"))?.click()
        assertNotNull(device.wait(Until.findObject(By.textContains("80.5")), 5_000))
        TestSupport.screenshot("12-weight-log")
    }

    private fun waitUntil(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(200)
        }
        assertTrue("condition not met in time", cond())
    }
}
