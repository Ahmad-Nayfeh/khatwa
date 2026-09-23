package com.khatwa.app.alarms

import com.khatwa.app.AppContainer
import com.khatwa.app.notifications.Notifications
import com.khatwa.app.settings.Settings
import com.khatwa.app.util.Fmt
import com.khatwa.core.quotes.QuotePicker
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Feature alarms: morning quote, weight reminder, and (from the scheduled-lock phase) the
 * daily lock. Each alarm is one-shot and re-armed after it fires.
 */
object AlarmKinds {
    fun scheduleFeatureAlarms(scheduler: AlarmScheduler, settings: Settings) {
        if (settings.morningEnabled) {
            scheduler.setExact(AlarmReceiver.ACTION_MORNING, AlarmTimes.next(settings.morningMinute, emptySet()), AlarmScheduler.REQ_MORNING)
        } else scheduler.cancel(AlarmReceiver.ACTION_MORNING, AlarmScheduler.REQ_MORNING)

        if (settings.weightReminderEnabled) {
            scheduler.setExact(AlarmReceiver.ACTION_WEIGHT, AlarmTimes.next(settings.weightReminderMinute, setOf(settings.weightReminderDay)), AlarmScheduler.REQ_WEIGHT)
        } else scheduler.cancel(AlarmReceiver.ACTION_WEIGHT, AlarmScheduler.REQ_WEIGHT)

        ScheduledLock.schedule(scheduler, settings)
    }

    suspend fun handle(container: AppContainer, action: String) {
        when (action) {
            AlarmReceiver.ACTION_MORNING -> morning(container)
            AlarmReceiver.ACTION_WEIGHT -> container.notifications.event(
                Notifications.ID_WEIGHT, "تذكير الوزن الأسبوعي", "سجّل وزنك هذا الأسبوع من الإعدادات ← سجل الوزن.",
            )
            else -> ScheduledLock.handle(container, action)
        }
        container.alarms.scheduleAll(container.settings.current())
    }

    suspend fun onMidnight(container: AppContainer) {
        container.alarms.scheduleAll(container.settings.current())
    }

    private suspend fun morning(container: AppContainer) {
        val today = LocalDate.now()
        val quotes = container.features.quotes.observeAll().first()
        val quote = quotes.getOrNull(QuotePicker.indexFor(today, quotes.size))?.text ?: return
        val yesterday = container.db.days().get(today.minusDays(1).toString())?.steps ?: 0L
        container.notifications.event(
            Notifications.ID_MORNING, "صباح الخير · خطوات الأمس ${Fmt.n(yesterday)}", quote, silent = true,
        )
    }
}
