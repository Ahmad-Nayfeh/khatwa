package com.khatwa.core.stats

import com.khatwa.core.time.Days
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsTest {
    private val zone: ZoneId = ZoneId.of("Asia/Riyadh")
    private val goal = 5000

    private fun days(from: LocalDate, vararg steps: Long): Map<LocalDate, DayStat> =
        steps.mapIndexed { i, s -> val d = from.plusDays(i.toLong()); d to DayStat(d, s, goal) }.toMap()

    @Test
    fun `current streak counts back from today when today is achieved`() {
        val today = LocalDate.of(2026, 5, 10)
        val h = days(today.minusDays(4), 6000, 6000, 1000, 6000, 6000)
        val s = Stats.streaks(h, today)
        assertEquals(2, s.current)
        assertEquals(2, s.longest)
    }

    @Test
    fun `an unfinished today does not break the streak`() {
        val today = LocalDate.of(2026, 5, 10)
        val h = days(today.minusDays(3), 6000, 6000, 6000, 100)
        assertEquals(3, Stats.streaks(h, today).current)
    }

    @Test
    fun `longest streak spans history and gaps reset it`() {
        val start = LocalDate.of(2026, 4, 1)
        val h = HashMap(days(start, 6000, 6000, 6000, 6000, 1000, 6000))
        // A missing day between two achieved days is a gap.
        h[start.plusDays(8)] = DayStat(start.plusDays(8), 7000, goal)
        val s = Stats.streaks(h, start.plusDays(8))
        assertEquals(4, s.longest)
        assertEquals(1, s.current)
    }

    @Test
    fun `week starts on Saturday`() {
        val wed = LocalDate.of(2026, 9, 23)
        assertEquals(DayOfWeek.WEDNESDAY, wed.dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 19), Days.weekStart(wed))
        assertEquals(DayOfWeek.SATURDAY, Days.weekStart(wed).dayOfWeek)
        val sat = LocalDate.of(2026, 9, 19)
        assertEquals(sat, Days.weekStart(sat))
    }

    @Test
    fun `week compare uses the same elapsed days of the previous week`() {
        val today = LocalDate.of(2026, 9, 21) // Monday -> week started Saturday 19th, 3 days elapsed
        val steps = HashMap<LocalDate, Long>()
        // This week: Sat, Sun, Mon
        steps[LocalDate.of(2026, 9, 19)] = 3000
        steps[LocalDate.of(2026, 9, 20)] = 3000
        steps[LocalDate.of(2026, 9, 21)] = 3000
        // Last week: Sat, Sun, Mon + later days that must be ignored
        steps[LocalDate.of(2026, 9, 12)] = 2000
        steps[LocalDate.of(2026, 9, 13)] = 2000
        steps[LocalDate.of(2026, 9, 14)] = 2000
        steps[LocalDate.of(2026, 9, 17)] = 9999
        val c = Stats.weekCompare(steps, today)
        assertEquals(9000, c.thisWeek)
        assertEquals(6000, c.lastWeek)
        assertEquals(50, c.percent)
    }

    @Test
    fun `week compare has no percent when last week was empty`() {
        val today = LocalDate.of(2026, 9, 21)
        val c = Stats.weekCompare(mapOf(today to 100L), today)
        assertNull(c.percent)
    }

    @Test
    fun `month summary`() {
        val start = LocalDate.of(2026, 6, 1)
        val list = days(start, 1000, 6000, 8000, 2000).values.toList()
        val m = Stats.monthSummary(list)
        assertEquals(4250, m.averagePerDay)
        assertEquals(8000, m.bestDay?.steps)
        assertEquals(2, m.daysAchieved)
        assertEquals(4, m.daysCounted)
    }

    @Test
    fun `monthly averages cover the last 12 months in order`() {
        val today = LocalDate.of(2026, 9, 23)
        val steps = mapOf(
            LocalDate.of(2026, 9, 1) to 4000L, LocalDate.of(2026, 9, 2) to 6000L,
            LocalDate.of(2025, 10, 5) to 1000L,
        )
        val m = Stats.monthlyAverages(steps, today)
        assertEquals(12, m.size)
        assertEquals(java.time.YearMonth.of(2025, 10), m.first().first)
        assertEquals(1000, m.first().second)
        assertEquals(java.time.YearMonth.of(2026, 9), m.last().first)
        assertEquals(5000, m.last().second)
        assertEquals(0, m[5].second)
    }

    @Test
    fun `hour distribution attributes deltas to the hour of the later snapshot`() {
        val d = LocalDate.of(2026, 9, 20)
        fun t(time: String) = d.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()
        val snaps = listOf(
            Snapshot(t("07:15"), 100), Snapshot(t("07:45"), 400), Snapshot(t("08:30"), 900),
            // second day
            Snapshot(t("07:30") + 86_400_000, 200), Snapshot(t("08:00") + 86_400_000, 500),
        )
        val dist = Stats.hourDistribution(snaps, zone)
        assertEquals(24, dist.size)
        assertEquals((400 + 200) / 2.0, dist[7])
        assertEquals((500 + 300) / 2.0, dist[8])
        assertEquals(0.0, dist[12])
    }
}
