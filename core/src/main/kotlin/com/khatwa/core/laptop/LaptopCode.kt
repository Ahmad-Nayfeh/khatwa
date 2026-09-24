package com.khatwa.core.laptop

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Laptop lock codes, shared with the Windows program (laptop-lock/KhatwaLock.Core/ChallengeCodes.cs).
 *
 * The phone and the laptop are paired once with a 16-character secret. Every phone lock
 * ("challenge") gets a random 4-character id, from which two codes are derived:
 *
 *  - lock code   = id + first 4 chars of MAC("lock:" + id)     -> 8 chars, shown as XXXX-XXXX
 *  - unlock code = first 8 chars of MAC("unlock:" + id)         -> 8 chars, shown as XXXX-XXXX
 *
 * MAC = HMAC-SHA256(key = secret as UTF-8, message as UTF-8); each byte maps to ALPHABET[b % 32].
 * The alphabet has 32 symbols without look-alike glyphs (no 0/O, no 1/I). Input is normalised
 * (upper-cased, separators removed) before comparison. Both implementations are checked against
 * shared/hmac-vectors.json in CI.
 */
object LaptopCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val SECRET_LENGTH = 16
    const val ID_LENGTH = 4
    const val CODE_LENGTH = 8

    fun generateSecret(random: SecureRandom = SecureRandom()): String = randomChars(SECRET_LENGTH, random)

    fun newChallengeId(random: SecureRandom = SecureRandom()): String = randomChars(ID_LENGTH, random)

    fun lockCode(secret: String, challengeId: String): String {
        val id = normalize(challengeId)
        require(id.length == ID_LENGTH) { "challenge id must be $ID_LENGTH chars" }
        return id + macChars(secret, "lock:$id", CODE_LENGTH - ID_LENGTH)
    }

    fun unlockCode(secret: String, challengeId: String): String {
        val id = normalize(challengeId)
        require(id.length == ID_LENGTH) { "challenge id must be $ID_LENGTH chars" }
        return macChars(secret, "unlock:$id", CODE_LENGTH)
    }

    /** Returns the challenge id when [input] is a valid lock code for [secret], else null. */
    fun verifyLockCode(secret: String, input: String): String? {
        val code = normalize(input)
        if (code.length != CODE_LENGTH) return null
        val id = code.substring(0, ID_LENGTH)
        return if (constantTimeEquals(code, lockCode(secret, id))) id else null
    }

    fun verifyUnlockCode(secret: String, challengeId: String, input: String): Boolean {
        val code = normalize(input)
        if (code.length != CODE_LENGTH) return false
        return constantTimeEquals(code, unlockCode(secret, challengeId))
    }

    /** "ABCDEFGH" -> "ABCD-EFGH"; secrets become 4 groups of 4. */
    fun format(code: String): String = normalize(code).chunked(4).joinToString("-")

    /** Upper-cases and drops anything that is not a letter or digit (spaces, dashes, dots). */
    fun normalize(input: String): String =
        input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun isSecret(input: String): Boolean =
        normalize(input).let { it.length == SECRET_LENGTH && it.all { c -> c in ALPHABET } }

    private fun macChars(secret: String, message: String, count: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val h = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(count)
        for (i in 0 until count) sb.append(ALPHABET[(h[i].toInt() and 0xff) % ALPHABET.length])
        return sb.toString()
    }

    private fun randomChars(n: Int, random: SecureRandom): String {
        val sb = StringBuilder(n)
        repeat(n) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}
