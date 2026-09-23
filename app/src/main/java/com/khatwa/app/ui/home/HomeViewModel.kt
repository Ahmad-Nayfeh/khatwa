package com.khatwa.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khatwa.app.AppContainer
import com.khatwa.app.data.DayEntity
import com.khatwa.app.data.QuoteEntity
import com.khatwa.app.data.SessionEntity
import com.khatwa.app.settings.Settings
import com.khatwa.app.steps.Today
import com.khatwa.core.laptop.LaptopCode
import com.khatwa.core.quotes.QuotePicker
import com.khatwa.core.stats.DayStat
import com.khatwa.core.stats.Stats
import com.khatwa.core.stats.Streaks
import com.khatwa.core.stats.WeekCompare
import com.khatwa.core.time.Days
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class WeekDay(val date: LocalDate, val steps: Long, val isToday: Boolean)

data class HomeUiState(
    val today: Today = Today(LocalDate.now(), 0, 3000),
    val week: List<WeekDay> = emptyList(),
    val sessionsThisWeek: Int = 0,
    val compare: WeekCompare = WeekCompare(0, 0, null),
    val streaks: Streaks = Streaks(0, 0),
    val quote: QuoteEntity? = null,
    val laptopSecretSet: Boolean = false,
    val laptopCode: String? = null,
    val settings: Settings = Settings(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val weekRange = c.tracker.today.map { it.date }.flatMapLatest { today ->
        val start = Days.weekStart(today)
        c.db.days().observeBetween(start.minusDays(7).toString(), today.toString())
    }

    private val sessionsThisWeek = c.tracker.today.map { it.date }.flatMapLatest { today ->
        val start = Days.startOfDayMs(Days.weekStart(today), zone)
        val end = Days.startOfDayMs(today.plusDays(1), zone)
        c.db.sessions().observeBetween(start, end)
    }

    private val allDays = c.db.days().observeAll()

    val state: StateFlow<HomeUiState> = combine(
        c.tracker.today, weekRange, sessionsThisWeek, allDays, c.settings.flow, c.features.quotes.observeAll(),
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        build(
            today = arr[0] as Today,
            weekDays = arr[1] as List<DayEntity>,
            sessions = arr[2] as List<SessionEntity>,
            all = arr[3] as List<DayEntity>,
            settings = arr[4] as Settings,
            quotes = arr[5] as List<QuoteEntity>,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun build(today: Today, weekDays: List<DayEntity>, sessions: List<SessionEntity>, all: List<DayEntity>, settings: Settings, quotes: List<QuoteEntity>): HomeUiState {
        val stepsByDate = HashMap<LocalDate, Long>()
        val stats = HashMap<LocalDate, DayStat>()
        for (d in all) {
            val date = LocalDate.parse(d.date)
            val steps = if (date == today.date) today.steps else d.steps
            stepsByDate[date] = steps
            stats[date] = DayStat(date, steps, if (date == today.date) today.goal else d.goal)
        }
        stepsByDate[today.date] = today.steps
        stats[today.date] = DayStat(today.date, today.steps, today.goal)

        val weekStart = Days.weekStart(today.date)
        val week = (0 until 7).map { i ->
            val d = weekStart.plusDays(i.toLong())
            WeekDay(d, stepsByDate[d] ?: 0L, d == today.date)
        }
        val quote = pickQuote(quotes, today.date, settings)
        val code = settings.laptopSecret?.takeIf { today.steps >= today.goal }?.let { LaptopCode.code(it, today.date) }
        return HomeUiState(
            today = today,
            week = week,
            sessionsThisWeek = sessions.size,
            compare = Stats.weekCompare(stepsByDate, today.date),
            streaks = Stats.streaks(stats, today.date),
            quote = quote,
            laptopSecretSet = settings.laptopSecret != null,
            laptopCode = code,
            settings = settings,
        )
    }

    private fun pickQuote(quotes: List<QuoteEntity>, date: LocalDate, settings: Settings): QuoteEntity? {
        if (quotes.isEmpty()) return null
        val override = if (settings.quoteOverrideDate == date.toString()) settings.quoteOverrideIndex else -1
        val idx = if (override in quotes.indices) override else QuotePicker.indexFor(date, quotes.size)
        return quotes[idx]
    }

    fun anotherQuote() {
        viewModelScope.launch {
            val s = state.value
            val list = c.features.quotes.observeAll().first()
            if (list.isEmpty()) return@launch
            val current = list.indexOfFirst { it.id == s.quote?.id }.coerceAtLeast(0)
            val next = QuotePicker.another(current, list.size)
            c.settings.setQuoteOverride(s.today.date.toString(), next)
        }
    }
}
