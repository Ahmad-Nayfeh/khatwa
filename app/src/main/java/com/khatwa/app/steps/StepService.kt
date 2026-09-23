package com.khatwa.app.steps

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.khatwa.app.KhatwaApp
import com.khatwa.app.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Light foreground service: keeps the step sensor registered and a quiet, persistent
 * notification with today's steps and goal. It is the near-real-time source for the lock.
 */
class StepService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private var source: StepSource? = null
    private var notificationJob: Job? = null

    private val tracker get() = KhatwaApp.container(this).tracker

    private val ticker = object : Runnable {
        override fun run() {
            scope.launch {
                runCatching { tracker.tick() }.onFailure { Log.w(TAG, "tick failed", it) }
                if (System.currentTimeMillis() - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
                    lastSnapshotMs = System.currentTimeMillis()
                    runCatching { tracker.snapshot() }
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }
    private var lastSnapshotMs = 0L

    override fun onCreate() {
        super.onCreate()
        val container = KhatwaApp.container(this)
        startInForeground()
        scope.launch { tracker.load() }
        val src = StepSourceFactory.create(this)
        source = src
        val started = src.start { reading, eventMs ->
            scope.launch { runCatching { tracker.onReading(reading, eventMs) }.onFailure { Log.e(TAG, "reading failed", it) } }
        }
        Log.i(TAG, "step source '${src.name}' started=$started available=${src.available}")
        lastSnapshotMs = System.currentTimeMillis()
        handler.postDelayed(ticker, TICK_MS)
        notificationJob = scope.launch {
            tracker.today.collect { today ->
                container.notifications.updateServiceNotification(today)
                kotlinx.coroutines.delay(NOTIFICATION_THROTTLE_MS)
            }
        }
        container.alarms.scheduleMidnight()
    }

    private fun startInForeground() {
        val notification = KhatwaApp.container(this).notifications.buildServiceNotification(tracker.today.value)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ServiceCompat.startForeground(this, Notifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                Log.i(TAG, "foreground type: health")
            } catch (e: Exception) {
                // health requires ACTIVITY_RECOGNITION at call time; fall back to specialUse.
                Log.w(TAG, "health type refused (${e.javaClass.simpleName}), using specialUse")
                ServiceCompat.startForeground(this, Notifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, Notifications.SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(Notifications.SERVICE_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        source?.stop()
        notificationJob?.cancel()
        runBlocking { runCatching { tracker.flush() } }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "StepService"
        const val ACTION_STOP = "com.khatwa.app.action.STOP_SERVICE"
        private const val TICK_MS = 60_000L
        private const val SNAPSHOT_INTERVAL_MS = 15 * 60_000L
        private const val NOTIFICATION_THROTTLE_MS = 3_000L

        /** Starts the service if the step permission is granted. Safe to call repeatedly. */
        fun start(context: Context): Boolean {
            if (!com.khatwa.app.permissions.PermissionChecks.activityRecognition(context)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, StepService::class.java))
                true
            } catch (e: Exception) {
                // Background start restrictions (Android 12+) when called from a worker.
                Log.w(TAG, "could not start service: ${e.javaClass.simpleName}")
                false
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, StepService::class.java).setAction(ACTION_STOP))
        }
    }
}
