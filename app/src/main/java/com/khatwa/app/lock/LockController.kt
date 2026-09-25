package com.khatwa.app.lock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.khatwa.app.AppContainer
import com.khatwa.core.laptop.LaptopCode
import com.khatwa.app.i18n.I18n
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
 * One phone lock = one laptop "challenge": a counter (1, 2, 3, ... per pairing) from which the
 * 6-digit laptop lock and unlock codes are derived (see core LaptopCode). [id] holds the counter
 * as text ("0" = started before pairing; it gets a counter when the laptop is paired). Kept after
 * the lock ends so the unlock code stays visible until dismissed or a new lock starts.
 */
@Serializable
data class LaptopChallenge(
    val id: String,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val finishReason: String? = null,
) {
    val finished: Boolean get() = finishedAtMs != null
    /** The challenge counter, or null for a challenge of the old format / not yet paired. */
    val counter: Long? get() = id.toLongOrNull()?.takeIf { it > 0 }
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

    private val _challenge = MutableStateFlow<LaptopChallenge?>(null)
    /** Current (or last, until dismissed) laptop challenge. */
    val challenge: StateFlow<LaptopChallenge?> = _challenge.asStateFlow()

    val overlay = LockOverlay(c)

    @Volatile private var policy: LockPolicy = LockPolicy(context.packageName, emptySet(), emptySet(), emptySet(), true)
    @Volatile private var lastForeground: String? = null

    fun start() {
        c.scope.launch {
            val s = c.settings.current()
            _state.value = s.lockStateJson?.let { runCatching { json.decodeFromString<LockStateJson>(it).toState() }.getOrNull() } ?: LockState.None
            _challenge.value = s.laptopChallengeJson?.let { runCatching { json.decodeFromString<LaptopChallenge>(it) }.getOrNull() }
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
        newChallenge()
        Log.i(TAG, "manual lock started: $targetSteps steps from ${today.steps}")
        reapply()
    }

    suspend fun startScheduled(goal: Int, endAtMs: Long) {
        set(LockState.Scheduled(goal = goal, startedAtMs = System.currentTimeMillis(), endAtMs = endAtMs))
        newChallenge()
        Log.i(TAG, "scheduled lock started until ${java.time.Instant.ofEpochMilli(endAtMs)}")
        c.notifications.event(Notifications.ID_LOCK_STARTED, I18n.current.scheduledLockStartedTitle, I18n.current.scheduledLockStartedText, silent = true)
        reapply()
    }

    suspend fun unlock(reason: UnlockReason) {
        val prev = _state.value
        if (!prev.isActive) return
        set(LockState.None)
        finishChallenge(reason.name.lowercase())
        main.post { overlay.hide() }
        when (reason) {
            UnlockReason.COMPLETED -> c.notifications.event(Notifications.ID_UNLOCKED, I18n.current.unlockedTitle, I18n.current.unlockedText, silent = true)
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

    // ---- laptop challenge (codes are derived from the pairing secret in the UI) ----

    private suspend fun newChallenge() {
        val s = c.settings.current()
        val secret = s.laptopPairing
        val counter = if (secret == null) 0L else LaptopCode.nextCounter(secret, s.laptopCounter).also { c.settings.setLaptopCounter(it) }
        setChallenge(LaptopChallenge(id = counter.toString(), startedAtMs = System.currentTimeMillis()))
    }

    /**
     * Called after a (new) pairing code was generated: the counter restarted at 0. An active lock
     * that started before pairing gets its counter now, so its laptop codes appear at once; a
     * finished challenge from the previous pairing is dropped (its codes no longer match).
     */
    suspend fun onLaptopPaired() {
        val secret = c.settings.current().laptopPairing ?: return
        val ch = _challenge.value ?: return
        if (ch.finished) { setChallenge(null); return }
        val n = LaptopCode.nextCounter(secret, 0)
        c.settings.setLaptopCounter(n)
        setChallenge(ch.copy(id = n.toString()))
    }

    private suspend fun finishChallenge(reason: String) {
        val ch = _challenge.value ?: return
        if (ch.finished) return
        setChallenge(ch.copy(finishedAtMs = System.currentTimeMillis(), finishReason = reason))
    }

    /** Hides the unlock code card once the user is done with it. */
    suspend fun dismissLaptopChallenge() {
        if (_challenge.value?.finished == true) setChallenge(null)
    }

    private suspend fun setChallenge(ch: LaptopChallenge?) {
        _challenge.value = ch
        c.settings.setLaptopChallengeJson(ch?.let { json.encodeToString(LaptopChallenge.serializer(), it) })
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
            // Our own windows: the overlay itself must not hide itself; our activities are allowed
            // and become the app in front, so a lock started from Home does not cover Home.
            if (className != null && className.contains("Activity")) {
                lastForeground = pkg
                overlay.hide()
            }
            return
        }
        // Only an app screen (an activity) coming to the front changes the lock. Keyboards,
        // autofill suggestions, toasts and other pop-up windows keep it exactly as it is: typing
        // the emergency phrase opens the keyboard, and that must never bring the lock screen back
        // over our own app or reset the emergency screen. (reapply() passes className = null.)
        if (className != null && !isActivity(pkg, className)) return
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

    private val activityCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * True when [className] is an activity of [pkg] (the window of an app screen). A package we
     * cannot see at all (another profile) counts as an activity, so it stays blocked as before.
     */
    internal fun isActivity(pkg: String, className: String): Boolean = activityCache.getOrPut("$pkg/$className") {
        val pm = context.packageManager
        val visible = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        !visible || runCatching { pm.getActivityInfo(android.content.ComponentName(pkg, className), 0); true }.getOrDefault(false)
    }

    fun onAccessibilityGone() {
        main.post { overlay.hide() }
        refreshHealth()
    }

    companion object {
        private const val TAG = "LockController"
    }
}
