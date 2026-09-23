package com.khatwa.app.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.khatwa.app.KhatwaApp
import com.khatwa.app.settings.Settings
import com.khatwa.app.steps.StepService
import com.khatwa.core.time.Days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Exact alarms for: midnight rollover, scheduled lock (warn / start / end), morning quote,
 * weight reminder. Alarms are one-shot and re-armed after each firing.
 */
class AlarmScheduler(private val context: Context) {
    private val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleAll(settings: Settings) {
        scheduleMidnight()
        AlarmKinds.scheduleFeatureAlarms(this, settings)
    }

    fun scheduleMidnight() {
        val at = Days.nextMidnightMs(System.currentTimeMillis(), ZoneId.systemDefault()) + 1_500
        setExact(AlarmReceiver.ACTION_MIDNIGHT, at, REQ_MIDNIGHT)
    }

    fun cancel(action: String, requestCode: Int) {
        am.cancel(pending(action, requestCode))
    }

    fun setExact(action: String, atMs: Long, requestCode: Int) {
        val pi = pending(action, requestCode)
        am.cancel(pi)
        if (atMs <= System.currentTimeMillis()) return
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
        Log.d(TAG, "alarm $action at ${java.time.Instant.ofEpochMilli(atMs)} exact=$canExact")
    }

    private fun pending(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "AlarmScheduler"
        const val REQ_MIDNIGHT = 1
        const val REQ_LOCK_WARN = 2
        const val REQ_LOCK_START = 3
        const val REQ_LOCK_END = 4
        const val REQ_MORNING = 5
        const val REQ_WEIGHT = 6
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("AlarmReceiver", "fired $action")
        val pending = goAsync()
        val container = KhatwaApp.container(context)
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_MIDNIGHT -> {
                        container.tracker.rolloverIfNeeded()
                        container.tracker.flush()
                        container.alarms.scheduleMidnight()
                        StepService.start(context)
                        AlarmKinds.onMidnight(container)
                    }
                    else -> AlarmKinds.handle(container, action)
                }
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "failed $action", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.khatwa.app.alarm.MIDNIGHT"
        const val ACTION_LOCK_WARN = "com.khatwa.app.alarm.LOCK_WARN"
        const val ACTION_LOCK_START = "com.khatwa.app.alarm.LOCK_START"
        const val ACTION_LOCK_END = "com.khatwa.app.alarm.LOCK_END"
        const val ACTION_MORNING = "com.khatwa.app.alarm.MORNING"
        const val ACTION_WEIGHT = "com.khatwa.app.alarm.WEIGHT"
    }
}

/** Helper for today-relative alarm times. */
object AlarmTimes {
    /** Next occurrence (>= now + 1s) of [minuteOfDay] on one of [days] (ISO 1..7; empty = every day). */
    fun next(minuteOfDay: Int, days: Set<Int>, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        var date = Days.localDate(nowMs, zone)
        repeat(8) {
            if (days.isEmpty() || date.dayOfWeek.value in days) {
                val at = Days.atTime(date, minuteOfDay / 60, minuteOfDay % 60, zone)
                if (at > nowMs + 1_000) return at
            }
            date = date.plusDays(1)
        }
        return Days.atTime(LocalDate.now(zone).plusDays(1), minuteOfDay / 60, minuteOfDay % 60, zone)
    }
}
