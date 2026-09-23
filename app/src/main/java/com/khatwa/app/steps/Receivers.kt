package com.khatwa.app.steps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.khatwa.app.KhatwaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/** After a reboot (or an app update) restart the service and the alarms. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("BootReceiver", "received $action")
        val pending = goAsync()
        val container = KhatwaApp.container(context)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = container.settings.current()
                if (settings.onboardingDone) {
                    container.tracker.load()
                    container.tracker.rolloverIfNeeded()
                    StepService.start(context)
                    container.alarms.scheduleAll(settings)
                    SnapshotWorker.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Timezone / clock changes may move "today"; re-evaluate the open day and the midnight alarm. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("TimeChangeReceiver", "received ${intent.action}")
        val pending = goAsync()
        val container = KhatwaApp.container(context)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                container.tracker.onZoneChanged()
                container.tracker.rolloverIfNeeded()
                container.alarms.scheduleAll(container.settings.current())
            } finally {
                pending.finish()
            }
        }
    }
}
