package com.khatwa.core.quotes

import java.time.LocalDate

/** Deterministic "quote of the day" index: the same date always maps to the same quote. */
object QuotePicker {
    fun indexFor(date: LocalDate, count: Int): Int {
        if (count <= 0) return 0
        val key = date.toEpochDay()
        // Simple LCG-style scrambling so consecutive days do not walk the list in order.
        val scrambled = (key * 2654435761L) xor (key ushr 3)
        return Math.floorMod(scrambled, count.toLong()).toInt()
    }

    /** A different index than [current], or [current] itself when only one quote exists. */
    fun another(current: Int, count: Int, random: kotlin.random.Random = kotlin.random.Random.Default): Int {
        if (count <= 1) return 0
        var next = random.nextInt(count)
        while (next == current) next = random.nextInt(count)
        return next
    }
}
