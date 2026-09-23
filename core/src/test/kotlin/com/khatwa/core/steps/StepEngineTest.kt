package com.khatwa.core.steps

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepEngineTest {
    private val riyadh: ZoneId = ZoneId.of("Asia/Riyadh")

    private fun at(date: LocalDate, time: String, zone: ZoneId = riyadh): Long =
        date.atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private val day1: LocalDate = LocalDate.of(2026, 3, 10)

    @Test
    fun `first reading starts the day at zero`() {
        val u = StepEngine.onReading(null, 5000, at(day1, "08:00"), riyadh)
        assertEquals(0, u.state.steps)
        assertEquals(day1, u.state.date)
        assertEquals(5000, u.state.baseline)
    }

    @Test
    fun `steps accumulate from the baseline`() {
        var s = StepEngine.onReading(null, 5000, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 5300, at(day1, "09:00"), riyadh).state
        val u = StepEngine.onReading(s, 6100, at(day1, "10:00"), riyadh)
        assertEquals(1100, u.state.steps)
        assertEquals(800, u.delta)
    }

    @Test
    fun `reboot keeps the steps already counted and does not double them`() {
        var s = StepEngine.onReading(null, 5000, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 6100, at(day1, "10:00"), riyadh).state // 1100 steps so far
        // Phone rebooted: the counter restarted near zero.
        val reboot = StepEngine.onReading(s, 12, at(day1, "10:30"), riyadh)
        assertTrue(reboot.rebootDetected)
        assertEquals(1100, reboot.state.steps)
        assertEquals(0, reboot.delta)
        // Walking continues after the reboot.
        val later = StepEngine.onReading(reboot.state, 512, at(day1, "11:00"), riyadh)
        assertEquals(1600, later.state.steps)
        assertEquals(500, later.delta)
        assertFalse(later.rebootDetected)
    }

    @Test
    fun `two reboots in one day still add up correctly`() {
        var s = StepEngine.onReading(null, 100, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 400, at(day1, "09:00"), riyadh).state   // 300
        s = StepEngine.onReading(s, 0, at(day1, "09:10"), riyadh).state     // reboot
        s = StepEngine.onReading(s, 200, at(day1, "10:00"), riyadh).state   // 500
        s = StepEngine.onReading(s, 5, at(day1, "10:05"), riyadh).state     // reboot
        s = StepEngine.onReading(s, 105, at(day1, "11:00"), riyadh).state   // 600
        assertEquals(600, s.steps)
    }

    @Test
    fun `midnight closes the day and opens the next one`() {
        var s = StepEngine.onReading(null, 5000, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 9000, at(day1, "23:50"), riyadh).state // 4000 today
        val u = StepEngine.onReading(s, 9050, at(day1.plusDays(1), "00:10"), riyadh)
        assertEquals(1, u.closed.size)
        assertEquals(day1, u.closed[0].date)
        assertEquals(4000, u.closed[0].steps)
        assertEquals(day1.plusDays(1), u.state.date)
        assertEquals(50, u.state.steps)
        assertEquals(50, u.delta)
    }

    @Test
    fun `rollover without a new reading closes the day with the last known value`() {
        var s = StepEngine.onReading(null, 5000, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 7000, at(day1, "20:00"), riyadh).state
        val u = StepEngine.rollover(s, at(day1.plusDays(1), "00:00"), riyadh)
        assertEquals(2000, u.closed.single().steps)
        assertEquals(0, u.state.steps)
        assertEquals(day1.plusDays(1), u.state.date)
        // A reading later that morning only counts the new steps.
        val morning = StepEngine.onReading(u.state, 7300, at(day1.plusDays(1), "07:00"), riyadh)
        assertEquals(300, morning.state.steps)
    }

    @Test
    fun `reboot across midnight loses nothing that was already counted`() {
        var s = StepEngine.onReading(null, 5000, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 8000, at(day1, "22:00"), riyadh).state // 3000
        // The phone was off overnight; first reading next day is small.
        val u = StepEngine.onReading(s, 40, at(day1.plusDays(1), "07:00"), riyadh)
        assertTrue(u.rebootDetected)
        assertEquals(3000, u.closed.single().steps)
        assertEquals(0, u.state.steps)
        assertEquals(40, u.state.baseline)
    }

    @Test
    fun `several days without readings close only the open day`() {
        var s = StepEngine.onReading(null, 100, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 600, at(day1, "12:00"), riyadh).state
        val u = StepEngine.onReading(s, 650, at(day1.plusDays(3), "12:00"), riyadh)
        assertEquals(listOf(day1), u.closed.map { it.date })
        assertEquals(500, u.closed[0].steps)
        assertEquals(day1.plusDays(3), u.state.date)
        assertEquals(50, u.state.steps)
    }

    @Test
    fun `timezone change forward rolls the day over`() {
        // 23:30 in Riyadh on day1 is already day2 in Dubai? No, both are +3/+4; use a big jump.
        val tokyo = ZoneId.of("Asia/Tokyo") // UTC+9
        var s = StepEngine.onReading(null, 100, at(day1, "20:00"), riyadh).state
        s = StepEngine.onReading(s, 400, at(day1, "21:00"), riyadh).state // 300 steps
        val nowMs = at(day1, "21:00")
        // Same instant seen from Tokyo is 03:00 on day2.
        val u = StepEngine.onZoneChanged(s, nowMs, tokyo)
        assertEquals(day1.plusDays(1), u.state.date)
        assertEquals(300, u.closed.single().steps)
        assertEquals(tokyo.id, u.state.zoneId)
    }

    @Test
    fun `timezone change backward never reopens a closed day`() {
        val la = ZoneId.of("America/Los_Angeles") // UTC-7/-8
        var s = StepEngine.onReading(null, 100, at(day1, "07:00"), riyadh).state
        s = StepEngine.onReading(s, 400, at(day1, "08:00"), riyadh).state
        val nowMs = at(day1, "08:00") // still day1-1 in LA (it is 22:00 the previous day)
        val u = StepEngine.onZoneChanged(s, nowMs, la)
        assertTrue(u.closed.isEmpty())
        assertEquals(day1, u.state.date)
        assertEquals(300, u.state.steps)
        assertEquals(la.id, u.state.zoneId)
    }

    @Test
    fun `identical reading adds nothing`() {
        var s = StepEngine.onReading(null, 100, at(day1, "08:00"), riyadh).state
        s = StepEngine.onReading(s, 150, at(day1, "08:10"), riyadh).state
        val u = StepEngine.onReading(s, 150, at(day1, "08:20"), riyadh)
        assertEquals(0, u.delta)
        assertEquals(50, u.state.steps)
    }
}
