package com.khatwa.core.laptop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LaptopCodeTest {

    @Serializable
    private data class Vector(val secret: String, val date: String, val code: String, val hmacHex: String)

    @Serializable
    private data class Doc(val description: String, val vectors: List<Vector>)

    private fun sharedVectors(): List<Vector> {
        // Gradle runs tests with the module directory as the working directory.
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
            assertEquals(v.code, LaptopCode.code(v.secret, v.date), "secret=${v.secret} date=${v.date}")
            assertEquals(v.code, LaptopCode.code(v.secret, LocalDate.parse(v.date)))
        }
    }

    @Test
    fun `codes are six digits and change with the date`() {
        val a = LaptopCode.code("K7mP2qR9sT4vW6xZ3bN8cD5f", LocalDate.of(2026, 9, 23))
        val b = LaptopCode.code("K7mP2qR9sT4vW6xZ3bN8cD5f", LocalDate.of(2026, 9, 24))
        assertTrue(a.matches(Regex("\\d{6}")))
        assertNotEquals(a, b)
    }

    @Test
    fun `generated secrets are 24 chars from the safe alphabet`() {
        repeat(20) {
            val s = LaptopCode.generateSecret()
            assertEquals(24, s.length)
            assertTrue(s.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789" }, s)
        }
        assertNotEquals(LaptopCode.generateSecret(), LaptopCode.generateSecret())
    }
}
