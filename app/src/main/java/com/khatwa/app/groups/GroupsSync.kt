package com.khatwa.app.groups

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.khatwa.app.AppContainer
import com.khatwa.app.KhatwaApp
import com.khatwa.core.groups.MemberStats
import com.khatwa.core.groups.Period
import com.khatwa.core.groups.PeriodStats
import com.khatwa.core.groups.Ranking
import com.khatwa.core.stats.DayStat
import com.khatwa.core.stats.Stats
import com.khatwa.core.time.Days
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Computes what this phone publishes to its groups (today / this week / this month, streak) from
 * the local database, and pushes it. Runs when the groups tab opens and every couple of hours
 * through WorkManager while the feature is enabled.
 */
object GroupsSync {
    private const val TAG = "GroupsSync"
    private const val WORK = "khatwa_groups_sync"

    suspend fun localStats(c: AppContainer, today: LocalDate = c.tracker.today.value.date): MemberStats {
        c.tracker.flush()
        val zone = ZoneId.systemDefault()
        val todayState = c.tracker.today.value
        val monthStart = Days.monthStart(today)
        val weekStart = Days.weekStart(today)
        val days = c.db.days().all().associate { LocalDate.parse(it.date) to it }
        val stats = HashMap<LocalDate, DayStat>()
        for ((date, d) in days) stats[date] = DayStat(date, d.steps, d.goal)
        stats[today] = DayStat(today, todayState.steps, todayState.goal)
        val monthSessions = c.db.sessions().observeBetween(Days.startOfDayMs(monthStart, zone), Days.startOfDayMs(today.plusDays(1), zone)).first()

        fun period(from: LocalDate, p: Period): PeriodStats {
            val range = Days.datesBetween(from, today)
            val ds = range.mapNotNull { stats[it] }
            val fromMs = Days.startOfDayMs(from, zone)
            val longest = monthSessions.filter { se -> se.startMs >= fromMs }.maxOfOrNull { se -> se.endMs - se.startMs } ?: 0L
            return Ranking.clamp(PeriodStats(Ranking.key(p, today), ds.sumOf { it.steps }, ds.count { it.achieved }, longest), range.size)
        }
        val streak = Stats.streaks(stats, today).current
        return MemberStats(
            uid = c.groups.uid ?: "",
            streak = streak,
            today = period(today, Period.TODAY),
            week = period(weekStart, Period.WEEK),
            month = period(monthStart, Period.MONTH),
        )
    }

    /** Publishes now; failures are logged, never shown (the next sync retries). */
    suspend fun syncNow(c: AppContainer): Boolean {
        val s = c.settings.current()
        if (!s.groupsEnabled || !c.groups.configured) return false
        return try {
            c.groups.ensureProfile(s.groupsNickname.ifBlank { "khatwa" })
            val today = c.tracker.today.value.date
            c.groups.publish(localStats(c, today), today)
            Log.i(TAG, "published")
            true
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            false
        }
    }

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<GroupsSyncWorker>(2, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class GroupsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = KhatwaApp.container(applicationContext)
        if (!c.settings.current().onboardingDone) return Result.success()
        c.tracker.load()
        return if (GroupsSync.syncNow(c)) Result.success() else Result.retry()
    }
}
