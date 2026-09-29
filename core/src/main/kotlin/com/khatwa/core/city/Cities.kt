package com.khatwa.core.city

import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A Saudi city the challenges cover: a center and a radius around it (from assets/cities_sa.json). */
@Serializable
data class City(
    val id: String,
    val ar: String,
    val en: String,
    val regionAr: String,
    val regionEn: String,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
    val population: Int = 0,
    val aliases: List<String> = emptyList(),
) {
    val center: LatLon get() = LatLon(lat, lon)
    fun name(arabic: Boolean) = if (arabic) ar else en
    fun region(arabic: Boolean) = if (arabic) regionAr else regionEn
}

@Serializable
private data class CityFile(val source: String = "", val cities: List<City>)

object Cities {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<City> = json.decodeFromString(CityFile.serializer(), text).cities

    /**
     * The city whose area contains [p]. Areas overlap around big cities (Riyadh and Diriyah), so
     * the one where [p] is relatively closest to the center wins. Null: outside every city.
     */
    fun locate(cities: List<City>, p: LatLon): City? =
        cities.map { it to Geo.distanceM(it.center, p) / (it.radiusKm * 1000.0) }
            .filter { it.second <= 1.0 }
            .minByOrNull { it.second }?.first

    /** Cities matching [query] (Arabic or English, any spelling of hamza / taa marbuta), best first. */
    fun search(cities: List<City>, query: String): List<City> {
        val q = normalize(query)
        if (q.isEmpty()) return cities
        return cities.mapNotNull { c ->
            val names = listOf(c.ar, c.en) + c.aliases
            val keys = names.map { normalize(it) }
            when {
                keys.any { it == q } -> c to 0
                keys.any { it.startsWith(q) } -> c to 1
                keys.any { it.split(' ').any { w -> w.startsWith(q) } } -> c to 2
                keys.any { it.contains(q) } -> c to 3
                normalize(c.regionAr).startsWith(q) || normalize(c.regionEn).startsWith(q) -> c to 4
                else -> null
            }
        }.sortedWith(compareBy({ it.second }, { -it.first.population })).map { it.first }
    }

    /** Lowercase, no diacritics, one form of alef / yaa / taa marbuta, no leading "ال" / "al ". */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s.trim().lowercase()) {
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                'ؤ' -> sb.append('و')
                'ئ' -> sb.append('ي')
                '\'', '’', '‘', '`', '-', '(', ')' -> sb.append(if (ch == '-') ' ' else "")
                else -> if (ch in 'ً'..'ْ' || ch == 'ٰ' || ch == 'ـ') Unit else sb.append(ch)
            }
        }
        return sb.toString()
            .split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { it.removePrefix("ال").removePrefix("al").ifEmpty { it } }
            .trim()
    }
}
