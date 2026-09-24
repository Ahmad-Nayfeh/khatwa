package com.khatwa.core.groups

import com.khatwa.core.time.Days
import java.time.LocalDate

/**
 * What one member publishes for one period: [key] is the ISO date the period starts on
 * (today / the week's Sunday / the 1st of the month), so stale entries are recognised.
 */
data class PeriodStats(val key: String, val steps: Long, val goalDays: Int, val longestMs: Long) {
    companion object { val EMPTY = PeriodStats("", 0, 0, 0) }
}

/** A member's published numbers (no name: names live in the members list, visible to members only). */
data class MemberStats(val uid: String, val streak: Int, val today: PeriodStats, val week: PeriodStats, val month: PeriodStats) {
    companion object {
        /** Hard cap enforced by the Firestore rules too: values above it are rejected. */
        const val MAX_STEPS_PER_DAY = 60_000L
    }
}

enum class Period { TODAY, WEEK, MONTH }

enum class MemberSort { STEPS, GOAL_DAYS, STREAK, LONGEST_SESSION }

enum class GroupSort { TOTAL_STEPS, AVERAGE_STEPS, GOAL_RATIO, MEMBERS }

data class MemberRow(val uid: String, val steps: Long, val goalDays: Int, val streak: Int, val longestMs: Long)

/** Totals of one group over a period (what the public list shows). */
data class GroupTotals(val steps: Long, val members: Int, val goalMet: Int, val goalRatio: Double) {
    val averageSteps: Long get() = if (members == 0) 0L else steps / members
    companion object { val EMPTY = GroupTotals(0, 0, 0, 0.0) }
}

/** A group's published summary: totals per period, stamped with the date they were computed on. */
data class GroupSummary(val date: String, val today: GroupTotals, val week: GroupTotals, val month: GroupTotals)

object Ranking {

    /** Period key for [today]: the ISO date the period starts on. */
    fun key(period: Period, today: LocalDate): String = when (period) {
        Period.TODAY -> today.toString()
        Period.WEEK -> Days.weekStart(today).toString()
        Period.MONTH -> Days.monthStart(today).toString()
    }

    /** Number of days the period covers up to and including [today]. */
    fun daysIn(period: Period, today: LocalDate): Int = when (period) {
        Period.TODAY -> 1
        Period.WEEK -> Days.datesBetween(Days.weekStart(today), today).size
        Period.MONTH -> today.dayOfMonth
    }

    /** The member's numbers for [period], or zeros when what it published is from an older period. */
    fun stats(m: MemberStats, period: Period, today: LocalDate): PeriodStats {
        val p = when (period) { Period.TODAY -> m.today; Period.WEEK -> m.week; Period.MONTH -> m.month }
        return if (p.key == key(period, today)) p else PeriodStats.EMPTY
    }

    fun memberRow(m: MemberStats, period: Period, today: LocalDate): MemberRow {
        val p = stats(m, period, today)
        return MemberRow(m.uid, p.steps, p.goalDays, m.streak, p.longestMs)
    }

    /** Members of one group ordered by [sort]; ties broken by steps then uid for a stable list. */
    fun rankMembers(members: Collection<MemberStats>, period: Period, sort: MemberSort, descending: Boolean, today: LocalDate): List<MemberRow> {
        val rows = members.map { memberRow(it, period, today) }
        val key: (MemberRow) -> Long = when (sort) {
            MemberSort.STEPS -> { r -> r.steps }
            MemberSort.GOAL_DAYS -> { r -> r.goalDays.toLong() }
            MemberSort.STREAK -> { r -> r.streak.toLong() }
            MemberSort.LONGEST_SESSION -> { r -> r.longestMs }
        }
        val cmp = compareBy<MemberRow>(key).thenBy { it.steps }.thenBy { it.uid }
        return if (descending) rows.sortedWith(cmp.reversed()) else rows.sortedWith(cmp)
    }

    /** Group totals for [period] from its members' published numbers. */
    fun totals(members: Collection<MemberStats>, memberCount: Int, period: Period, today: LocalDate): GroupTotals {
        val rows = members.map { memberRow(it, period, today) }
        val possible = daysIn(period, today) * memberCount
        val goalMetToday = members.count { stats(it, Period.TODAY, today).goalDays > 0 }
        return GroupTotals(
            steps = rows.sumOf { it.steps },
            members = memberCount,
            goalMet = goalMetToday,
            goalRatio = if (possible == 0) 0.0 else (rows.sumOf { it.goalDays }.toDouble() / possible).coerceIn(0.0, 1.0),
        )
    }

    fun summary(members: Collection<MemberStats>, memberCount: Int, today: LocalDate): GroupSummary = GroupSummary(
        date = today.toString(),
        today = totals(members, memberCount, Period.TODAY, today),
        week = totals(members, memberCount, Period.WEEK, today),
        month = totals(members, memberCount, Period.MONTH, today),
    )

    /**
     * Totals to show for a group in the public list: a summary computed on an earlier day is still
     * valid for the week/month it belongs to, and counts as zero for periods that have since passed.
     */
    fun publicTotals(s: GroupSummary?, memberCount: Int, period: Period, today: LocalDate): GroupTotals {
        if (s == null) return GroupTotals.EMPTY.copy(members = memberCount)
        val date = runCatching { LocalDate.parse(s.date) }.getOrNull() ?: return GroupTotals.EMPTY.copy(members = memberCount)
        val fresh = when (period) {
            Period.TODAY -> date == today
            Period.WEEK -> Days.weekStart(date) == Days.weekStart(today)
            Period.MONTH -> Days.monthStart(date) == Days.monthStart(today)
        }
        val t = when (period) { Period.TODAY -> s.today; Period.WEEK -> s.week; Period.MONTH -> s.month }
        return if (fresh) t.copy(members = memberCount) else GroupTotals.EMPTY.copy(members = memberCount)
    }

    /** Public ranking of groups given each group's totals for the chosen period. */
    fun <G> rankGroups(groups: List<Pair<G, GroupTotals>>, sort: GroupSort, descending: Boolean): List<Pair<G, GroupTotals>> {
        val key: (GroupTotals) -> Double = when (sort) {
            GroupSort.TOTAL_STEPS -> { t -> t.steps.toDouble() }
            GroupSort.AVERAGE_STEPS -> { t -> t.averageSteps.toDouble() }
            GroupSort.GOAL_RATIO -> { t -> t.goalRatio }
            GroupSort.MEMBERS -> { t -> t.members.toDouble() }
        }
        val cmp = compareBy<Pair<G, GroupTotals>> { key(it.second) }.thenBy { it.second.steps }
        return if (descending) groups.sortedWith(cmp.reversed()) else groups.sortedWith(cmp)
    }

    /** Clamps a period's steps to what the rules accept (60 000 per day). */
    fun clamp(p: PeriodStats, days: Int): PeriodStats =
        p.copy(steps = p.steps.coerceIn(0L, MemberStats.MAX_STEPS_PER_DAY * days), goalDays = p.goalDays.coerceIn(0, days), longestMs = p.longestMs.coerceIn(0L, 86_400_000L))
}

/** Invite codes: 8 characters from an unambiguous alphabet (no 0/O, 1/I). */
object InviteCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8

    fun generate(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Upper-cases and drops anything outside the alphabet (spaces, dashes). */
    fun normalize(input: String): String = input.uppercase().filter { it in ALPHABET }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}
