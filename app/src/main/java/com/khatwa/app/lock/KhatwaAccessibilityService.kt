package com.khatwa.app.lock

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.khatwa.app.KhatwaApp

/**
 * Watches which app is in the foreground. Only package names are read (canRetrieveWindowContent
 * is false in the service config), never screen content. The decision itself lives in
 * [LockController].
 */
class KhatwaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.i(TAG, "connected")
        val lock = KhatwaApp.container(this).lock
        lock.refreshHealth()
        lock.reapply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        KhatwaApp.container(this).lock.onForeground(pkg, event.className?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        KhatwaApp.container(this).lock.onAccessibilityGone()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        KhatwaApp.container(this).lock.onAccessibilityGone()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KhatwaA11y"
        @Volatile var connected: Boolean = false
            private set
    }
}
