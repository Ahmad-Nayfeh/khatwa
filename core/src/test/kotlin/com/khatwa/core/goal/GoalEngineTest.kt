package com.khatwa.core.goal

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalEngineTest {
    private val start = LocalDate.of(2026, 1, 3)
    private val config = GoalConfig(startDate = start, tempGoal = 3000, finalGoal = 8000, weeklyIncrement = 500)

    private fun week(w: Int, vararg steps: Long): Map<LocalDate, Long> =
        GoalEngine.weekDates(config, w).zip(steps.toList()).toMap()

    @Test
    fun `measurement week uses the temporary goal`() {
        val info = GoalEngine.goalFor(config, start.plusDays(3), emptyMap())
        assertEquals(3000, info.effectiveGoal)
        assertTrue(info.isMeasurementWeek)
        assertEquals(0, info.weekIndex)
    }

    @Test
    fun `week 1 is the measurement average plus the increment`() {
        val history = week(0, 4000, 4200, 3800, 4100, 3900, 4000, 4000) // avg 4000
        val info = GoalEngine.goalFor(config, start.plusDays(7), history)
        assertEquals(4500, info.effectiveGoal)
        assertEquals(1, info.weekIndex)
    }

    @Test
    fun `week 1 never drops below the temporary goal`() {
        val history = week(0, 1000, 1200, 900, 1100, 1000, 1000, 1000) // avg ~1029 + 500 < 3000
        val info = GoalEngine.goalFor(config, start.plusDays(8), history)
        assertEquals(3000, info.effectiveGoal)
    }

    @Test
    fun `the goal rises every week when the user keeps up`() {
        val history = HashMap<LocalDate, Long>()
        history += week(0, 4000, 4000, 4000, 4000, 4000, 4000, 4000) // week1 goal 4500
        history += week(1, 5000, 5000, 5000, 5000, 5000, 5000, 5000) // all achieved -> week2 5000
        history += week(2, 5000, 5000, 5000, 5000, 5000, 5000, 5000) // all achieved -> week3 5500
        assertEquals(4500, GoalEngine.goalForWeek(config, 1, history))
        assertEquals(5000, GoalEngine.goalForWeek(config, 2, history))
        assertEquals(5500, GoalEngine.goalForWeek(config, 3, history))
    }

    @Test
    fun `more than 4 failed days freezes the next week`() {
        val history = HashMap<LocalDate, Long>()
        history += week(0, 4000, 4000, 4000, 4000, 4000, 4000, 4000) // week1 goal 4500
        history += week(1, 1000, 1000, 1000, 1000, 1000, 5000, 5000) // 5 failures -> freeze
        assertEquals(4500, GoalEngine.goalForWeek(config, 2, history))
        // Exactly 4 failures is still allowed to rise.
        history += week(2, 1000, 1000, 1000, 1000, 5000, 5000, 5000)
        assertEquals(5000, GoalEngine.goalForWeek(config, 3, history))
    }

    @Test
    fun `missing days count as failures`() {
        val history = HashMap<LocalDate, Long>()
        history += week(0, 4000, 4000, 4000, 4000, 4000, 4000, 4000)
        // Week 1 has only two recorded days -> 5 missing days = failures.
        history += week(1, 5000, 5000)
        assertEquals(4500, GoalEngine.goalForWeek(config, 2, history))
    }

    @Test
    fun `the goal never exceeds the final goal and stays there`() {
        val history = HashMap<LocalDate, Long>()
        history += week(0, 7900, 7900, 7900, 7900, 7900, 7900, 7900) // avg 7900 + 500 = 8400 -> cap 8000
        assertEquals(8000, GoalEngine.goalForWeek(config, 1, history))
        history += week(1, 9000, 9000, 9000, 9000, 9000, 9000, 9000)
        assertEquals(8000, GoalEngine.goalForWeek(config, 2, history))
        assertTrue(GoalEngine.goalFor(config, start.plusDays(14), history).reachedFinal)
    }

    @Test
    fun `manual goal pauses progression`() {
        val manual = config.copy(manualGoal = 6000)
        val info = GoalEngine.goalFor(manual, start.plusDays(20), emptyMap())
        assertEquals(6000, info.effectiveGoal)
        assertTrue(info.isManual)
    }

    @Test
    fun `dates before the start date belong to week 0`() {
        assertEquals(0, GoalEngine.weekIndex(config, start.minusDays(3)))
        assertEquals(1, GoalEngine.weekIndex(config, start.plusDays(7)))
        assertEquals(1, GoalEngine.weekIndex(config, start.plusDays(13)))
        assertEquals(2, GoalEngine.weekIndex(config, start.plusDays(14)))
    }
}
