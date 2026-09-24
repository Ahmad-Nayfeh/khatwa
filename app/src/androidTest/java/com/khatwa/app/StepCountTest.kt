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

/** Phase 1 evidence: steps from the (fake) sensor reach the tracker, the database, and the home screen. */
@RunWith(AndroidJUnit4::class)
class StepCountTest {

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
    }

    @Test
    fun stepsAreCountedAndShownOnHome() {
        TestSupport.launchApp()
        val steps = TestSupport.waitFor(By.res("home_steps"), 15_000, "home_steps")
        assertNotNull("home screen did not show the step counter", steps)
        TestSupport.screenshot("01-home-before-steps")

        val before = TestSupport.container.tracker.today.value.steps
        TestSupport.fake().add(1234)
        val shown = TestSupport.waitFor(By.res("home_steps").text(fmt(before + 1234)), 10_000, "home_1234")
        assertNotNull("home did not update to ${fmt(before + 1234)}", shown)
        TestSupport.screenshot("02-home-after-1234-steps")

        // The database has the open day with the same count. Persistence is throttled, so flush
        // first (a CI run that crossed midnight read the fresh day's row before the write was due).
        val day = runBlocking {
            TestSupport.container.tracker.flush()
            TestSupport.container.db.days().openDay()
        }
        assertNotNull(day)
        assertEquals(before + 1234, day!!.steps)
        TestSupport.evidence("steps today=${day.steps} goal=${day.goal} date=${day.date}")
    }

    @Test
    fun simulatedRebootKeepsSteps() {
        TestSupport.launchApp()
        device.wait(Until.findObject(By.res("home_steps")), 15_000)
        val before = TestSupport.container.tracker.today.value.steps
        TestSupport.fake().add(500)
        Thread.sleep(500)
        TestSupport.fake().reboot()
        Thread.sleep(500)
        TestSupport.fake().add(200)
        val expected = before + 700
        val shown = TestSupport.waitFor(By.res("home_steps").text(fmt(expected)), 10_000, "home_reboot")
        assertNotNull("after reboot home should show ${fmt(expected)}", shown)
        TestSupport.screenshot("03-home-after-reboot")
        assertTrue(TestSupport.container.tracker.today.value.steps == expected)
    }

    private fun fmt(v: Long) = String.format(java.util.Locale.US, "%,d", v)
}
