package com.khatwa.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Calendar helpers shared by the app. The week starts on Sunday (Friday and Saturday are the weekend). */
object Days {
    val WEEK_START: DayOfWeek = DayOfWeek.SUNDAY

    fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    fun startOfDayMs(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Epoch millis of the next local midnight strictly after [epochMs]. */
    fun nextMidnightMs(epochMs: Long, zone: ZoneId): Long {
        val today = localDate(epochMs, zone)
        return startOfDayMs(today.plusDays(1), zone)
    }

    fun weekStart(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(WEEK_START))

    fun weekEnd(date: LocalDate): LocalDate = weekStart(date).plusDays(6)

    fun monthStart(date: LocalDate): LocalDate = date.withDayOfMonth(1)

    fun hourOfDay(epochMs: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(epochMs).atZone(zone).hour

    fun atTime(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)), zone)
            .toInstant().toEpochMilli()

    /** Days between two dates, inclusive of both ends. */
    fun datesBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val out = ArrayList<LocalDate>()
        var d = from
        while (!d.isAfter(to)) {
            out.add(d)
            d = d.plusDays(1)
        }
        return out
    }
}
