package com.khatwa.core.steps

import com.khatwa.core.time.Days
import java.time.LocalDate
import java.time.ZoneId

/**
 * The open day's counter state.
 *
 * Android's TYPE_STEP_COUNTER is cumulative since the last boot, so today's steps are
 * `carry + (lastReading - baseline)`:
 *  - [baseline] is the sensor reading at the start of the current segment,
 *  - [carry] holds steps already credited to this day before the baseline was reset
 *    (a reboot resets the sensor to ~0, so we fold the old segment into carry).
 */
data class DayState(
    val date: LocalDate,
    val zoneId: String,
    val carry: Long,
    val baseline: Long,
    val lastReading: Long,
    val lastUpdatedMs: Long,
) {
    val steps: Long get() = carry + (lastReading - baseline)
}

/** A day that was closed by a rollover (midnight / timezone change / first reading on a new day). */
data class ClosedDay(val date: LocalDate, val steps: Long, val closedAtMs: Long)

data class StepUpdate(
    val state: DayState,
    val closed: List<ClosedDay> = emptyList(),
    /** Steps that this reading added to the open day (0 on reboot or first reading). */
    val delta: Long = 0,
    val rebootDetected: Boolean = false,
)

/**
 * Pure step-counter bookkeeping. Every rule here is unit-tested in `StepEngineTest`.
 *
 * Rules:
 *  1. First reading ever: baseline = reading, carry = 0.
 *  2. Reading lower than the last one = the phone rebooted (the counter restarted).
 *     Fold the previous segment into carry and start a new segment at the new reading.
 *     Nothing is lost and nothing is double-counted.
 *  3. Reading on a later local date = midnight passed. Close the old day with what it had,
 *     open the new day with baseline = last reading, then apply the new reading to the new day.
 *  4. Timezone change re-evaluates "today". The open day only ever moves forward.
 */
object StepEngine {

    fun first(reading: Long, nowMs: Long, zone: ZoneId): DayState = DayState(
        date = Days.localDate(nowMs, zone),
        zoneId = zone.id,
        carry = 0,
        baseline = reading,
        lastReading = reading,
        lastUpdatedMs = nowMs,
    )

    fun onReading(state: DayState?, reading: Long, nowMs: Long, zone: ZoneId): StepUpdate {
        if (state == null) return StepUpdate(first(reading, nowMs, zone))

        var s = state
        var reboot = false
        val closed = ArrayList<ClosedDay>()

        // Rule 2: reboot detection.
        if (reading < s.lastReading) {
            reboot = true
            s = s.copy(carry = s.steps, baseline = reading, lastReading = reading)
        }

        // Rule 3: date rollover (in the current zone, which may differ from the stored one).
        val today = Days.localDate(nowMs, zone)
        if (today.isAfter(s.date)) {
            closed.add(ClosedDay(s.date, s.steps, nowMs))
            s = DayState(
                date = today,
                zoneId = zone.id,
                carry = 0,
                baseline = s.lastReading,
                lastReading = s.lastReading,
                lastUpdatedMs = nowMs,
            )
        } else if (s.zoneId != zone.id) {
            s = s.copy(zoneId = zone.id)
        }

        val delta = if (reboot) 0L else (reading - s.lastReading)
        s = s.copy(lastReading = reading, lastUpdatedMs = nowMs)
        return StepUpdate(state = s, closed = closed, delta = delta, rebootDetected = reboot)
    }

    /**
     * Midnight passed without a new sensor reading (the service was alive but idle, or the
     * alarm fired). Close the day using the last known reading.
     */
    fun rollover(state: DayState, nowMs: Long, zone: ZoneId): StepUpdate =
        onReading(state, state.lastReading, nowMs, zone)

    /** Timezone changed: same as a rollover check in the new zone. Never moves the day backwards. */
    fun onZoneChanged(state: DayState, nowMs: Long, newZone: ZoneId): StepUpdate =
        onReading(state, state.lastReading, nowMs, newZone)
}
