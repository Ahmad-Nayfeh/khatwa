package com.khatwa.core.stats

import com.khatwa.core.time.Days
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToInt

/** One finished or in-progress day as the stats layer sees it. */
data class DayStat(val date: LocalDate, val steps: Long, val goal: Int) {
    val achieved: Boolean get() = steps >= goal
}

data class Streaks(val current: Int, val longest: Int)

data class WeekCompare(val thisWeek: Long, val lastWeek: Long, val percent: Int?)

data class MonthSummary(
    val averagePerDay: Int,
    val bestDay: DayStat?,
    val daysAchieved: Int,
    val daysCounted: Int,
)

/** A (timestamp, stepsSoFarToday) snapshot taken every ~15 minutes. */
data class Snapshot(val epochMs: Long, val stepsToday: Long)

object Stats {

    /**
     * Current streak counts back from today if today's goal is met, otherwise from yesterday
     * (today is still in progress and must not break the streak). Days with no record are 0.
     */
    fun streaks(days: Map<LocalDate, DayStat>, today: LocalDate): Streaks {
        var cursor = if (days[today]?.achieved == true) today else today.minusDays(1)
        var current = 0
        while (days[cursor]?.achieved == true) {
            current++
            cursor = cursor.minusDays(1)
        }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days.keys.sorted()) {
            val stat = days.getValue(d)
            run = if (stat.achieved && (prev == null || prev.plusDays(1) == d)) run + 1
            else if (stat.achieved) 1 else 0
            if (run > longest) longest = run
            prev = d
        }
        return Streaks(current, longest)
    }

    fun weekCompare(stepsByDate: Map<LocalDate, Long>, today: LocalDate): WeekCompare {
        val thisStart = Days.weekStart(today)
        val lastStart = thisStart.minusDays(7)
        val thisWeek = Days.datesBetween(thisStart, today).sumOf { stepsByDate[it] ?: 0L }
        // Compare like with like: the same number of elapsed days in the previous week.
        val elapsed = java.time.temporal.ChronoUnit.DAYS.between(thisStart, today)
        val lastWeek = Days.datesBetween(lastStart, lastStart.plusDays(elapsed)).sumOf { stepsByDate[it] ?: 0L }
        val percent = if (lastWeek == 0L) null else (((thisWeek - lastWeek) * 100.0) / lastWeek).roundToInt()
        return WeekCompare(thisWeek, lastWeek, percent)
    }

    fun monthSummary(days: List<DayStat>): MonthSummary {
        if (days.isEmpty()) return MonthSummary(0, null, 0, 0)
        val avg = (days.sumOf { it.steps }.toDouble() / days.size).roundToInt()
        return MonthSummary(
            averagePerDay = avg,
            bestDay = days.maxByOrNull { it.steps },
            daysAchieved = days.count { it.achieved },
            daysCounted = days.size,
        )
    }

    /** Average daily steps per month for the last [months] months ending at [today]'s month. */
    fun monthlyAverages(stepsByDate: Map<LocalDate, Long>, today: LocalDate, months: Int = 12): List<Pair<YearMonth, Int>> {
        val end = YearMonth.from(today)
        return (months - 1 downTo 0).map { back ->
            val ym = end.minusMonths(back.toLong())
            val inMonth = stepsByDate.filterKeys { YearMonth.from(it) == ym }
            val avg = if (inMonth.isEmpty()) 0 else (inMonth.values.sum().toDouble() / inMonth.size).roundToInt()
            ym to avg
        }
    }

    /**
     * Average steps per hour of day (0..23) across the days present in [snapshots].
     * Each snapshot carries the cumulative steps of its day; the delta between consecutive
     * snapshots of the same day is attributed to the hour of the later snapshot.
     */
    fun hourDistribution(snapshots: List<Snapshot>, zone: ZoneId): List<Double> {
        val perHour = DoubleArray(24)
        val byDay = snapshots.groupBy { Days.localDate(it.epochMs, zone) }
        if (byDay.isEmpty()) return perHour.toList()
        for ((_, list) in byDay) {
            val sorted = list.sortedBy { it.epochMs }
            var prev = 0L
            for (s in sorted) {
                val delta = (s.stepsToday - prev).coerceAtLeast(0)
                perHour[Days.hourOfDay(s.epochMs, zone)] += delta.toDouble()
                prev = s.stepsToday
            }
        }
        val dayCount = byDay.size.toDouble()
        return perHour.map { it / dayCount }
    }
}
