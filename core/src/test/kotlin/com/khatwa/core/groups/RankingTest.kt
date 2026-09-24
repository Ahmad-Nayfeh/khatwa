package com.khatwa.core.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RankingTest {
    // Wednesday 2026-09-23: the week (Sunday start) began on the 20th, the month on the 1st.
    private val today: LocalDate = LocalDate.of(2026, 9, 23)

    private val a = MemberStats("a", streak = 3,
        today = PeriodStats("2026-09-23", 4000, 0, 15 * 60_000L),
        week = PeriodStats("2026-09-20", 13000, 1, 40 * 60_000L),
        month = PeriodStats("2026-09-01", 33000, 2, 40 * 60_000L),
    )
    private val b = MemberStats("b", streak = 10,
        today = PeriodStats("2026-09-23", 8000, 1, 12 * 60_000L),
        week = PeriodStats("2026-09-20", 8000, 1, 12 * 60_000L),
        month = PeriodStats("2026-09-01", 15000, 2, 12 * 60_000L),
    )
    // Published last week: only its month entry still counts.
    private val stale = MemberStats("s", streak = 1,
        today = PeriodStats("2026-09-16", 9999, 1, 0),
        week = PeriodStats("2026-09-13", 50000, 5, 0),
        month = PeriodStats("2026-09-01", 50000, 5, 0),
    )

    @Test
    fun periodKeysAndLengths() {
        assertEquals("2026-09-23", Ranking.key(Period.TODAY, today))
        assertEquals("2026-09-20", Ranking.key(Period.WEEK, today))
        assertEquals("2026-09-01", Ranking.key(Period.MONTH, today))
        assertEquals(1, Ranking.daysIn(Period.TODAY, today))
        assertEquals(4, Ranking.daysIn(Period.WEEK, today))
        assertEquals(23, Ranking.daysIn(Period.MONTH, today))
    }

    @Test
    fun staleEntriesCountAsZero() {
        assertEquals(0L, Ranking.memberRow(stale, Period.TODAY, today).steps)
        assertEquals(0L, Ranking.memberRow(stale, Period.WEEK, today).steps)
        assertEquals(50000L, Ranking.memberRow(stale, Period.MONTH, today).steps)
    }

    @Test
    fun membersAreRankedPerPeriodAndMetric() {
        val all = listOf(a, b, stale)
        assertEquals(listOf("b", "a", "s"), Ranking.rankMembers(all, Period.TODAY, MemberSort.STEPS, true, today).map { it.uid })
        assertEquals(listOf("a", "b", "s"), Ranking.rankMembers(all, Period.WEEK, MemberSort.STEPS, true, today).map { it.uid })
        assertEquals(listOf("s", "a", "b"), Ranking.rankMembers(all, Period.MONTH, MemberSort.STEPS, true, today).map { it.uid })
        // Goal days this month: s=5, a=2, b=2 -> the tie is broken by steps (a walked more).
        assertEquals(listOf("s", "a", "b"), Ranking.rankMembers(all, Period.MONTH, MemberSort.GOAL_DAYS, true, today).map { it.uid })
        assertEquals(listOf("s", "a", "b"), Ranking.rankMembers(all, Period.WEEK, MemberSort.STREAK, false, today).map { it.uid })
        assertEquals("a", Ranking.rankMembers(all, Period.WEEK, MemberSort.LONGEST_SESSION, true, today)[0].uid)
    }

    @Test
    fun totalsSummaryAndPublicRanking() {
        val t = Ranking.totals(listOf(a, b), memberCount = 2, Period.WEEK, today)
        assertEquals(21000L, t.steps)
        assertEquals(10500L, t.averageSteps)
        assertEquals(1, t.goalMet) // only b met today's goal
        assertEquals(2.0 / 8.0, t.goalRatio, 1e-9)

        val s = Ranking.summary(listOf(a, b), 2, today)
        assertEquals("2026-09-23", s.date)
        assertEquals(12000L, s.today.steps)
        // A summary from earlier this week is still valid for the week, not for today.
        val earlier = s.copy(date = "2026-09-21")
        assertEquals(0L, Ranking.publicTotals(earlier, 2, Period.TODAY, today).steps)
        assertEquals(21000L, Ranking.publicTotals(earlier, 2, Period.WEEK, today).steps)
        assertEquals(0L, Ranking.publicTotals(s.copy(date = "2026-08-30"), 2, Period.MONTH, today).steps)
        assertEquals(5, Ranking.publicTotals(null, 5, Period.MONTH, today).members)

        val big = GroupTotals(steps = 30000, members = 10, goalMet = 2, goalRatio = 0.1)
        val small = GroupTotals(steps = 21000, members = 2, goalMet = 1, goalRatio = 0.25)
        val pair = listOf("big" to big, "small" to small)
        assertEquals(listOf("big", "small"), Ranking.rankGroups(pair, GroupSort.TOTAL_STEPS, true).map { it.first })
        assertEquals(listOf("small", "big"), Ranking.rankGroups(pair, GroupSort.AVERAGE_STEPS, true).map { it.first })
        assertEquals(listOf("small", "big"), Ranking.rankGroups(pair, GroupSort.GOAL_RATIO, true).map { it.first })
        assertEquals(listOf("small", "big"), Ranking.rankGroups(pair, GroupSort.MEMBERS, false).map { it.first })
    }

    @Test
    fun clampKeepsValuesInsideTheRulesCaps() {
        val c = Ranking.clamp(PeriodStats("2026-09-20", 1_000_000, 9, 1L shl 40), days = 7)
        assertEquals(420_000L, c.steps)
        assertEquals(7, c.goalDays)
        assertEquals(86_400_000L, c.longestMs)
    }

    @Test
    fun inviteCodes() {
        val code = InviteCode.generate()
        assertEquals(8, code.length)
        assertTrue(InviteCode.isValid(code))
        assertEquals("ABCD2345", InviteCode.normalize(" abcd-2345 "))
        assertFalse(InviteCode.isValid("ABC"))
        assertFalse(InviteCode.isValid("ABCD234O"))
    }
}
