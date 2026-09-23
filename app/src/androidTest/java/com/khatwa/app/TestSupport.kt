package com.khatwa.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.khatwa.app.debug.FakeStepSource
import com.khatwa.app.steps.StepService
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * Shared helpers for the emulator tests. Tests run inside the app process, so they can talk
 * to the container directly; shell commands run with the `shell` uid via UiAutomator.
 */
object TestSupport {
    const val PKG = "com.khatwa.app"
    const val EVIDENCE_DIR = "/sdcard/khatwa-evidence"
    private const val TAG = "KhatwaEvidence"

    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val container: AppContainer get() = KhatwaApp.container(context)

    fun shell(cmd: String): String = device.executeShellCommand(cmd).also { Log.i(TAG, "$ $cmd\n$it") }

    fun grantBasics() {
        wake()
        shell("pm grant $PKG android.permission.ACTIVITY_RECOGNITION")
        shell("pm grant $PKG android.permission.POST_NOTIFICATIONS")
        shell("appops set $PKG SYSTEM_ALERT_WINDOW allow")
        shell("mkdir -p $EVIDENCE_DIR")
        shell("settings put global window_animation_scale 0")
        shell("settings put global transition_animation_scale 0")
        shell("settings put global animator_duration_scale 0")
    }

    fun enableAccessibility() {
        val component = "$PKG/com.khatwa.app.lock.KhatwaAccessibilityService"
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
    }

    fun disableAccessibility() {
        shell("settings put secure enabled_accessibility_services \"\"")
        shell("settings put secure accessibility_enabled 0")
    }

    /** Mark onboarding done with default settings and use the fake step source. */
    fun onboardWithFakeSteps() {
        FakeStepSource.setEnabled(context, true)
        val c = container
        runBlocking {
            c.settings.setOnboardingDone(LocalDate.now())
            c.tracker.load()
        }
        StepService.stop(context)
        Thread.sleep(700)
        // Start from the foreground (the activity), exactly like a user would.
        launchApp()
        val started = StepService.start(context)
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && !fake().isStarted) Thread.sleep(200)
        Log.i(TAG, "service start requested=$started fakeStarted=${fake().isStarted}")
        check(fake().isStarted) { "StepService did not start with the fake step source" }
    }

    fun fake(): FakeStepSource = FakeStepSource.get(context)

    fun launchApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(PKG)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        if (!device.wait(Until.hasObject(By.pkg(PKG)), 10_000)) dump("launch-not-visible")
    }

    fun launchPackage(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        requireNotNull(intent) { "no launch intent for $pkg" }
        context.startActivity(intent)
    }

    fun screenshot(name: String) {
        shell("screencap -p $EVIDENCE_DIR/$name.png")
        Log.i(TAG, "screenshot $name")
    }

    /** Screenshot + accessibility hierarchy dump (pulled by CI via run-as) for diagnosing failures. */
    fun dump(name: String) {
        screenshot(name)
        try {
            val dir = java.io.File(context.filesDir, "evidence").apply { mkdirs() }
            device.dumpWindowHierarchy(java.io.File(dir, "$name.xml"))
            Log.i(TAG, "hierarchy dumped: $name; currentPackage=${device.currentPackageName}")
        } catch (e: Exception) {
            Log.w(TAG, "hierarchy dump failed: $e")
        }
    }

    /** Waits for a node and, if it never appears, records a screenshot + hierarchy before failing. */
    fun waitFor(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long, name: String): androidx.test.uiautomator.UiObject2? {
        val obj = device.wait(Until.findObject(selector), timeoutMs)
        if (obj == null) dump("missing-$name")
        return obj
    }

    fun wake() {
        shell("settings put global device_provisioned 1")
        shell("settings put secure user_setup_complete 1")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    fun evidence(msg: String) = Log.i(TAG, msg)

    /** First installed launchable package from the candidates (a "blocked" app for lock tests). */
    fun firstInstalled(vararg candidates: String): String? =
        candidates.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
}
