package com.khatwa.core.laptop

import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Daily laptop unlock code, shared with the Windows program.
 *
 * code = HMAC-SHA256(key = secret as UTF-8, message = "YYYY-MM-DD" as UTF-8)
 *        -> first 4 bytes as a big-endian unsigned 31-bit integer -> mod 1_000_000
 *        -> zero-padded to 6 digits.
 *
 * The C# implementation in laptop-lock/ must produce exactly the same digits; both are
 * checked against shared/hmac-vectors.json in CI.
 */
object LaptopCode {
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private const val SECRET_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
    const val SECRET_LENGTH = 24

    fun code(secret: String, date: LocalDate): String = code(secret, date.format(DATE_FORMAT))

    fun code(secret: String, isoDate: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val h = mac.doFinal(isoDate.toByteArray(Charsets.UTF_8))
        val n = ((h[0].toLong() and 0x7f) shl 24) or
            ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or
            (h[3].toLong() and 0xff)
        return String.format("%06d", n % 1_000_000)
    }

    /** 24 characters from an alphabet without look-alike glyphs (no 0/O, 1/l/I). */
    fun generateSecret(random: SecureRandom = SecureRandom()): String {
        val sb = StringBuilder(SECRET_LENGTH)
        repeat(SECRET_LENGTH) { sb.append(SECRET_ALPHABET[random.nextInt(SECRET_ALPHABET.length)]) }
        return sb.toString()
    }
}
