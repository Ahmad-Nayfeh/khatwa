package com.khatwa.app.util

import com.khatwa.app.i18n.I18n
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number and date formatting. Western digits; labels follow the app language ([I18n.current]). */
object Fmt {
    fun n(v: Long): String = String.format(Locale.US, "%,d", v)
    fun n(v: Int): String = n(v.toLong())
    fun kg(v: Double): String = String.format(Locale.US, "%.1f", v)
    fun pct(v: Int): String = (if (v > 0) "+" else "") + String.format(Locale.US, "%d%%", v)

    fun time(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val s = I18n.current
        val suffix = if (h < 12) s.am else s.pm
        val h12 = when (val x = h % 12) { 0 -> 12; else -> x }
        return String.format(Locale.US, "%d:%02d %s", h12, m, suffix)
    }

    fun duration(ms: Long): String {
        val min = ms / 60_000
        val s = I18n.current
        return if (min < 60) s.minutesShort(min) else s.hoursMinutesShort(min / 60, min % 60)
    }

    private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M", Locale.US)
    private val full: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun dayMonth(d: LocalDate): String = d.format(dayMonth)
    fun iso(d: LocalDate): String = d.format(full)

    /** ISO day-of-week (Mon=1..Sun=7) to its name in the app language. */
    fun dayName(isoDay: Int): String = I18n.current.dayNames[isoDay] ?: ""
    fun dayShort(isoDay: Int): String = I18n.current.dayNamesShort[isoDay] ?: ""
    /** Month name (1..12) in the app language. */
    fun month(month: Int): String = I18n.current.monthNames[month - 1]
    fun monthShort(month: Int): String = month(month).take(3)
}
