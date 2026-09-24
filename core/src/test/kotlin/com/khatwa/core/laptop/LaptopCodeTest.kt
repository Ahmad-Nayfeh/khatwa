package com.khatwa.core.laptop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaptopCodeTest {

    @Serializable
    private data class Vector(val secret: String, val challengeId: String, val lockCode: String, val unlockCode: String)

    @Serializable
    private data class Doc(val description: String, val vectors: List<Vector>)

    private fun sharedVectors(): List<Vector> {
        val candidates = listOf(File("../shared/hmac-vectors.json"), File("shared/hmac-vectors.json"))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("shared/hmac-vectors.json not found from ${File(".").absolutePath}")
        return Json { ignoreUnknownKeys = true }.decodeFromString<Doc>(file.readText()).vectors
    }

    @Test
    fun `shared vectors match the Kotlin implementation`() {
        val vectors = sharedVectors()
        assertTrue(vectors.size >= 5)
        for (v in vectors) {
            assertEquals(v.lockCode, LaptopCode.lockCode(v.secret, v.challengeId), "lock ${v.secret}/${v.challengeId}")
            assertEquals(v.unlockCode, LaptopCode.unlockCode(v.secret, v.challengeId), "unlock ${v.secret}/${v.challengeId}")
            assertEquals(v.challengeId, LaptopCode.verifyLockCode(v.secret, v.lockCode))
            assertTrue(LaptopCode.verifyUnlockCode(v.secret, v.challengeId, v.unlockCode))
        }
    }

    @Test
    fun `codes are 8 chars from the alphabet and differ per challenge`() {
        val a = LaptopCode.lockCode("K7MP2QR9ST4VW6XZ", "AAAA")
        val b = LaptopCode.lockCode("K7MP2QR9ST4VW6XZ", "AAAB")
        assertEquals(8, a.length)
        assertTrue(a.all { it in LaptopCode.ALPHABET }, a)
        assertNotEquals(a, b)
        assertNotEquals(LaptopCode.unlockCode("K7MP2QR9ST4VW6XZ", "AAAA"), LaptopCode.unlockCode("K7MP2QR9ST4VW6XZ", "AAAB"))
        assertNotEquals(LaptopCode.unlockCode("K7MP2QR9ST4VW6XZ", "AAAA"), LaptopCode.unlockCode("ZZZZZZZZZZZZZZZZ", "AAAA"))
    }

    @Test
    fun `input is normalised, wrong codes are rejected`() {
        val secret = "ABCDEFGHJKLMNPQR"
        val lock = LaptopCode.lockCode(secret, "K7MP") // K7MPY3HE
        assertEquals("K7MP", LaptopCode.verifyLockCode(secret, " k7mp-y3he "))
        assertEquals("K7MP-Y3HE", LaptopCode.format(lock))
        assertNull(LaptopCode.verifyLockCode(secret, "K7MP-Y3HF"))
        assertNull(LaptopCode.verifyLockCode(secret, "K7MPY3H"))
        assertNull(LaptopCode.verifyLockCode("OTHERSECRET12345", lock))
        assertTrue(LaptopCode.verifyUnlockCode(secret, "K7MP", "mtl5-n2ms"))
        assertFalse(LaptopCode.verifyUnlockCode(secret, "K7MP", "MTL5N2MT"))
        assertFalse(LaptopCode.verifyUnlockCode(secret, "2345", "MTL5N2MS"))
    }

    @Test
    fun `generated secrets and ids use the safe alphabet`() {
        repeat(20) {
            val s = LaptopCode.generateSecret()
            assertEquals(16, s.length)
            assertTrue(LaptopCode.isSecret(s), s)
            assertTrue(LaptopCode.isSecret(LaptopCode.format(s)), LaptopCode.format(s))
            assertEquals(4, LaptopCode.newChallengeId().length)
        }
        assertNotEquals(LaptopCode.generateSecret(), LaptopCode.generateSecret())
        assertFalse(LaptopCode.isSecret("ABCDEFGHJKLMNPQ"))
        assertFalse(LaptopCode.isSecret("ABCDEFGHJKLMNPQ0"))
    }
}
