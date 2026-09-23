package com.khatwa.app.alarms

import com.khatwa.app.AppContainer
import com.khatwa.app.settings.Settings

/** Scheduled daily lock alarms. Implemented in the scheduled-lock phase. */
object ScheduledLock {
    fun schedule(scheduler: AlarmScheduler, settings: Settings) = Unit
    suspend fun handle(container: AppContainer, action: String) = Unit
}
