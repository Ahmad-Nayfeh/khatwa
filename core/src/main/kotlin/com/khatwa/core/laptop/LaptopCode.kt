package com.khatwa.core.laptop

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Laptop lock codes, shared with the Windows program (laptop-lock/KhatwaLock.Core/ChallengeCodes.cs).
 *
 * The phone and the laptop are paired once with an 8-digit secret. Every phone lock ("challenge")
 * takes the next number of a counter that starts at 1 after pairing. Two 6-digit codes are derived
 * from it, the same way authenticator apps derive their codes (HMAC + RFC 4226 truncation):
 *
 *  - lock code   = code6(secret, "lock:"   + counter)   typed into the laptop to lock it
 *  - unlock code = code6(secret, "unlock:" + counter)   typed into the laptop to unlock it
 *
 * code6 = HMAC-SHA256(key = secret UTF-8, message UTF-8), offset = h[31] & 0x0F,
 *         value = ((h[off] & 0x7F) << 24 | h[off+1] << 16 | h[off+2] << 8 | h[off+3]) mod 1 000 000,
 *         zero-padded to 6 digits.
 *
 * The laptop does not know the phone's counter: it remembers the last counter it accepted and
 * tries the next [WINDOW] counters. The phone skips any counter whose lock code equals the lock
 * code of one of the [WINDOW] counters before it, so the laptop always finds the right one and
 * the unlock code shown on the phone always matches. Both implementations are checked against
 * shared/hmac-vectors.json in CI.
 */
object LaptopCode {
    const val SECRET_LENGTH = 8
    const val CODE_LENGTH = 6
    /** How many counters ahead of its last accepted one the laptop looks. */
    const val WINDOW = 1000

    fun generateSecret(random: SecureRandom = SecureRandom()): String =
        buildString { repeat(SECRET_LENGTH) { append('0' + random.nextInt(10)) } }

    fun lockCode(secret: String, counter: Long): String = code6(secret, "lock:$counter")

    fun unlockCode(secret: String, counter: Long): String = code6(secret, "unlock:$counter")

    /**
     * The counter for the next challenge after [last]: the first one whose lock code is not also
     * the lock code of any of the [WINDOW] counters before it.
     */
    fun nextCounter(secret: String, last: Long): Long {
        var n = last + 1
        while (true) {
            val code = lockCode(secret, n)
            val clash = (maxOf(1L, n - WINDOW) until n).any { lockCode(secret, it) == code }
            if (!clash) return n
            n++
        }
    }

    /** The counter when [input] is the lock code of one of the [WINDOW] counters after [last], else null. */
    fun verifyLockCode(secret: String, input: String, last: Long): Long? {
        val code = normalize(input)
        if (code.length != CODE_LENGTH) return null
        for (n in last + 1..last + WINDOW) if (constantTimeEquals(code, lockCode(secret, n))) return n
        return null
    }

    fun verifyUnlockCode(secret: String, counter: Long, input: String): Boolean {
        val code = normalize(input)
        return code.length == CODE_LENGTH && constantTimeEquals(code, unlockCode(secret, counter))
    }

    /** "123456" -> "123 456"; "12345678" -> "1234 5678". */
    fun format(code: String): String {
        val n = normalize(code)
        return if (n.length == SECRET_LENGTH) n.chunked(4).joinToString(" ") else n.chunked(3).joinToString(" ")
    }

    /** Keeps digits only; Arabic-Indic and Eastern Arabic-Indic digits become ASCII digits. */
    fun normalize(input: String): String = buildString {
        for (c in input) when (c) {
            in '0'..'9' -> append(c)
            in '٠'..'٩' -> append('0' + (c - '٠'))
            in '۰'..'۹' -> append('0' + (c - '۰'))
        }
    }

    fun isSecret(input: String?): Boolean = input != null && normalize(input).length == SECRET_LENGTH &&
        input.all { it.isDigit() || it == ' ' || it == '-' }

    private fun code6(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalize(secret).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val h = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val off = h[31].toInt() and 0x0f
        val value = ((h[off].toInt() and 0x7f) shl 24) or ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or (h[off + 3].toInt() and 0xff)
        return (value % 1_000_000).toString().padStart(CODE_LENGTH, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
