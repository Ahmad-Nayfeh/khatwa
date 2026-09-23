package com.khatwa.app.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number and date formatting. Western digits, Arabic labels. */
object Fmt {
    fun n(v: Long): String = String.format(Locale.US, "%,d", v)
    fun n(v: Int): String = n(v.toLong())
    fun kg(v: Double): String = String.format(Locale.US, "%.1f", v)
    fun pct(v: Int): String = (if (v > 0) "+" else "") + String.format(Locale.US, "%d%%", v)

    fun time(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val suffix = if (h < 12) "ص" else "م"
        val h12 = when (val x = h % 12) { 0 -> 12; else -> x }
        return String.format(Locale.US, "%d:%02d %s", h12, m, suffix)
    }

    fun duration(ms: Long): String {
        val min = ms / 60_000
        return if (min < 60) "$min د" else String.format(Locale.US, "%d س %d د", min / 60, min % 60)
    }

    private val dayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M", Locale.US)
    private val full: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun dayMonth(d: LocalDate): String = d.format(dayMonth)
    fun iso(d: LocalDate): String = d.format(full)

    val arabicDays = mapOf(
        1 to "الاثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس", 5 to "الجمعة", 6 to "السبت", 7 to "الأحد",
    )
    val arabicDaysShort = mapOf(
        1 to "ن", 2 to "ث", 3 to "ر", 4 to "خ", 5 to "ج", 6 to "س", 7 to "ح",
    )
    val arabicMonths = listOf(
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر",
    )
}
