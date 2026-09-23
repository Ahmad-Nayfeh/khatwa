package com.khatwa.app.lock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.khatwa.app.AppContainer
import com.khatwa.app.data.SurrenderEntity
import com.khatwa.app.notifications.Notifications
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.steps.Today
import com.khatwa.core.lock.AllowReason
import com.khatwa.core.lock.LockPolicy
import com.khatwa.core.lock.LockState
import com.khatwa.core.time.Days
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZoneId

enum class UnlockReason { COMPLETED, TIME_UP, SURRENDER, CANCELLED }

/** Whether the lock can actually work right now. Checked on every app open. */
data class LockHealth(val accessibility: Boolean, val overlay: Boolean) {
    val ok: Boolean get() = accessibility && overlay
}

@Serializable
private data class LockStateJson(
    val type: String,
    val stepsAtStart: Long = 0,
    val target: Int = 0,
    val startedAtMs: Long = 0,
    val endAtMs: Long = 0,
) {
    fun toState(): LockState = when (type) {
        "manual" -> LockState.Manual(stepsAtStart, target, startedAtMs)
        "scheduled" -> LockState.Scheduled(target, startedAtMs, endAtMs)
        else -> LockState.None
    }

    companion object {
        fun from(s: LockState) = when (s) {
            LockState.None -> LockStateJson("none")
            is LockState.Manual -> LockStateJson("manual", s.stepsAtStart, s.targetSteps, s.startedAtMs)
            is LockState.Scheduled -> LockStateJson("scheduled", 0, s.goal, s.startedAtMs, s.endAtMs)
        }
    }
}

/**
 * Single source of truth for the phone lock. Decides, per foreground package, whether the
 * overlay must be shown; unlocks automatically when the required steps are reached.
 */
class LockController(private val c: AppContainer) {
    private val context: Context get() = c.app
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<LockState>(LockState.None)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private val _health = MutableStateFlow(LockHealth(accessibility = false, overlay = false))
    val health: StateFlow<LockHealth> = _health.asStateFlow()

    val overlay = LockOverlay(c)

    @Volatile private var policy: LockPolicy = LockPolicy(context.packageName, emptySet(), emptySet(), emptySet(), true)
    @Volatile private var lastForeground: String? = null

    fun start() {
        c.scope.launch {
            val s = c.settings.current()
            _state.value = s.lockStateJson?.let { runCatching { json.decodeFromString<LockStateJson>(it).toState() }.getOrNull() } ?: LockState.None
            refreshPolicy()
            refreshHealth()
        }
        c.scope.launch {
            c.settings.flow.map { it.allowlist to it.blockSettings }.distinctUntilChanged().collect { refreshPolicy() }
        }
        c.scope.launch { c.tracker.today.collect { onStepsChanged(it) } }
    }

    fun refreshHealth(): LockHealth {
        val h = LockHealth(
            accessibility = PermissionChecks.accessibilityEnabled(context, KhatwaAccessibilityService::class.java) && KhatwaAccessibilityService.connected,
            overlay = PermissionChecks.overlay(context),
        )
        _health.value = h
        return h
    }

    suspend fun refreshPolicy() {
        val s = c.settings.current()
        policy = LockPolicy(
            ownPackage = context.packageName,
            alwaysAllowed = AllowlistDefaults.alwaysAllowed(context),
            settingsPackages = AllowlistDefaults.settingsPackages(),
            userAllowed = s.allowlist,
            blockSettings = s.blockSettings,
        )
    }

    fun currentPolicy(): LockPolicy = policy

    // ---- state changes ----

    suspend fun startManual(targetSteps: Int) {
        val today = c.tracker.today.value
        set(LockState.Manual(stepsAtStart = today.steps, targetSteps = targetSteps, startedAtMs = System.currentTimeMillis()))
        Log.i(TAG, "manual lock started: $targetSteps steps from ${today.steps}")
        reapply()
    }

    suspend fun startScheduled(goal: Int, endAtMs: Long) {
        set(LockState.Scheduled(goal = goal, startedAtMs = System.currentTimeMillis(), endAtMs = endAtMs))
        Log.i(TAG, "scheduled lock started until ${java.time.Instant.ofEpochMilli(endAtMs)}")
        c.notifications.event(Notifications.ID_LOCK_STARTED, "بدأ القفل المجدول", "أكمل هدف اليوم لفتح الجوال. التطبيقات المسموحة تبقى متاحة.", silent = true)
        reapply()
    }

    suspend fun unlock(reason: UnlockReason) {
        val prev = _state.value
        if (!prev.isActive) return
        set(LockState.None)
        main.post { overlay.hide() }
        when (reason) {
            UnlockReason.COMPLETED -> c.notifications.event(Notifications.ID_UNLOCKED, "أحسنت", "أكملت الخطوات المطلوبة وفُتح القفل.", silent = true)
            UnlockReason.TIME_UP -> Log.i(TAG, "scheduled lock ended by time")
            else -> Unit
        }
        Log.i(TAG, "unlocked: $reason")
    }

    /** Emergency exit: recorded as a surrender with the steps that were still missing. */
    suspend fun surrender() {
        val s = _state.value
        if (!s.isActive) return
        val today = c.tracker.today.value
        val remaining = s.remaining(today.steps)
        c.db.surrenders().insert(
            SurrenderEntity(
                epochMs = System.currentTimeMillis(),
                date = Days.localDate(System.currentTimeMillis(), ZoneId.systemDefault()).toString(),
                remainingSteps = remaining,
                lockType = if (s is LockState.Manual) "manual" else "scheduled",
            )
        )
        Log.i(TAG, "surrender recorded: remaining=$remaining")
        if (s is LockState.Scheduled) com.khatwa.app.alarms.ScheduledLock.markSurrenderedToday(c)
        unlock(UnlockReason.SURRENDER)
    }

    private suspend fun set(state: LockState) = mutex.withLock {
        _state.value = state
        c.settings.setLockStateJson(if (state.isActive) json.encodeToString(LockStateJson.serializer(), LockStateJson.from(state)) else null)
    }

    private fun onStepsChanged(today: Today) {
        val s = _state.value
        if (!s.isActive) return
        if (s.isComplete(today.steps)) {
            c.scope.launch { unlock(UnlockReason.COMPLETED) }
        } else if (s.isExpired(System.currentTimeMillis())) {
            c.scope.launch { unlock(UnlockReason.TIME_UP) }
        }
    }

    /** Re-evaluate the last known foreground package (after a lock starts or the policy changes). */
    fun reapply() {
        val pkg = lastForeground ?: return
        main.post { onForeground(pkg, null) }
    }

    // ---- foreground tracking (main thread, called by the accessibility service) ----

    fun onForeground(pkg: String, className: String?) {
        val s = _state.value
        if (pkg == context.packageName) {
            // Our own windows: the overlay itself must not hide itself; our activities are allowed.
            if (className != null && className.contains("Activity")) overlay.hide()
            return
        }
        lastForeground = pkg
        if (!s.isActive) { overlay.hide(); return }
        if (s.isExpired(System.currentTimeMillis())) { c.scope.launch { unlock(UnlockReason.TIME_UP) }; return }
        when (policy.decide(pkg)) {
            AllowReason.ALWAYS_ALLOWED -> {
                // System UI / keyboards keep the current overlay state; launchers and dialers hide it.
                if (pkg == "com.android.systemui" || pkg in AllowlistDefaults.inputMethods(context)) return
                overlay.hide()
            }
            AllowReason.USER_ALLOWED -> overlay.hide()
            AllowReason.SETTINGS_BLOCKED, AllowReason.BLOCKED -> overlay.show()
        }
    }

    fun onAccessibilityGone() {
        main.post { overlay.hide() }
        refreshHealth()
    }

    companion object {
        private const val TAG = "LockController"
    }
}
