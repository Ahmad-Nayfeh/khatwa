package com.khatwa.app

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import com.khatwa.app.lock.KhatwaAccessibilityService
import com.khatwa.app.notifications.Notifications
import com.khatwa.core.lock.LockState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 evidence on the emulator: the manual lock blocks a non-allowed app, lets an allowed
 * app through, unlocks when the steps are reached, the emergency exit records a surrender,
 * and a disabled accessibility service shows the warning.
 */
@RunWith(AndroidJUnit4::class)
class LockFlowTest {

    private val c get() = TestSupport.container

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
        TestSupport.enableAccessibility()
        waitUntil(20_000, "accessibility service connected") { KhatwaAccessibilityService.connected }
        runBlocking { c.lock.unlock(com.khatwa.app.lock.UnlockReason.CANCELLED) }
        runBlocking { c.lock.refreshPolicy() }
        c.lock.refreshHealth()
    }

    private fun blockedApp(): String {
        // Lightest candidates first: Chrome renders through SwiftShader/Vulkan on the CI emulator and
        // starves the runner (QEMU "hanging thread" errors, then the runner is shut down).
        val pkg = TestSupport.firstInstalled(
            "com.google.android.deskclock", "com.android.deskclock", "com.google.android.calculator",
            "com.android.calculator2", "com.android.settings", "com.android.chrome",
            "com.google.android.apps.maps",
        )
        assumeTrue("no blockable app on this image", pkg != null)
        return pkg!!
    }

    private fun allowedApp(): String? {
        val dialer = com.khatwa.app.lock.AllowlistDefaults.dialer(TestSupport.context)
        return dialer?.takeIf { TestSupport.context.packageManager.getLaunchIntentForPackage(it) != null }
    }

    @Test
    fun manualLockBlocksAllowsAndUnlocks() {
        val blocked = blockedApp()
        val allowed = allowedApp()
        val pairSecret = "12345678" // shared vector: counter 1 -> lock 179578, unlock 880387
        runBlocking { c.settings.setLaptopSecret(pairSecret); c.lock.startManual(1000) }
        assertTrue(c.lock.state.value is LockState.Manual)
        TestSupport.evidence("lock started; blocked=$blocked allowed=$allowed policy=${c.lock.currentPolicy().decide(blocked)}")

        // 0. The laptop lock code for this challenge is shown on Home (the phone is paired).
        val ch = c.lock.challenge.value
        assertNotNull("challenge id created with the lock", ch)
        TestSupport.launchApp()
        val lockCodeShown = TestSupport.waitFor(By.res("home_lock_code"), 10_000, "home_lock_code")
        assertNotNull("laptop lock code missing on Home", lockCodeShown)
        assertEquals("first challenge after pairing", 1L, ch!!.counter)
        assertEquals("179 578", lockCodeShown!!.text)
        // A freshly paired laptop (last counter 0) recognises it as challenge 1.
        assertEquals(1L, com.khatwa.core.laptop.LaptopCode.verifyLockCode(pairSecret, lockCodeShown.text, 0))
        assertTrue("no copy button any more", !device.hasObject(By.res("home_lock_code_copy")))
        TestSupport.evidence("laptop lock code shown: ${lockCodeShown.text}")
        TestSupport.screenshot("19-home-laptop-lock-code")

        // 1. A blocked app gets covered by the lock screen.
        TestSupport.launchPackage(blocked)
        val overlay = device.wait(Until.findObject(By.res("lock_remaining")), 10_000)
        assertNotNull("lock overlay did not appear over $blocked", overlay)
        assertEquals("1,000", overlay.text)
        TestSupport.screenshot("20-lock-over-blocked-app")

        // 2. An allowed app (the dialer) is not covered.
        if (allowed != null) {
            TestSupport.launchPackage(allowed)
            assertTrue("overlay should hide over allowed app $allowed", device.wait(Until.gone(By.res("lock_remaining")), 8_000))
            device.wait(Until.hasObject(By.pkg(allowed)), 5_000)
            TestSupport.screenshot("21-allowed-app-not-covered")
            assertTrue(device.currentPackageName == allowed || !device.hasObject(By.res("lock_remaining")))
        } else {
            TestSupport.evidence("no dialer on this image; allowed-app step skipped")
        }

        // 3. Back to the blocked app: covered again; walking 1000 steps unlocks it.
        TestSupport.launchPackage(blocked)
        assertNotNull(device.wait(Until.findObject(By.res("lock_remaining")), 10_000))
        TestSupport.fake().add(600)
        assertNotNull(device.wait(Until.findObject(By.res("lock_remaining").text("400")), 8_000))
        TestSupport.screenshot("22-lock-400-remaining")
        TestSupport.fake().add(400)
        assertTrue("overlay should disappear after 1000 steps", device.wait(Until.gone(By.res("lock_remaining")), 8_000))
        waitUntil(5_000, "lock state cleared") { !c.lock.state.value.isActive }
        TestSupport.screenshot("23-unlocked-blocked-app-visible")

        val nm = TestSupport.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        waitUntil(5_000, "well-done notification") { nm.activeNotifications.any { it.id == Notifications.ID_UNLOCKED } }
        TestSupport.evidence("unlocked automatically; 'أحسنت' notification posted")

        // 4. Home now shows the laptop unlock code for the finished challenge.
        TestSupport.launchApp()
        val unlockShown = TestSupport.waitFor(By.res("home_unlock_code"), 10_000, "home_unlock_code")
        assertNotNull("laptop unlock code missing on Home", unlockShown)
        assertEquals("880 387", unlockShown!!.text)
        assertTrue(com.khatwa.core.laptop.LaptopCode.verifyUnlockCode(pairSecret, 1, unlockShown.text))
        TestSupport.evidence("laptop unlock code shown: ${unlockShown.text}")
        TestSupport.screenshot("28-home-laptop-unlock-code")
        assertTrue(TestSupport.clickRes("home_unlock_dismiss"))
        waitUntil(5_000, "unlock card dismissed") { c.lock.challenge.value == null }
    }

    @Test
    fun emergencyExitRequiresWaitingAndPhraseAndRecordsSurrender() {
        val blocked = blockedApp()
        val before = runBlocking { c.db.surrenders().count() }
        runBlocking { c.lock.startManual(2000) }
        TestSupport.launchPackage(blocked)
        assertNotNull(device.wait(Until.findObject(By.res("lock_remaining")), 10_000))

        device.findObject(By.res("lock_emergency")).click()
        assertNotNull("countdown should be visible", device.wait(Until.findObject(By.res("lock_countdown")), 5_000))
        TestSupport.screenshot("24-emergency-countdown")
        // Still locked while waiting.
        assertFalse(device.hasObject(By.res("lock_phrase")))
        val phrase = device.wait(Until.findObject(By.res("lock_phrase")), 70_000)
        assertNotNull("phrase field should appear after the 60 s wait", phrase)
        // A wrong phrase does not enable "continue".
        phrase.text = "جملة خاطئة"
        Thread.sleep(500)
        assertFalse(device.findObject(By.res("lock_continue")).isEnabled)
        // The real keyboard: tap the field like a person does. The keyboard window appearing must
        // not reset the emergency screen or take the lock away (it did on a real phone).
        device.findObject(By.res("lock_phrase")).click()
        Thread.sleep(2_000)
        val keyboard = device.executeShellCommand("dumpsys input_method").lines().firstOrNull { "mInputShown" in it }?.trim()
        TestSupport.evidence("keyboard after tapping the phrase field: $keyboard")
        TestSupport.screenshot("24b-emergency-keyboard")
        val field = device.findObject(By.res("lock_phrase"))
        assertNotNull("the emergency screen was reset when the keyboard opened", field)
        assertEquals("جملة خاطئة", field.text)
        assertTrue(c.lock.overlay.isShown)
        device.pressBack() // closes the keyboard only; the lock screen ignores Back
        Thread.sleep(500)
        // Typed the way people type: no hamza on the alefs, no diacritics. It must still be accepted.
        device.findObject(By.res("lock_phrase")).text = TYPED_PHRASE
        device.wait(Until.findObject(By.res("lock_continue").enabled(true)), 5_000).click()
        device.wait(Until.findObject(By.res("lock_confirm")), 5_000).click()
        TestSupport.screenshot("25-emergency-confirmed")

        assertTrue(device.wait(Until.gone(By.res("lock_remaining")), 8_000))
        waitUntil(5_000, "lock cleared") { !c.lock.state.value.isActive }
        val after = runBlocking { c.db.surrenders().count() }
        assertEquals(before + 1, after)
        val last = runBlocking { c.db.surrenders().all().last() }
        TestSupport.evidence("surrender recorded: remaining=${last.remainingSteps} type=${last.lockType}")
        assertEquals(2000L, last.remainingSteps)
    }

    @Test
    fun popUpWindowsNeverBringTheLockOverTheApp() {
        val blocked = blockedApp()
        runBlocking { c.lock.startManual(2000) }
        TestSupport.launchApp()
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
        val main = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val ime = com.khatwa.app.lock.AllowlistDefaults.inputMethods(TestSupport.context).firstOrNull()
        // What a keyboard, an autofill list or a dialog of another app report when they appear
        // over our app (e.g. while typing the emergency phrase on Home).
        main.runOnMainSync {
            ime?.let { c.lock.onForeground(it, "android.inputmethodservice.SoftInputWindow") }
            c.lock.onForeground(blocked, "android.widget.PopupWindow\$PopupDecorView")
            c.lock.onForeground(blocked, "android.app.Dialog")
        }
        assertFalse("a pop-up window brought the lock screen over the app", c.lock.overlay.isShown)
        assertFalse(device.hasObject(By.res("lock_remaining")))
        // An actual screen (activity) of the blocked app is still covered.
        val activity = TestSupport.context.packageManager.getLaunchIntentForPackage(blocked)!!.component!!.className
        main.runOnMainSync { c.lock.onForeground(blocked, activity) }
        assertTrue("an activity of $blocked ($activity) must still be locked", c.lock.overlay.isShown)
        TestSupport.evidence("pop-ups ignored (ime=$ime); activity $activity locked")
        main.runOnMainSync { c.lock.overlay.hide() }
    }

    @Test
    fun aLockStartedFromHomeDoesNotCoverHome() {
        val blocked = blockedApp()
        // The last app screen before ours was a blocked app (e.g. the app was opened from a notification).
        val activity = TestSupport.context.packageManager.getLaunchIntentForPackage(blocked)!!.component!!.className
        val main = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        main.runOnMainSync { c.lock.onForeground(blocked, activity) }
        TestSupport.launchApp()
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
        main.runOnMainSync { c.lock.onForeground(TestSupport.PKG, "com.khatwa.app.ui.MainActivity") }
        runBlocking { c.lock.startManual(1000) }
        main.runOnMainSync { } // let the posted re-check run
        assertFalse("the lock screen covered our own Home", c.lock.overlay.isShown)
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 5_000))
    }

    @Test
    fun disabledAccessibilityShowsWarningOnHome() {
        TestSupport.disableAccessibility()
        waitUntil(10_000, "accessibility disconnected") { !KhatwaAccessibilityService.connected }
        TestSupport.launchApp()
        val warning = device.wait(Until.findObject(By.res("lock_warning")), 15_000)
        assertNotNull("'القفل لا يعمل' warning missing", warning)
        TestSupport.screenshot("26-accessibility-disabled-warning")
        // Re-enable and the warning goes away on the next open.
        TestSupport.enableAccessibility()
        waitUntil(20_000, "accessibility reconnected") { KhatwaAccessibilityService.connected }
        TestSupport.launchApp()
        device.wait(Until.findObject(By.res("home_steps")), 15_000)
        assertTrue(device.wait(Until.gone(By.res("lock_warning")), 10_000))
        TestSupport.screenshot("27-accessibility-enabled-no-warning")
    }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(200)
        }
        assertTrue("timed out waiting for: $what", cond())
    }

    companion object {
        /** The default phrase typed the way people type: no hamza, no commas. */
        const val TYPED_PHRASE = "اختار الاستسلام اليوم بدلا من المشي واعلم ان هذا يسجل علي واعد نفسي ان احاول من جديد غدا"
    }
}
