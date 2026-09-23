package com.khatwa.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.khatwa.app.KhatwaApp
import com.khatwa.app.steps.StepService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * adb hooks for manual testing on an emulator (debug build only):
 *
 *   adb shell am broadcast -n com.khatwa.app/.debug.DebugReceiver -a com.khatwa.app.debug.ENABLE_FAKE
 *   adb shell am broadcast -n com.khatwa.app/.debug.DebugReceiver -a com.khatwa.app.debug.ADD_STEPS --ei steps 500
 *   adb shell am broadcast -n com.khatwa.app/.debug.DebugReceiver -a com.khatwa.app.debug.REBOOT
 *   adb shell am broadcast -n com.khatwa.app/.debug.DebugReceiver -a com.khatwa.app.debug.ONBOARD
 *   adb shell am broadcast -n com.khatwa.app/.debug.DebugReceiver -a com.khatwa.app.debug.DUMP
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = KhatwaApp.container(context)
        when (intent.action) {
            ACTION_ENABLE_FAKE -> {
                FakeStepSource.setEnabled(context, true)
                Log.i(TAG, "fake source enabled (restart the service to apply)")
                StepService.stop(context)
                StepService.start(context)
            }
            ACTION_ADD_STEPS -> {
                val steps = intent.getIntExtra("steps", 100).toLong()
                val events = intent.getIntExtra("events", 1)
                val spread = intent.getLongExtra("spreadMs", 0L)
                FakeStepSource.get(context).add(steps, events, spread)
            }
            ACTION_REBOOT -> FakeStepSource.get(context).reboot()
            ACTION_ONBOARD -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        container.settings.setOnboardingDone(LocalDate.now())
                        StepService.start(context)
                    } finally { pending.finish() }
                }
            }
            ACTION_DUMP -> {
                val t = container.tracker.today.value
                Log.i(TAG, "DUMP today=${t.date} steps=${t.steps} goal=${t.goal} sensorSeen=${t.sensorSeen}")
            }
        }
    }

    companion object {
        private const val TAG = "KhatwaDebug"
        const val ACTION_ENABLE_FAKE = "com.khatwa.app.debug.ENABLE_FAKE"
        const val ACTION_ADD_STEPS = "com.khatwa.app.debug.ADD_STEPS"
        const val ACTION_REBOOT = "com.khatwa.app.debug.REBOOT"
        const val ACTION_ONBOARD = "com.khatwa.app.debug.ONBOARD"
        const val ACTION_DUMP = "com.khatwa.app.debug.DUMP"
    }
}
