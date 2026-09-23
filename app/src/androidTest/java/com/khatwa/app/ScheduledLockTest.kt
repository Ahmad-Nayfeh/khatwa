package com.khatwa.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import com.khatwa.app.lock.KhatwaAccessibilityService
import com.khatwa.core.lock.LockState
import com.khatwa.core.lock.ScheduleConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/**
 * Phase 4 evidence: with the goal unmet, the scheduled lock activates by itself at its start
 * time (real AlarmManager alarm), covers a blocked app, and ends by itself at the end time.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledLockTest {
    private val c get() = TestSupport.container

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
        TestSupport.enableAccessibility()
        waitUntil(20_000) { KhatwaAccessibilityService.connected }
        runBlocking {
            c.lock.unlock(com.khatwa.app.lock.UnlockReason.CANCELLED)
            c.settings.setScheduleSkipDate(null)
            c.lock.refreshPolicy()
            // A goal far above whatever earlier tests walked, so "goal not met" holds.
            c.settings.setGoals(null, null, null, manual = 100_000)
            c.tracker.refreshGoalNow()
        }
        waitUntil(10_000) { c.tracker.today.value.goal == 100_000 }
        c.lock.refreshHealth()
    }

    @Test
    fun scheduledLockActivatesAtItsTimeAndEndsAtItsEndTime() {
        val blocked = TestSupport.firstInstalled(
            "com.android.chrome", "com.google.android.calculator", "com.android.calculator2",
            "com.google.android.deskclock", "com.android.deskclock", "com.android.settings",
        )
        assumeTrue(blocked != null)
        val today = c.tracker.today.value
        assumeTrue("goal already met on this emulator run; cannot test scheduled lock", today.steps < today.goal)

        // Start at the next full minute that is at least 15 s away; end one minute later.
        val now = LocalTime.now()
        var startMinute = now.hour * 60 + now.minute + 1
        if (now.second > 45) startMinute += 1
        assumeTrue("too close to midnight for this test", startMinute + 1 < 24 * 60)
        val config = ScheduleConfig(enabled = true, startMinute = startMinute, endMinute = startMinute + 1, days = (1..7).toSet())
        runBlocking {
            c.settings.setSchedule(config)
            c.alarms.scheduleAll(c.settings.current())
        }
        TestSupport.evidence("schedule set: start=$startMinute end=${startMinute + 1} now=$now steps=${today.steps} goal=${today.goal}")

        waitUntil(130_000) { c.lock.state.value is LockState.Scheduled }
        TestSupport.evidence("scheduled lock activated at ${LocalTime.now()}")

        TestSupport.launchPackage(blocked!!)
        val overlay = device.wait(Until.findObject(By.res("lock_remaining")), 10_000)
        assertNotNull("scheduled lock overlay missing", overlay)
        TestSupport.screenshot("30-scheduled-lock-active")

        waitUntil(130_000) { !c.lock.state.value.isActive }
        TestSupport.evidence("scheduled lock ended by time at ${LocalTime.now()}")
        assertTrue(device.wait(Until.gone(By.res("lock_remaining")), 8_000))
        TestSupport.screenshot("31-scheduled-lock-ended")

        runBlocking {
            c.settings.setSchedule(ScheduleConfig(enabled = false))
            c.settings.setGoals(null, null, null, null, clearManual = true)
            c.alarms.scheduleAll(c.settings.current())
        }
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(250)
        }
        assertTrue("timed out", cond())
    }
}
