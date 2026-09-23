package com.khatwa.app.alarms

import android.util.Log
import com.khatwa.app.AppContainer
import com.khatwa.app.lock.UnlockReason
import com.khatwa.app.notifications.Notifications
import com.khatwa.app.settings.Settings
import com.khatwa.app.util.Fmt
import com.khatwa.core.lock.LockState
import com.khatwa.core.time.Days
import java.time.LocalDate
import java.time.ZoneId

/**
 * Scheduled daily lock: at [com.khatwa.core.lock.ScheduleConfig.startMinute] on enabled days,
 * if today's goal is not met, the lock activates until the goal is met or the end time passes.
 * A quiet warning fires 30 minutes before. Alarms are re-armed after every firing and on boot;
 * [checkNow] catches the case where the phone was off when the start alarm should have fired.
 */
object ScheduledLock {
    private const val TAG = "ScheduledLock"

    fun schedule(scheduler: AlarmScheduler, settings: Settings) {
        val s = settings.schedule
        if (!s.enabled || s.days.isEmpty()) {
            scheduler.cancel(AlarmReceiver.ACTION_LOCK_WARN, AlarmScheduler.REQ_LOCK_WARN)
            scheduler.cancel(AlarmReceiver.ACTION_LOCK_START, AlarmScheduler.REQ_LOCK_START)
            scheduler.cancel(AlarmReceiver.ACTION_LOCK_END, AlarmScheduler.REQ_LOCK_END)
            return
        }
        val warnMinute = (s.startMinute - s.warnMinutesBefore).coerceAtLeast(0)
        scheduler.setExact(AlarmReceiver.ACTION_LOCK_WARN, AlarmTimes.next(warnMinute, s.days), AlarmScheduler.REQ_LOCK_WARN)
        scheduler.setExact(AlarmReceiver.ACTION_LOCK_START, AlarmTimes.next(s.startMinute, s.days), AlarmScheduler.REQ_LOCK_START)
        // The end alarm belongs to the same day as the start; next(endMinute) on enabled days is
        // correct as long as end > start, which the settings screen enforces.
        scheduler.setExact(AlarmReceiver.ACTION_LOCK_END, AlarmTimes.next(s.endMinute, s.days), AlarmScheduler.REQ_LOCK_END)
    }

    suspend fun handle(container: AppContainer, action: String) {
        when (action) {
            AlarmReceiver.ACTION_LOCK_WARN -> warn(container)
            AlarmReceiver.ACTION_LOCK_START -> checkNow(container, fromAlarm = true)
            AlarmReceiver.ACTION_LOCK_END -> {
                if (container.lock.state.value is LockState.Scheduled) container.lock.unlock(UnlockReason.TIME_UP)
            }
        }
    }

    private suspend fun warn(container: AppContainer) {
        val settings = container.settings.current()
        val today = container.tracker.today.value
        if (!settings.schedule.enabled || today.steps >= today.goal) return
        val remaining = today.goal - today.steps
        container.notifications.event(
            Notifications.ID_LOCK_WARN,
            "القفل المجدول بعد ${settings.schedule.warnMinutesBefore} دقيقة",
            "المتبقي ${Fmt.n(remaining)} خطوة لتفادي القفل الساعة ${Fmt.time(settings.schedule.startMinute)}.",
            silent = true,
        )
    }

    /**
     * Activates the scheduled lock if we are inside today's window, the goal is not met, no lock
     * is active, and the user did not already surrender today's scheduled lock.
     */
    suspend fun checkNow(container: AppContainer, fromAlarm: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        val settings = container.settings.current()
        val s = settings.schedule
        val zone = ZoneId.systemDefault()
        val date = Days.localDate(nowMs, zone)
        if (!s.appliesOn(date.dayOfWeek.value)) return
        val start = Days.atTime(date, s.startMinute / 60, s.startMinute % 60, zone)
        val end = Days.atTime(date, s.endMinute / 60, s.endMinute % 60, zone)
        if (nowMs < start - 2_000 || nowMs >= end) return
        if (container.lock.state.value.isActive) return
        if (settings.scheduleSkipDate == date.toString()) {
            Log.i(TAG, "scheduled lock skipped today (surrendered earlier)")
            return
        }
        container.tracker.load()
        val today = container.tracker.today.value
        if (today.steps >= today.goal) {
            Log.i(TAG, "goal already met (${today.steps}/${today.goal}); no scheduled lock")
            return
        }
        Log.i(TAG, "activating scheduled lock (fromAlarm=$fromAlarm) goal=${today.goal} steps=${today.steps}")
        container.lock.startScheduled(today.goal, end)
    }

    /** Called when the user surrenders a scheduled lock: do not re-arm it for the rest of the day. */
    suspend fun markSurrenderedToday(container: AppContainer) {
        container.settings.setScheduleSkipDate(LocalDate.now().toString())
    }
}
