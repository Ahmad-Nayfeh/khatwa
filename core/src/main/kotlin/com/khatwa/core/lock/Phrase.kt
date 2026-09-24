package com.khatwa.core.lock

import java.text.Normalizer

/**
 * Comparison of the typed emergency phrase with the expected one. Deliberately forgiving about
 * how Arabic is typed (the same rule is in laptop-lock/KhatwaLock.Core/LockDecision.cs):
 *  - diacritics (tashkeel: fatha, damma, shadda, sukun, tanween...) and tatweel are ignored;
 *  - أ إ آ ٱ count as ا, ى as ي, ة as ه;
 *  - invisible format characters (direction marks, zero-width joiners) are ignored;
 *  - punctuation is ignored, whitespace runs count as one space, English is case-insensitive.
 * What still matters: the words themselves, in order. The phrase is meant to be slow to type,
 * not impossible.
 */
object Phrase {
    fun matches(expected: String, typed: String): Boolean {
        val e = normalize(expected)
        return e.isNotEmpty() && e == normalize(typed)
    }

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (c in Normalizer.normalize(input, Normalizer.Form.NFKC)) {
            when {
                c in 'ً'..'ٟ' || c == 'ٰ' || c in 'ؐ'..'ؚ' || c in 'ۖ'..'ۭ' -> Unit
                c == 'ـ' -> Unit // tatweel
                Character.getType(c) == Character.FORMAT.toInt() -> Unit
                c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ' -> sb.append('ا')
                c == 'ى' -> sb.append('ي')
                c == 'ة' -> sb.append('ه')
                Character.isLetterOrDigit(c) -> sb.append(c.lowercaseChar())
                Character.isWhitespace(c) -> sb.append(' ')
                else -> Unit // punctuation
            }
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }
}
