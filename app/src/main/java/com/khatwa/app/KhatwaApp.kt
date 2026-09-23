package com.khatwa.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import com.khatwa.app.alarms.AlarmScheduler
import com.khatwa.app.data.KhatwaDatabase
import com.khatwa.app.debug.DebugHooks
import com.khatwa.app.notifications.Notifications
import com.khatwa.app.settings.SettingsRepository
import com.khatwa.app.steps.SnapshotWorker
import com.khatwa.app.steps.StepService
import com.khatwa.app.steps.StepTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container (no DI framework: fewer moving parts, faster CI). */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: KhatwaDatabase = KhatwaDatabase.build(app)
    val settings = SettingsRepository(app)
    val notifications = Notifications(app)
    val alarms = AlarmScheduler(app)
    val tracker = StepTracker(db, settings, scope)
    val features = Features(this)

    /** Called once from Application.onCreate. */
    fun start() {
        notifications.createChannels()
        scope.launch {
            val s = settings.current()
            if (s.onboardingDone) {
                tracker.load()
                StepService.start(app)
                alarms.scheduleAll(s)
                SnapshotWorker.schedule(app)
            }
            features.start()
        }
    }
}

class KhatwaApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DebugHooks.onAppCreate(this)
        container.start()
        Log.i("KhatwaApp", "started (debug hooks: ${DebugHooks.ENABLED})")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(Log.INFO).build()

    companion object {
        fun get(context: Context): KhatwaApp = context.applicationContext as KhatwaApp
        fun container(context: Context): AppContainer = get(context).container
    }
}
