package com.khatwa.app.alarms

import com.khatwa.app.AppContainer
import com.khatwa.app.settings.Settings

/**
 * Feature alarms (scheduled lock, morning quote, weight reminder). Phase 1 only needs the
 * midnight alarm; later phases fill these in.
 */
object AlarmKinds {
    fun scheduleFeatureAlarms(scheduler: AlarmScheduler, settings: Settings) {
        // Filled in by the scheduled-lock / notifications phases.
    }

    suspend fun handle(container: AppContainer, action: String) {
        // Filled in by later phases.
    }

    suspend fun onMidnight(container: AppContainer) {
        container.alarms.scheduleAll(container.settings.current())
    }
}
