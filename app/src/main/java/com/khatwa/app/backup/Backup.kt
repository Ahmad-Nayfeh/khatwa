package com.khatwa.app.backup

import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.I18n
import com.khatwa.app.steps.SnapshotWorker
import com.khatwa.app.steps.StepService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class DayJson(val date: String, val zone: String, val carry: Long, val baseline: Long, val lastReading: Long, val steps: Long, val goal: Int, val lastUpdatedMs: Long, val closed: Boolean)
@Serializable data class SnapshotJson(val epochMs: Long, val date: String, val stepsToday: Long)
@Serializable data class SessionJson(val date: String, val startMs: Long, val endMs: Long, val steps: Long)
@Serializable data class WeightJson(val date: String, val kg: Double, val createdMs: Long)
@Serializable data class SurrenderJson(val epochMs: Long, val date: String, val remainingSteps: Long, val lockType: String)
@Serializable data class QuoteBackupJson(val text: String, val source: String? = null, val lang: String = "ar")

/** Full backup: settings (including the laptop secret and, later, the groups identity) + every table. */
@Serializable
data class BackupFile(
    val app: String = "khatwa",
    val version: Int = 1,
    val exportedAtMs: Long,
    val settings: Map<String, String>,
    val days: List<DayJson>,
    val snapshots: List<SnapshotJson>,
    val sessions: List<SessionJson>,
    val weights: List<WeightJson>,
    val surrenders: List<SurrenderJson>,
    val quotes: List<QuoteBackupJson>,
)

class Backup(private val c: AppContainer) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val compactJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The backup file as JSON. [snapshotsSinceMs] drops older step snapshots (the only table that
     * grows fast) when a size limit matters (cloud backup); [pretty] is for the file export.
     */
    suspend fun export(pretty: Boolean = true, snapshotsSinceMs: Long = 0L): String {
        c.tracker.flush()
        val db = c.db
        val file = BackupFile(
            exportedAtMs = System.currentTimeMillis(),
            settings = c.settings.exportMap().filterKeys { it != "lock_state" },
            days = db.days().all().map { DayJson(it.date, it.zone, it.carry, it.baseline, it.lastReading, it.steps, it.goal, it.lastUpdatedMs, it.closed) },
            snapshots = db.snapshots().all().filter { it.epochMs >= snapshotsSinceMs }.map { SnapshotJson(it.epochMs, it.date, it.stepsToday) },
            sessions = db.sessions().all().map { SessionJson(it.date, it.startMs, it.endMs, it.steps) },
            weights = db.weights().all().map { WeightJson(it.date, it.kg, it.createdMs) },
            surrenders = db.surrenders().all().map { SurrenderJson(it.epochMs, it.date, it.remainingSteps, it.lockType) },
            quotes = db.quotes().all().map { QuoteBackupJson(it.text, it.source, it.lang) },
        )
        return (if (pretty) json else compactJson).encodeToString(BackupFile.serializer(), file)
    }

    /** Replaces everything with the backup. Returns a short human summary. */
    suspend fun import(text: String): String {
        val file = json.decodeFromString<BackupFile>(text)
        require(file.app == "khatwa") { "not a khatwa backup" }
        StepService.stop(c.app)
        c.lock.unlock(com.khatwa.app.lock.UnlockReason.CANCELLED)
        clearTables()
        val db = c.db
        file.days.forEach { db.days().upsert(com.khatwa.app.data.DayEntity(it.date, it.zone, it.carry, it.baseline, it.lastReading, it.steps, it.goal, it.lastUpdatedMs, it.closed)) }
        file.snapshots.forEach { db.snapshots().insert(com.khatwa.app.data.SnapshotEntity(epochMs = it.epochMs, date = it.date, stepsToday = it.stepsToday)) }
        file.sessions.forEach { db.sessions().insert(com.khatwa.app.data.SessionEntity(date = it.date, startMs = it.startMs, endMs = it.endMs, steps = it.steps)) }
        file.weights.forEach { db.weights().insert(com.khatwa.app.data.WeightEntity(date = it.date, kg = it.kg, createdMs = it.createdMs)) }
        file.surrenders.forEach { db.surrenders().insert(com.khatwa.app.data.SurrenderEntity(epochMs = it.epochMs, date = it.date, remainingSteps = it.remainingSteps, lockType = it.lockType)) }
        if (file.quotes.isNotEmpty()) db.quotes().insertAll(file.quotes.mapIndexed { i, q -> com.khatwa.app.data.QuoteEntity(text = q.text, source = q.source, sortOrder = i, lang = q.lang) })
        else c.features.quotes.seedIfNeeded()
        c.settings.importMap(file.settings.filterKeys { it != "lock_state" })
        c.tracker.reload()
        c.lock.refreshPolicy()
        val s = c.settings.current()
        if (s.onboardingDone) {
            StepService.start(c.app)
            c.alarms.scheduleAll(s)
            SnapshotWorker.schedule(c.app)
        }
        return I18n.current.restoreSummary(file.days.size, file.sessions.size, file.weights.size, file.quotes.size)
    }

    /** Wipes everything and returns to onboarding. */
    suspend fun clearAll() {
        StepService.stop(c.app)
        c.lock.unlock(com.khatwa.app.lock.UnlockReason.CANCELLED)
        clearTables()
        c.settings.clear()
        c.features.quotes.seedIfNeeded()
        c.tracker.reload()
        c.alarms.scheduleAll(c.settings.current())
    }

    private suspend fun clearTables() {
        val db = c.db
        db.days().deleteAll(); db.snapshots().deleteAll(); db.sessions().deleteAll()
        db.weights().deleteAll(); db.surrenders().deleteAll(); db.quotes().deleteAll()
    }
}
