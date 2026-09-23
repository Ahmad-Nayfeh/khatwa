package com.khatwa.core.quotes

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QuotePickerTest {
    @Test
    fun `same date gives the same quote`() {
        val d = LocalDate.of(2026, 9, 23)
        assertEquals(QuotePicker.indexFor(d, 365), QuotePicker.indexFor(d, 365))
    }

    @Test
    fun `indexes stay in range and are spread over a year`() {
        val seen = HashSet<Int>()
        var d = LocalDate.of(2026, 1, 1)
        repeat(365) {
            val i = QuotePicker.indexFor(d, 365)
            assertTrue(i in 0 until 365)
            seen += i
            d = d.plusDays(1)
        }
        assertTrue(seen.size > 200, "only ${seen.size} distinct quotes in a year")
    }

    @Test
    fun `another quote differs from the current one`() {
        repeat(50) { assertNotEquals(3, QuotePicker.another(3, 10)) }
        assertEquals(0, QuotePicker.another(0, 1))
    }
}
