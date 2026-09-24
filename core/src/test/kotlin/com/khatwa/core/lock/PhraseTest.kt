package com.khatwa.core.lock

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhraseTest {
    private val withTashkeel = "أختار الاستسلام اليوم وأعلم أن هذا يُسجَّل"

    @Test
    fun `typed without diacritics or hamza still matches`() {
        assertTrue(Phrase.matches(withTashkeel, "أختار الاستسلام اليوم وأعلم أن هذا يسجل"))
        assertTrue(Phrase.matches(withTashkeel, "اختار الاستسلام اليوم واعلم ان هذا يسجل"))
        assertTrue(Phrase.matches(withTashkeel, "  اختار  الاستسلام اليوم، واعلم ان هذا يسجل. "))
        assertTrue(Phrase.matches(withTashkeel, "‏اختار الاستسلام اليوم واعلم ان هذا يـسـجـل"))
        assertTrue(Phrase.matches(withTashkeel, withTashkeel))
    }

    @Test
    fun `the words still have to be there`() {
        assertFalse(Phrase.matches(withTashkeel, "اختار الاستسلام اليوم"))
        assertFalse(Phrase.matches(withTashkeel, "اختار الاستسلام اليوم واعلم ان هذا لا يسجل"))
        assertFalse(Phrase.matches(withTashkeel, ""))
        assertFalse(Phrase.matches("", ""))
    }

    @Test
    fun `the long default phrase is accepted however it is typed`() {
        val long = "أختار الاستسلام اليوم بدلا من المشي، وأعلم أن هذا يسجل علي، وأعد نفسي أن أحاول من جديد غدا"
        assertTrue(Phrase.matches(long, "اختار الاستسلام اليوم بدلا من المشي واعلم ان هذا يسجل على واعد نفسي ان احاول من جديد غدا"))
        assertFalse(Phrase.matches(long, "اختار الاستسلام اليوم بدلا من المشي"))
    }

    @Test
    fun `English is case and punctuation insensitive`() {
        val en = "I choose to give up today and I know this is recorded"
        assertTrue(Phrase.matches(en, "i choose to give up today, and I know this is recorded."))
        assertFalse(Phrase.matches(en, "I choose to give up today"))
    }
}
