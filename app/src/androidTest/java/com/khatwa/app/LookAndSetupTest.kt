package com.khatwa.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import com.khatwa.app.settings.ThemeMode
import com.khatwa.app.ui.theme.LivingBackgroundPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Evidence for the look of the app: the living background at dawn, day, sunset and night in both
 * themes, a large step count that must fit its ring, and the first setup screen (the account)
 * with the warning shown when it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class LookAndSetupTest {
    private val c get() = TestSupport.container

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
    }

    @Test
    fun backgroundThroughTheDayInBothThemes() {
        // A big number (tens of thousands) must shrink to fit the ring.
        TestSupport.fake().add(48_765)
        try {
            for ((mode, name) in listOf(ThemeMode.DARK to "dark", ThemeMode.LIGHT to "light")) {
                runBlocking { c.settings.setThemeMode(mode) }
                for ((hour, label) in listOf(7f to "dawn", 12f to "day", 18.7f to "sunset", 23f to "night")) {
                    LivingBackgroundPreview.hour = hour
                    TestSupport.launchApp()
                    assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
                    Thread.sleep(1_200)
                    TestSupport.screenshot("70-bg-$name-$label")
                }
            }
            // The steps text stays inside the ring (its bounds are narrower than the ring).
            val steps = device.findObject(By.res("home_steps"))
            TestSupport.evidence("home_steps '${steps.text}' width=${steps.visibleBounds.width()} px")
            assertTrue("steps text wider than the ring", steps.visibleBounds.width() < device.displayWidth * 0.55)
        } finally {
            LivingBackgroundPreview.hour = null
            runBlocking { c.settings.setThemeMode(ThemeMode.DARK) }
        }
    }

    @Test
    fun firstSetupScreenIsTheAccountAndSkippingWarns() {
        assertTrue("groups not configured in this build", c.groups.configured)
        c.groups.signOut()
        // Back to first-time setup (everything else kept).
        runBlocking { c.settings.importMap(c.settings.exportMap() + ("onboarding_done" to "false")) }
        try {
            TestSupport.launchApp()
            assertNotNull("account form is not the first screen", device.wait(Until.findObject(By.res("groups_email")), 15_000))
            TestSupport.screenshot("75-setup-account")
            assertTrue(TestSupport.clickRes("onboarding_next")) // "Skip" without an account
            assertNotNull("no warning when skipping", device.wait(Until.findObject(By.res("onboarding_skip_confirm")), 5_000))
            TestSupport.screenshot("76-setup-skip-warning")
            assertTrue(TestSupport.clickRes("onboarding_skip_confirm"))
            assertTrue("still on the account screen", device.wait(Until.gone(By.res("groups_email")), 5_000))
            TestSupport.evidence("setup: account first, skip warning shown, then the welcome screen")
        } finally {
            runBlocking { c.settings.setOnboardingDone(LocalDate.now()) }
        }
    }
}
