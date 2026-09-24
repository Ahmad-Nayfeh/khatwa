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

    init {
        // By default UiAutomation SUPPRESSES every other accessibility service while a test runs,
        // which would keep our own KhatwaAccessibilityService from ever binding. Opt out before
        // the first UiDevice is created.
        androidx.test.uiautomator.Configurator.getInstance()
            .setUiAutomationFlags(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    }

    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Finds a node by exact text and clicks it, retrying once if the node went stale. */
    fun clickText(text: String, timeoutMs: Long = 5_000): Boolean {
        repeat(3) {
            try {
                val obj = device.wait(Until.findObject(By.text(text)), timeoutMs) ?: return false
                obj.click()
                return true
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                Thread.sleep(300)
            }
        }
        return false
    }
    /** Finds a node by resource id (test tag) and clicks it, retrying if it went stale. */
    fun clickRes(resId: String, timeoutMs: Long = 5_000): Boolean {
        repeat(3) {
            try {
                val obj = device.wait(Until.findObject(By.res(resId)), timeoutMs) ?: return false
                obj.click()
                return true
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                Thread.sleep(300)
            }
        }
        return false
    }

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
        // Android 13+ "restricted settings" for sideloaded apps: allow them explicitly (what the
        // user does by hand through App info > Allow restricted settings).
        shell("appops set $PKG ACCESS_RESTRICTED_SETTINGS allow")
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        Log.i(TAG, "enabled services now: " + shell("settings get secure enabled_accessibility_services").trim())
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
            // Deterministic evidence: the dark scheme regardless of the emulator's system theme.
            // (The default for users is "system"; the light-theme test switches explicitly.)
            c.settings.setThemeMode(com.khatwa.app.settings.ThemeMode.DARK)
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
        if (!device.wait(Until.hasObject(By.pkg(PKG)), 10_000)) {
            dismissAnrDialog()
            if (!device.wait(Until.hasObject(By.pkg(PKG)), 5_000)) dump("launch-not-visible")
        }
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

    /**
     * Screenshot + accessibility hierarchy dump for diagnosing failures. The hierarchy goes to
     * logcat (tag KhatwaHierarchy), which CI keeps; the app's own files are gone by then because
     * the test run uninstalls the app.
     */
    fun dump(name: String) {
        screenshot(name)
        try {
            val out = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            out.toString(Charsets.UTF_8.name()).chunked(3_000).forEachIndexed { i, part ->
                Log.i("KhatwaHierarchy", "$name[$i] $part")
            }
            Log.i(TAG, "hierarchy dumped: $name; currentPackage=${device.currentPackageName}")
        } catch (e: Exception) {
            Log.w(TAG, "hierarchy dump failed: $e")
        }
    }

    /**
     * Waits for a node by resource id (test tag), clearing the accessibility cache every two
     * seconds (API 34+). On the CI emulator the cache can keep an old copy of a Compose screen,
     * so a node that is visibly on screen is never found (GroupsTest's leaderboard row).
     */
    fun findRes(resId: String, timeoutMs: Long): androidx.test.uiautomator.UiObject2? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            device.wait(Until.findObject(By.res(resId)), 2_000)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val cleared = automation().clearCache()
                Log.i(TAG, "findRes($resId): accessibility cache cleared=$cleared")
            }
        }
    }

    /** The UiAutomation UiDevice uses (asking with other flags would reconnect it). */
    private fun automation(): android.app.UiAutomation = InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(androidx.test.uiautomator.Configurator.getInstance().uiAutomationFlags)

    /**
     * Scrolls a scroll container one page forward through the accessibility action instead of an
     * injected touch swipe. Touch swipes are unreliable on the software-rendered CI emulator
     * (frames take hundreds of ms), while ACTION_SCROLL_FORWARD is handled by Compose directly.
     */
    fun scrollForward(resId: String): Boolean = scrollPage(resId, forward = true)

    fun scrollBackward(resId: String): Boolean = scrollPage(resId, forward = false)

    private fun scrollPage(resId: String, forward: Boolean): Boolean {
        val root = automation().rootInActiveWindow ?: return false.also { Log.w(TAG, "scrollPage: no root window") }
        val node = findNode(root) { it.viewIdResourceName == resId }
            ?: return false.also { Log.w(TAG, "scrollPage: no node with id $resId") }
        val action = if (forward) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val ok = node.performAction(action)
        Log.i(TAG, "scrollPage($resId, forward=$forward) -> $ok")
        return ok
    }

    private fun findNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        pred: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, pred)?.let { return it }
        }
        return null
    }

    /** Waits for a node and, if it never appears, records a screenshot + hierarchy before failing. */
    fun waitFor(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long, name: String): androidx.test.uiautomator.UiObject2? {
        var obj = device.wait(Until.findObject(selector), timeoutMs)
        if (obj == null) {
            dismissAnrDialog()
            obj = device.wait(Until.findObject(selector), 3_000)
        }
        if (obj == null) dump("missing-$name")
        return obj
    }

    fun wake() {
        shell("settings put global hide_error_dialogs 1")
        dismissAnrDialog()
        shell("settings put global device_provisioned 1")
        shell("settings put secure user_setup_complete 1")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    fun evidence(msg: String) = Log.i(TAG, msg)

    /** The headless emulator sometimes shows "System UI isn't responding"; tap Wait / Close. */
    fun dismissAnrDialog() {
        repeat(2) {
            val wait = device.findObject(By.text("Wait")) ?: device.findObject(By.textContains("isn't responding"))?.let { null }
            if (wait != null) { wait.click(); Thread.sleep(500) }
            else device.findObject(By.text("Close app"))?.let { it.click(); Thread.sleep(500) }
        }
    }

    /** First installed launchable package from the candidates (a "blocked" app for lock tests). */
    fun firstInstalled(vararg candidates: String): String? =
        candidates.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
}
