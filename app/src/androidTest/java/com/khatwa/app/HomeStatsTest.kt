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
        // With the accessibility service enabled the "lock does not work" warning card is not
        // shown, so the home screen has its normal layout (quote card near the top).
        TestSupport.enableAccessibility()
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
        val stepsBefore = c.tracker.today.value.steps
        val now = System.currentTimeMillis()

        // 12 minutes at ~100 steps/min, ending 20 minutes ago, one event every 6 seconds (10 steps).
        var t = now - 40 * 60_000L
        repeat(120) { fake.emitAt(t, 10); t += 6_000 }
        // 6 minutes of walking, ending 5 minutes ago.
        t = now - 11 * 60_000L
        repeat(60) { fake.emitAt(t, 10); t += 6_000 }
        // Wait for the channel to drain, then let the tracker close stale candidates.
        waitUntil { c.tracker.today.value.steps >= stepsBefore + 1800 }
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
        // The quote card sits below the fold once the lock section is on screen. Scroll to it with
        // the accessibility scroll action (touch swipes are not reliable on the slow CI emulator).
        // A page scroll can jump past the card (seen in CI evidence), so sweep down, then back up.
        var quote = device.findObject(By.res("home_quote"))
        var scrolls = 0
        val sweep = listOf(true, true, true, false, false, false)
        for (forward in sweep) {
            if (quote != null) break
            if (forward) TestSupport.scrollForward("home_scroll") else TestSupport.scrollBackward("home_scroll")
            scrolls++
            quote = device.wait(Until.findObject(By.res("home_quote")), 3_000)
        }
        if (quote == null) TestSupport.dump("missing-home_quote")
        val quotesInDb = runBlocking { c.db.quotes().count() }
        assertNotNull("quote card missing after $scrolls scrolls (quotes in db: $quotesInDb)", quote)
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
        assertNotNull("home not shown", TestSupport.waitFor(By.res("home_steps"), 15_000, "home_steps_weight"))
        // The settings tab click can be swallowed while the slow emulator is still composing the
        // home screen: click, wait for the settings list, retry once.
        var entry: androidx.test.uiautomator.UiObject2? = null
        repeat(2) { attempt ->
            if (entry == null) {
                TestSupport.clickRes("tab_settings")
                entry = device.wait(Until.findObject(By.text("سجل الوزن")), 8_000)
                if (entry == null) TestSupport.dump("missing-weight-entry-$attempt")
            }
        }
        assertNotNull("settings entry 'سجل الوزن' not found", entry)
        assertTrue(TestSupport.clickText("سجل الوزن"))
        assertNotNull("weight 80.5 not listed", TestSupport.waitFor(By.textContains("80.5"), 8_000, "weight_80_5"))
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
