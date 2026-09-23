package com.khatwa.app.steps

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.khatwa.app.KhatwaApp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * WorkManager backup that runs every 15 minutes even if the foreground service was killed:
 * takes one fresh sensor reading, applies midnight rollover, stores a snapshot, and tries to
 * revive the service.
 */
class SnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = KhatwaApp.container(applicationContext)
        val settings = container.settings.current()
        if (!settings.onboardingDone) return Result.success()
        val tracker = container.tracker
        tracker.load()
        tracker.rolloverIfNeeded()
        readOnce()?.let { (reading, timeMs) -> tracker.onReading(reading, timeMs) }
        tracker.tick()
        tracker.snapshot()
        tracker.flush()
        StepService.start(applicationContext)
        container.alarms.scheduleMidnight()
        return Result.success()
    }

    /** Register the step source and wait for its first (current) value. */
    private suspend fun readOnce(): Pair<Long, Long>? {
        val source = StepSourceFactory.create(applicationContext)
        if (!source.available) return null
        return try {
            withTimeoutOrNull(READ_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val ok = source.start { reading, eventMs ->
                        if (cont.isActive) cont.resume(reading to eventMs)
                    }
                    if (!ok && cont.isActive) cont.resume(null)
                    cont.invokeOnCancellation { source.stop() }
                }
            }
        } finally {
            source.stop()
        }
    }

    companion object {
        private const val TAG = "SnapshotWorker"
        private const val NAME = "khatwa-snapshot"
        private const val READ_TIMEOUT_MS = 8_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SnapshotWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Log.d(TAG, "scheduled")
        }
    }
}
