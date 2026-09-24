package com.khatwa.core.laptop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaptopCodeTest {

    @Serializable
    private data class Vector(val secret: String, val counter: Long, val lockCode: String, val unlockCode: String)

    @Serializable
    private data class Skip(val secret: String, val last: Long, val next: Long)

    @Serializable
    private data class Pairing(val valid: List<String>, val invalid: List<String>)

    @Serializable
    private data class Doc(val description: String, val window: Int, val vectors: List<Vector>, val nextCounterSkip: Skip, val pairing: Pairing)

    private fun shared(): Doc {
        val candidates = listOf(File("../shared/hmac-vectors.json"), File("shared/hmac-vectors.json"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("shared/hmac-vectors.json not found from ${File(".").absolutePath}")
        return Json { ignoreUnknownKeys = true }.decodeFromString<Doc>(file.readText())
    }

    @Test
    fun `shared vectors match the Kotlin implementation`() {
        val doc = shared()
        assertEquals(LaptopCode.WINDOW, doc.window)
        assertTrue(doc.vectors.size >= 5)
        for (v in doc.vectors) {
            assertEquals(v.lockCode, LaptopCode.lockCode(v.secret, v.counter), "lock ${v.secret}/${v.counter}")
            assertEquals(v.unlockCode, LaptopCode.unlockCode(v.secret, v.counter), "unlock ${v.secret}/${v.counter}")
            assertTrue(LaptopCode.verifyUnlockCode(v.secret, v.counter, v.unlockCode))
            // The laptop finds the counter from its last accepted one (anything within the window).
            assertEquals(v.counter, LaptopCode.verifyLockCode(v.secret, v.lockCode, last = maxOf(0, v.counter - 1)))
        }
    }

    @Test
    fun `next counter skips a lock code that clashes with a recent one`() {
        val s = shared().nextCounterSkip
        assertEquals(s.next, LaptopCode.nextCounter(s.secret, s.last))
        assertTrue(s.next > s.last + 1, "the vector must exercise a real skip")
        // Ordinary case: no clash, simply last + 1.
        assertEquals(1L, LaptopCode.nextCounter("12345678", 0))
    }

    @Test
    fun `lock codes are found anywhere in the window and nowhere else`() {
        val secret = "12345678"
        val code = LaptopCode.lockCode(secret, 500)
        assertEquals(500L, LaptopCode.verifyLockCode(secret, code, last = 0))
        assertEquals(500L, LaptopCode.verifyLockCode(secret, code, last = 499))
        // Already used (last >= counter) or too far ahead: rejected.
        assertNull(LaptopCode.verifyLockCode(secret, code, last = 500))
        val far = LaptopCode.lockCode(secret, 5000)
        if (LaptopCode.verifyLockCode(secret, far, last = 0) != null) {
            // Only possible through a coincidental clash inside 1..1000; then it must not be 5000.
            assertTrue(LaptopCode.verifyLockCode(secret, far, last = 0)!! <= LaptopCode.WINDOW)
        }
        assertEquals(5000L, LaptopCode.verifyLockCode(secret, far, last = 4999))
    }

    @Test
    fun `every challenge a phone hands out is recognised by a laptop that saw none of them`() {
        // The phone runs 300 challenges; the laptop (last = 0) must map each lock code to its own
        // counter, so the unlock code on the phone always matches.
        val secret = "24681357"
        var last = 0L
        repeat(300) {
            val n = LaptopCode.nextCounter(secret, last)
            assertEquals(n, LaptopCode.verifyLockCode(secret, LaptopCode.lockCode(secret, n), last = 0))
            last = n
        }
    }

    @Test
    fun `input is normalised, Arabic digits accepted, wrong codes rejected`() {
        val secret = "12345678"
        assertEquals(1L, LaptopCode.verifyLockCode(secret, " 179 578 ", 0))
        assertEquals(1L, LaptopCode.verifyLockCode(secret, "١٧٩٥٧٨", 0))
        assertEquals(1L, LaptopCode.verifyLockCode(secret, "۱۷۹۵۷۸", 0))
        assertNull(LaptopCode.verifyLockCode(secret, "17957", 0))
        assertNull(LaptopCode.verifyLockCode(secret, "", 0))
        assertTrue(LaptopCode.verifyUnlockCode(secret, 1, "880 387"))
        assertFalse(LaptopCode.verifyUnlockCode(secret, 2, "880387"))
        assertFalse(LaptopCode.verifyUnlockCode(secret, 1, "880388"))
        assertFalse(LaptopCode.verifyUnlockCode("87654321", 1, "880387"))
        assertEquals("179 578", LaptopCode.format("179578"))
        assertEquals("1234 5678", LaptopCode.format("12345678"))
    }

    @Test
    fun `pairing codes carry check digits that catch typos`() {
        val p = shared().pairing
        for (c in p.valid) assertTrue(LaptopCode.isValidPairingCode(c), c)
        for (c in p.invalid) assertFalse(LaptopCode.isValidPairingCode(c), c)
        assertTrue(LaptopCode.isValidPairingCode("1234 5676"))
        assertTrue(LaptopCode.isValidPairingCode("١٢٣٤٥٦٧٦"))
        assertFalse(LaptopCode.isValidPairingCode("1234567"))
        assertFalse(LaptopCode.isValidPairingCode(null))
        // Every single wrong digit and every swap of neighbours is rejected.
        for (good in p.valid) for (i in 0 until 8) {
            for (d in '0'..'9') if (d != good[i]) {
                assertFalse(LaptopCode.isValidPairingCode(good.substring(0, i) + d + good.substring(i + 1)), "$good digit $i -> $d")
            }
            if (i < 7 && good[i] != good[i + 1]) {
                val swapped = good.substring(0, i) + good[i + 1] + good[i] + good.substring(i + 2)
                assertFalse(LaptopCode.isValidPairingCode(swapped), "$good swap $i")
            }
        }
        repeat(200) { assertTrue(LaptopCode.isValidPairingCode(LaptopCode.generateSecret())) }
    }

    @Test
    fun `secrets are eight digits`() {
        val s = LaptopCode.generateSecret()
        assertEquals(8, s.length)
        assertTrue(s.all { it in '0'..'9' })
        assertTrue(LaptopCode.isSecret(s))
        // A secret saved before check digits existed still works for codes.
        assertTrue(LaptopCode.isSecret("12345678"))
        assertTrue(LaptopCode.isSecret("1234 5678"))
        assertFalse(LaptopCode.isSecret("1234567"))
        assertFalse(LaptopCode.isSecret("ABCDEFGHJKLMNPQR")) // the old 16-character format needs re-pairing
        assertFalse(LaptopCode.isSecret(null))
    }
}
