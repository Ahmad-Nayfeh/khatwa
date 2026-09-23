package com.khatwa.app.steps

import android.util.Log
import com.khatwa.app.data.DayEntity
import com.khatwa.app.data.KhatwaDatabase
import com.khatwa.app.data.SessionEntity
import com.khatwa.app.data.SnapshotEntity
import com.khatwa.app.settings.SettingsRepository
import com.khatwa.core.goal.GoalEngine
import com.khatwa.core.goal.GoalInfo
import com.khatwa.core.sessions.SessionDetector
import com.khatwa.core.sessions.WalkSession
import com.khatwa.core.steps.DayState
import com.khatwa.core.steps.StepEngine
import com.khatwa.core.time.Days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/** What the UI and the lock need to know about today. */
data class Today(
    val date: LocalDate,
    val steps: Long,
    val goal: Int,
    val goalInfo: GoalInfo? = null,
    val lastUpdatedMs: Long = 0,
    /** True once at least one sensor reading has been processed since the process started. */
    val sensorSeen: Boolean = false,
)

/**
 * Glue between the pure [StepEngine] and Room. Single-threaded through a mutex; every entry
 * point is a suspend function so it can be called from the service, receivers, workers, and tests.
 */
class StepTracker(
    private val db: KhatwaDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var state: DayState? = null
    private val detector = SessionDetector()
    private var lastPersistMs = 0L
    private var dirty = false

    private val _today = MutableStateFlow(Today(LocalDate.now(), 0, 3000))
    val today: StateFlow<Today> = _today.asStateFlow()

    private val _sessions = MutableSharedFlow<WalkSession>(extraBufferCapacity = 8)
    val sessions: SharedFlow<WalkSession> = _sessions

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** Loads the open day from the database (idempotent). */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val open = db.days().openDay()
        state = open?.let {
            DayState(
                date = LocalDate.parse(it.date), zoneId = it.zone, carry = it.carry,
                baseline = it.baseline, lastReading = it.lastReading, lastUpdatedMs = it.lastUpdatedMs,
            )
        }
        loaded = true
        // Rolling over here also handles "the app was dead at midnight".
        state?.let { applyUpdate(StepEngine.rollover(it, clock(), zone()), eventMs = clock(), fromSensor = false) }
        publish(sensorSeen = false)
    }

    suspend fun onReading(reading: Long, eventTimeMs: Long) = mutex.withLock {
        ensureLoaded()
        val now = clock()
        val update = StepEngine.onReading(state, reading, now, zone())
        applyUpdate(update, eventMs = eventTimeMs, fromSensor = true)
        if (update.delta > 0) {
            detector.onSteps(eventTimeMs, update.delta)?.let { saveSession(it) }
        }
        publish(sensorSeen = true)
        persistIfDue(force = update.closed.isNotEmpty() || update.rebootDetected)
    }

    /** Midnight alarm / worker / service start: close yesterday if the date changed. */
    suspend fun rolloverIfNeeded() = mutex.withLock {
        ensureLoaded()
        val s = state ?: return@withLock
        val update = StepEngine.rollover(s, clock(), zone())
        if (update.closed.isNotEmpty()) {
            applyUpdate(update, eventMs = clock(), fromSensor = false)
            publish(sensorSeen = _today.value.sensorSeen)
            persistIfDue(force = true)
        } else {
            refreshGoal()
        }
    }

    suspend fun onZoneChanged() = mutex.withLock {
        ensureLoaded()
        val s = state ?: return@withLock
        val update = StepEngine.onZoneChanged(s, clock(), zone())
        applyUpdate(update, eventMs = clock(), fromSensor = false)
        publish(sensorSeen = _today.value.sensorSeen)
        persistIfDue(force = true)
    }

    /** Periodic tick from the service (~1/min): closes stale walking sessions, persists. */
    suspend fun tick() = mutex.withLock {
        ensureLoaded()
        detector.flush(clock())?.let { saveSession(it) }
        val s = state
        if (s != null && Days.localDate(clock(), zone()).isAfter(s.date)) {
            applyUpdate(StepEngine.rollover(s, clock(), zone()), eventMs = clock(), fromSensor = false)
            publish(sensorSeen = _today.value.sensorSeen)
        }
        persistIfDue(force = dirty)
    }

    /** 15-minute snapshot for the hour-of-day distribution. */
    suspend fun snapshot() = mutex.withLock {
        ensureLoaded()
        val s = state ?: return@withLock
        val now = clock()
        val latest = db.snapshots().latest()
        if (latest != null && now - latest.epochMs < 5 * 60_000L && latest.stepsToday == s.steps) return@withLock
        db.snapshots().insert(SnapshotEntity(epochMs = now, date = s.date.toString(), stepsToday = s.steps))
    }

    suspend fun flush() = mutex.withLock {
        if (!loaded) return@withLock
        persistIfDue(force = true)
    }

    /** Recompute the goal after settings changed. */
    suspend fun refreshGoalNow() = mutex.withLock {
        ensureLoaded()
        refreshGoal()
        publish(sensorSeen = _today.value.sensorSeen)
        persistIfDue(force = true)
    }

    /** Wipe in-memory state after the database was cleared or restored. */
    suspend fun reload() = mutex.withLock {
        loaded = false
        state = null
        detector.reset()
        ensureLoaded()
    }

    // ---- internals (call only while holding the mutex) ----

    private var currentGoal: GoalInfo? = null

    private suspend fun applyUpdate(update: com.khatwa.core.steps.StepUpdate, eventMs: Long, fromSensor: Boolean) {
        for (closed in update.closed) {
            val goal = currentGoal?.effectiveGoal ?: goalFor(closed.date).effectiveGoal
            db.days().upsert(
                DayEntity(
                    date = closed.date.toString(), zone = state?.zoneId ?: zone().id,
                    carry = 0, baseline = 0, lastReading = 0, steps = closed.steps, goal = goal,
                    lastUpdatedMs = closed.closedAtMs, closed = true,
                )
            )
            detector.reset()?.let { saveSession(it) }
            Log.i(TAG, "closed day ${closed.date} with ${closed.steps} steps (goal $goal)")
        }
        val dateChanged = state?.date != update.state.date
        state = update.state
        if (dateChanged || currentGoal == null) refreshGoal()
        dirty = true
        if (update.rebootDetected) Log.i(TAG, "reboot detected; carry=${update.state.carry}")
    }

    private suspend fun goalFor(date: LocalDate): GoalInfo {
        val s = settings.current()
        val history = db.days().all().associate { LocalDate.parse(it.date) to it.steps }.toMutableMap()
        state?.let { history[it.date] = it.steps }
        return GoalEngine.goalFor(s.goalConfig(date), date, history)
    }

    private suspend fun refreshGoal() {
        val date = state?.date ?: Days.localDate(clock(), zone())
        currentGoal = goalFor(date)
    }

    private suspend fun saveSession(session: WalkSession) {
        val date = Days.localDate(session.startMs, zone()).toString()
        db.sessions().insert(SessionEntity(date = date, startMs = session.startMs, endMs = session.endMs, steps = session.steps))
        _sessions.tryEmit(session)
        Log.i(TAG, "session saved: ${session.durationMs / 60000} min, ${session.steps} steps")
    }

    private fun publish(sensorSeen: Boolean) {
        val s = state
        val goal = currentGoal
        _today.value = Today(
            date = s?.date ?: Days.localDate(clock(), zone()),
            steps = s?.steps ?: 0,
            goal = goal?.effectiveGoal ?: _today.value.goal,
            goalInfo = goal,
            lastUpdatedMs = s?.lastUpdatedMs ?: 0,
            sensorSeen = sensorSeen || _today.value.sensorSeen,
        )
    }

    private suspend fun persistIfDue(force: Boolean) {
        val s = state ?: return
        val now = clock()
        if (!force && (now - lastPersistMs < PERSIST_INTERVAL_MS)) return
        db.days().upsert(
            DayEntity(
                date = s.date.toString(), zone = s.zoneId, carry = s.carry, baseline = s.baseline,
                lastReading = s.lastReading, steps = s.steps, goal = currentGoal?.effectiveGoal ?: _today.value.goal,
                lastUpdatedMs = s.lastUpdatedMs, closed = false,
            )
        )
        lastPersistMs = now
        dirty = false
    }

    fun launch(block: suspend () -> Unit) = scope.launch { block() }

    companion object {
        private const val TAG = "StepTracker"
        private const val PERSIST_INTERVAL_MS = 3_000L
    }
}
