package com.khatwa.app.lock

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app is in the foreground. Only package names are read (canRetrieveWindowContent
 * is false in the service config), never screen content. The lock logic is added in the lock phase.
 */
class KhatwaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        LockHooks.onForegroundPackage(this, pkg, event.className?.toString())
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        connected = false
        LockHooks.onServiceGone(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KhatwaA11y"
        @Volatile var connected: Boolean = false
            private set
    }
}

/** Entry points filled in by the lock phase. Kept here so the service compiles before that. */
object LockHooks {
    fun onForegroundPackage(service: KhatwaAccessibilityService, pkg: String, className: String?) = Unit
    fun onServiceGone(service: KhatwaAccessibilityService) = Unit
}
