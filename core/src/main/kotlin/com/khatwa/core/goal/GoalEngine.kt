package com.khatwa.core.goal

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Progressive goal configuration. Weeks are 7-day blocks counted from [startDate]
 * (the day the user finished onboarding), so week 0 is the measurement week.
 */
data class GoalConfig(
    val startDate: LocalDate,
    val tempGoal: Int = 3000,
    val finalGoal: Int = 8000,
    val weeklyIncrement: Int = 500,
    /** When set, progression is paused and this value is the effective goal. */
    val manualGoal: Int? = null,
    /** More failed days than this in a week freezes the next week's goal. */
    val maxFailedDaysPerWeek: Int = 4,
)

data class GoalInfo(
    val effectiveGoal: Int,
    val weekIndex: Int,
    val isMeasurementWeek: Boolean,
    val isManual: Boolean,
    val reachedFinal: Boolean,
)

/**
 * Rules (all unit-tested in `GoalEngineTest`):
 *  - Week 0 (days 1..7): goal = tempGoal.
 *  - Week 1: goal = max(tempGoal, round(avg steps of week 0) + increment). The max() keeps
 *    the promise that the goal never drops automatically.
 *  - Week n>1: goal = goal(n-1) + increment, unless week n-1 had more than
 *    maxFailedDaysPerWeek days below goal(n-1): then goal(n) = goal(n-1) (frozen).
 *  - Capped at finalGoal; once reached it stays there.
 *  - manualGoal overrides everything.
 */
object GoalEngine {

    fun weekIndex(config: GoalConfig, date: LocalDate): Int {
        val days = ChronoUnit.DAYS.between(config.startDate, date)
        return if (days < 0) 0 else (days / 7).toInt()
    }

    fun weekDates(config: GoalConfig, week: Int): List<LocalDate> {
        val start = config.startDate.plusDays(week * 7L)
        return (0 until 7).map { start.plusDays(it.toLong()) }
    }

    /**
     * @param stepsByDate steps for every known day; missing days count as 0.
     */
    fun goalFor(config: GoalConfig, date: LocalDate, stepsByDate: Map<LocalDate, Long>): GoalInfo {
        config.manualGoal?.let {
            return GoalInfo(it, weekIndex(config, date), false, true, it >= config.finalGoal)
        }
        val week = weekIndex(config, date)
        val goal = goalForWeek(config, week, stepsByDate)
        return GoalInfo(
            effectiveGoal = goal,
            weekIndex = week,
            isMeasurementWeek = week == 0,
            isManual = false,
            reachedFinal = goal >= config.finalGoal,
        )
    }

    fun goalForWeek(config: GoalConfig, week: Int, stepsByDate: Map<LocalDate, Long>): Int {
        if (week <= 0) return config.tempGoal
        var goal = config.tempGoal
        for (w in 1..week) {
            val prevWeekDates = weekDates(config, w - 1)
            val prevGoal = goal
            val candidate = if (w == 1) {
                val avg = prevWeekDates.map { stepsByDate[it] ?: 0L }.average()
                maxOf(config.tempGoal, avg.roundToInt() + config.weeklyIncrement)
            } else {
                val failed = prevWeekDates.count { (stepsByDate[it] ?: 0L) < prevGoal }
                if (failed > config.maxFailedDaysPerWeek) prevGoal else prevGoal + config.weeklyIncrement
            }
            goal = maxOf(prevGoal, minOf(candidate, config.finalGoal))
            // If the user lowered the final goal below the current one, follow the user.
            if (goal > config.finalGoal) goal = config.finalGoal
        }
        return goal
    }
}
