package com.khatwa.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khatwa.app.AppContainer
import com.khatwa.app.data.DayEntity
import com.khatwa.app.data.SessionEntity
import com.khatwa.app.data.SnapshotEntity
import com.khatwa.app.data.SurrenderEntity
import com.khatwa.app.data.WeightEntity
import com.khatwa.app.steps.Today
import com.khatwa.core.stats.DayStat
import com.khatwa.core.stats.MonthSummary
import com.khatwa.core.stats.Snapshot
import com.khatwa.core.stats.Stats
import com.khatwa.core.stats.Streaks
import com.khatwa.core.time.Days
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class SessionStats(val countThisWeek: Int, val longestMs: Long, val averageMs: Long, val countThisMonth: Int)

/** One day as shown in the day slider: steps, goal and the walking sessions recorded that day. */
data class DayCard(val stat: DayStat, val sessions: Int, val longestSessionMs: Long, val isToday: Boolean)

data class StatsUiState(
    val loaded: Boolean = false,
    /** Oldest first, ending with today. */
    val days: List<DayCard> = emptyList(),
    val streaks: Streaks = Streaks(0, 0),
    val last30: List<DayStat> = emptyList(),
    val month: MonthSummary = MonthSummary(0, null, 0, 0),
    val sessions: SessionStats = SessionStats(0, 0, 0, 0),
    val months: List<Pair<YearMonth, Int>> = emptyList(),
    val weightByMonth: List<Double?> = emptyList(),
    val weights: List<WeightEntity> = emptyList(),
    val hours: List<Double> = emptyList(),
    val surrendersThisMonth: Int = 0,
    val goal: Int = 0,
)

class StatsViewModel(private val c: AppContainer) : ViewModel() {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val sessions = c.tracker.today.map { it.date }.flatMapLatest { today ->
        c.db.sessions().observeBetween(Days.startOfDayMs(today.minusDays(SLIDER_DAYS.toLong()), zone), Days.startOfDayMs(today.plusDays(1), zone))
    }
    private val snapshots = c.tracker.today.map { it.date }.flatMapLatest { today ->
        c.db.snapshots().observeBetween(Days.startOfDayMs(today.minusDays(30), zone), Days.startOfDayMs(today.plusDays(1), zone))
    }
    private val surrenders = c.tracker.today.map { it.date }.flatMapLatest { today ->
        c.db.surrenders().observeBetween(Days.startOfDayMs(Days.monthStart(today), zone), Days.startOfDayMs(today.plusDays(1), zone))
    }

    val state: StateFlow<StatsUiState> = combine(
        c.tracker.today, c.db.days().observeAll(), sessions, snapshots, c.db.weights().observeAll(), surrenders,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        build(
            today = arr[0] as Today,
            all = arr[1] as List<DayEntity>,
            sessions = arr[2] as List<SessionEntity>,
            snapshots = arr[3] as List<SnapshotEntity>,
            weights = arr[4] as List<WeightEntity>,
            surrenders = arr[5] as List<SurrenderEntity>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun build(today: Today, all: List<DayEntity>, sessions: List<SessionEntity>, snapshots: List<SnapshotEntity>, weights: List<WeightEntity>, surrenders: List<SurrenderEntity>): StatsUiState {
        val stepsByDate = HashMap<LocalDate, Long>()
        val goalByDate = HashMap<LocalDate, Int>()
        for (d in all) {
            val date = LocalDate.parse(d.date)
            stepsByDate[date] = if (date == today.date) today.steps else d.steps
            goalByDate[date] = if (date == today.date) today.goal else d.goal
        }
        stepsByDate[today.date] = today.steps
        goalByDate[today.date] = today.goal

        val last30 = (29 downTo 0).map { back ->
            val d = today.date.minusDays(back.toLong())
            DayStat(d, stepsByDate[d] ?: 0L, goalByDate[d] ?: today.goal)
        }
        val sessionsByDate = sessions.groupBy { it.date }
        val days = (SLIDER_DAYS - 1 downTo 0).map { back ->
            val d = today.date.minusDays(back.toLong())
            val daySessions = sessionsByDate[d.toString()].orEmpty()
            DayCard(
                stat = DayStat(d, stepsByDate[d] ?: 0L, goalByDate[d] ?: today.goal),
                sessions = daySessions.size,
                longestSessionMs = daySessions.maxOfOrNull { it.endMs - it.startMs } ?: 0L,
                isToday = d == today.date,
            )
        }
        val statsByDate = stepsByDate.keys.associateWith { d -> DayStat(d, stepsByDate[d] ?: 0L, goalByDate[d] ?: today.goal) }
        val streaks = Stats.streaks(statsByDate, today.date)
        val recorded30 = last30.filter { stepsByDate.containsKey(it.date) }
        val month = Stats.monthSummary(recorded30)

        val weekStartMs = Days.startOfDayMs(Days.weekStart(today.date), zone)
        val monthStartMs = Days.startOfDayMs(Days.monthStart(today.date), zone)
        val weekSessions = sessions.filter { it.startMs >= weekStartMs }
        val monthSessions = sessions.filter { it.startMs >= monthStartMs }
        val sessionStats = SessionStats(
            countThisWeek = weekSessions.size,
            longestMs = monthSessions.maxOfOrNull { it.endMs - it.startMs } ?: 0L,
            averageMs = if (monthSessions.isEmpty()) 0L else monthSessions.sumOf { it.endMs - it.startMs } / monthSessions.size,
            countThisMonth = monthSessions.size,
        )

        val months = Stats.monthlyAverages(stepsByDate, today.date, 12)
        val weightByMonth = months.map { (ym, _) ->
            weights.filter { YearMonth.from(LocalDate.parse(it.date)) == ym }.map { it.kg }.let { l -> if (l.isEmpty()) null else l.average() }
        }
        val hours = Stats.hourDistribution(snapshots.map { Snapshot(it.epochMs, it.stepsToday) }, zone)

        return StatsUiState(
            loaded = true,
            days = days,
            streaks = streaks,
            last30 = last30,
            month = month,
            sessions = sessionStats,
            months = months,
            weightByMonth = weightByMonth,
            weights = weights,
            hours = hours,
            surrendersThisMonth = surrenders.size,
            goal = today.goal,
        )
    }

    companion object {
        /** Days available in the day slider (today included). */
        const val SLIDER_DAYS = 90
    }
}
