package com.khatwa.core.city

import com.khatwa.core.geo.LatLon
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses the real bundled list (app/src/main/assets/cities_sa.json). */
class CitiesTest {
    private val cities = Cities.parse(File("../app/src/main/assets/cities_sa.json").readText())
    private fun at(lat: Double, lon: Double) = Cities.locate(cities, LatLon(lat, lon))

    @Test
    fun `the list covers the main Saudi cities with Arabic names and unique ids`() {
        assertTrue(cities.size >= 100, "only ${cities.size}")
        assertEquals(cities.size, cities.map { it.id }.toSet().size)
        assertEquals(cities.size, cities.map { it.ar }.toSet().size)
        for (name in listOf("الرياض", "جدة", "مكة المكرمة", "المدينة المنورة", "الدمام", "الخبر", "الطائف", "تبوك", "أبها", "حائل", "جازان", "نجران", "عنيزة", "بريدة", "الخرج")) {
            assertTrue(cities.any { it.ar == name }, "missing $name")
        }
        // Saudi Arabia only: every center is inside the country's bounding box.
        assertTrue(cities.all { it.lat in 16.0..32.5 && it.lon in 34.4..56.0 })
    }

    @Test
    fun `a point in a neighbourhood finds its city`() {
        assertEquals("الرياض", at(24.8136, 46.6120)?.ar) // Al-Malqa, north Riyadh
        assertEquals("الرياض", at(24.5870, 46.7630)?.ar) // Al-Aziziyah, south Riyadh
        assertEquals("الدرعية", at(24.7519, 46.5387)?.ar) // Diriyah, next to Riyadh
        assertEquals("جدة", at(21.62, 39.11)?.ar) // north Jeddah
        assertEquals("الخبر", at(26.30, 50.20)?.ar)
    }

    @Test
    fun `outside every city is null, including abroad`() {
        assertNull(at(22.5, 50.5)) // the Empty Quarter
        assertNull(at(25.2048, 55.2708)) // Dubai
        assertNull(at(30.0444, 31.2357)) // Cairo
    }

    @Test
    fun `search finds cities however the name is typed`() {
        assertEquals("الرياض", Cities.search(cities, "الرياض").first().ar)
        assertEquals("الرياض", Cities.search(cities, "رياض").first().ar)
        assertEquals("جدة", Cities.search(cities, "جده").first().ar)
        assertEquals("أبها", Cities.search(cities, "ابها").first().ar)
        assertEquals("الهفوف", Cities.search(cities, "الأحساء").first().ar)
        assertEquals("مكة المكرمة", Cities.search(cities, "makkah").first().ar)
        assertEquals("الخرج", Cities.search(cities, "kharj").first().ar)
        assertTrue(Cities.search(cities, "zzzz").isEmpty())
        assertEquals(cities.size, Cities.search(cities, " ").size)
    }
}
