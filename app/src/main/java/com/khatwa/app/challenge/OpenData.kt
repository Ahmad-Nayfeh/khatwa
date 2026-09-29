package com.khatwa.app.challenge

import android.content.Context
import android.util.Log
import com.khatwa.core.challenge.HourWeather
import com.khatwa.core.challenge.Place
import com.khatwa.core.challenge.PlaceKind
import com.khatwa.core.city.City
import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.LocalDateTime

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class PlaceJson(val id: String, val nameAr: String? = null, val nameEn: String? = null, val kind: String, val lat: Double, val lon: Double) {
    fun toPlace() = runCatching { Place(id, nameAr, nameEn, PlaceKind.valueOf(kind), LatLon(lat, lon)) }.getOrNull()
}

/** Places around a point, fetched once and kept on the phone (see [PlacesRepository]). */
@Serializable
data class PlacesCache(val lat: Double, val lon: Double, val fetchedAtMs: Long, val places: List<PlaceJson>) {
    val center: LatLon get() = LatLon(lat, lon)
}

/**
 * Walk-worthy places from OpenStreetMap (Overpass API): named parks, walkways and running
 * tracks, malls within 5 km, and named mosques within 2.5 km. The public servers are free but
 * busy, so results are kept for 30 days (or until the user is 2 km away), a failure is not
 * retried for 30 minutes, and a second server is tried when the first fails.
 */
class PlacesRepository(context: Context) {
    private val file = File(context.filesDir, "places_cache.json")
    private val _cache = MutableStateFlow(load())
    val cache: StateFlow<PlacesCache?> = _cache.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var lastFailureMs = 0L

    fun needsRefresh(at: LatLon, now: Long = System.currentTimeMillis()): Boolean {
        val c = _cache.value ?: return true
        return now - c.fetchedAtMs > 30L * 24 * 3600 * 1000 || Geo.distanceM(c.center, at) > 2_000
    }

    /** Fetches places around [at] if the cache is missing or stale. Returns true when fresh data is there. */
    suspend fun refreshIfNeeded(at: LatLon): Boolean = mutex.withLock {
        if (!needsRefresh(at)) return true
        if (System.currentTimeMillis() - lastFailureMs < 30 * 60_000) return false
        val body = query(at)
        for (server in SERVERS) {
            try {
                val text = withContext(Dispatchers.IO) { Http.postForm(server, mapOf("data" to body)) }
                val places = parse(text)
                save(PlacesCache(at.lat, at.lon, System.currentTimeMillis(), places))
                Log.i(TAG, "${places.size} places from ${server.substringAfter("//").substringBefore('/')}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "places from $server failed: $e")
            }
        }
        lastFailureMs = System.currentTimeMillis()
        false
    }

    fun save(c: PlacesCache) {
        runCatching { file.writeText(json.encodeToString(PlacesCache.serializer(), c)) }
        _cache.value = c
    }

    private fun load(): PlacesCache? = runCatching { json.decodeFromString(PlacesCache.serializer(), file.readText()) }.getOrNull()

    companion object {
        private const val TAG = "Places"
        val SERVERS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter")

        fun query(at: LatLon): String {
            val a = "(around:5000,${at.lat},${at.lon})"
            val m = "(around:2500,${at.lat},${at.lon})"
            return """[out:json][timeout:25];
                (
                  nwr["leisure"="park"]["name"]$a;
                  way["highway"~"^(footway|pedestrian)$"]["name"]$a;
                  way["leisure"="track"]["name"]$a;
                  nwr["shop"="mall"]["name"]$a;
                  nwr["amenity"="place_of_worship"]["religion"="muslim"]["name"]$m;
                );
                out center tags qt 600;""".trimIndent()
        }

        /** Overpass JSON → places, one per kind and name (a walkway is many short segments). */
        fun parse(text: String): List<PlaceJson> {
            val els = json.parseToJsonElement(text).jsonObject["elements"]?.jsonArray ?: return emptyList()
            val out = LinkedHashMap<String, PlaceJson>()
            for (e in els) {
                val o = e.jsonObject
                val tags = o["tags"]?.jsonObject ?: continue
                fun tag(k: String) = tags[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val kind = when {
                    tag("leisure") == "park" -> PlaceKind.PARK
                    tag("shop") == "mall" -> PlaceKind.MALL
                    tag("amenity") == "place_of_worship" -> PlaceKind.MOSQUE
                    tag("leisure") == "track" || tag("highway") != null -> PlaceKind.WALKWAY
                    else -> continue
                }
                val center = o["center"]?.jsonObject
                val lat = (center?.get("lat") ?: o["lat"])?.jsonPrimitive?.doubleOrNull ?: continue
                val lon = (center?.get("lon") ?: o["lon"])?.jsonPrimitive?.doubleOrNull ?: continue
                val name = tag("name") ?: continue
                val ar = tag("name:ar") ?: name.takeIf { it.any { ch -> ch in '؀'..'ۿ' } }
                val en = tag("name:en") ?: name.takeIf { ar == null }
                val key = "$kind:${ar ?: en}"
                if (key in out) continue
                out[key] = PlaceJson("${o["type"]?.jsonPrimitive?.content}/${o["id"]?.jsonPrimitive?.long}", ar, en, kind.name, lat, lon)
            }
            return out.values.toList()
        }
    }
}

@Serializable
data class HourJson(val hour: String, val tempC: Double, val feelsC: Double, val rainPct: Int, val uv: Double) {
    fun toHour() = HourWeather(LocalDateTime.parse(hour), tempC, feelsC, rainPct, uv)
}

@Serializable
data class WeatherCache(val cityId: String, val fetchedAtMs: Long, val hours: List<HourJson>)

/**
 * Hourly forecast for the chosen city (Open-Meteo, free for non-commercial apps, no key): felt
 * temperature, chance of rain and UV index for today and tomorrow. Kept for 3 hours. Only the
 * city center is sent, never the user's position.
 */
class WeatherRepository(context: Context) {
    private val file = File(context.filesDir, "weather_cache.json")
    private val _cache = MutableStateFlow(load())
    val cache: StateFlow<WeatherCache?> = _cache.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var lastFailureMs = 0L

    suspend fun refreshIfNeeded(city: City): Boolean = mutex.withLock {
        val c = _cache.value
        if (c != null && c.cityId == city.id && System.currentTimeMillis() - c.fetchedAtMs < 3 * 3600_000L) return true
        if (System.currentTimeMillis() - lastFailureMs < 20 * 60_000) return false
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${city.lat}&longitude=${city.lon}" +
            "&hourly=temperature_2m,apparent_temperature,precipitation_probability,uv_index&timezone=Asia%2FRiyadh&forecast_days=2"
        try {
            val text = withContext(Dispatchers.IO) { Http.get(url) }
            save(WeatherCache(city.id, System.currentTimeMillis(), parse(text)))
            true
        } catch (e: Exception) {
            Log.w("Weather", "forecast failed: $e")
            lastFailureMs = System.currentTimeMillis()
            false
        }
    }

    fun save(c: WeatherCache) {
        runCatching { file.writeText(json.encodeToString(WeatherCache.serializer(), c)) }
        _cache.value = c
    }

    private fun load(): WeatherCache? = runCatching { json.decodeFromString(WeatherCache.serializer(), file.readText()) }.getOrNull()

    companion object {
        fun parse(text: String): List<HourJson> {
            val h = json.parseToJsonElement(text).jsonObject["hourly"]!!.jsonObject
            fun arr(k: String): JsonArray = h[k]!!.jsonArray
            val times = arr("time")
            return times.indices.mapNotNull { i ->
                val t = arr("temperature_2m")[i].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                val f = arr("apparent_temperature")[i].jsonPrimitive.doubleOrNull ?: t
                HourJson(
                    times[i].jsonPrimitive.content, t, f,
                    h["precipitation_probability"]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.intOrNull ?: 0,
                    h["uv_index"]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            }
        }
    }
}
