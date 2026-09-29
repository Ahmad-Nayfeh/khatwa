package com.khatwa.app.challenge

import android.content.Context
import android.util.Log
import com.khatwa.app.AppContainer
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.settings.SettingsRepository
import com.khatwa.app.walks.LocationSource
import com.khatwa.core.challenge.Challenge
import com.khatwa.core.challenge.ChallengeEngine
import com.khatwa.core.challenge.ChallengeInput
import com.khatwa.core.challenge.ChallengeRecord
import com.khatwa.core.challenge.Prayers
import com.khatwa.core.city.Cities
import com.khatwa.core.city.City
import com.khatwa.core.geo.Explored
import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The bundled list of Saudi cities and the one the user chose. */
class CityRepository(private val context: Context, private val settings: SettingsRepository) {
    val all: List<City> by lazy { Cities.parse(context.assets.open("cities_sa.json").bufferedReader().use { it.readText() }) }

    fun byId(id: String?): City? = id?.let { i -> all.firstOrNull { it.id == i } }

    val current: Flow<City?> = settings.flow.map { byId(it.cityId) }.distinctUntilChanged()

    sealed interface Detected {
        data class Found(val city: City) : Detected
        /** The phone is outside every city we cover (or outside Saudi Arabia). */
        data object Outside : Detected
        data object NoPermission : Detected
        data object NoFix : Detected
    }

    /** "Find my city" with GPS. */
    suspend fun detect(location: LocationSource): Detected {
        if (!PermissionChecks.location(context) && location.lastKnown() == null) return Detected.NoPermission
        val fix = location.current(15_000) ?: return Detected.NoFix
        return Cities.locate(all, fix.point)?.let { Detected.Found(it) } ?: Detected.Outside
    }
}

@Serializable
data class ChallengeRec(val date: String, val placeId: String? = null, val done: Boolean = false)

/** Today's pick, whether it was done, and the last 30 days (for habit and variety). */
@Serializable
data class ChallengeState(
    val date: String? = null,
    val selectedKey: String? = null,
    val doneKey: String? = null,
    val history: List<ChallengeRec> = emptyList(),
) {
    /** A new day: yesterday's pick goes into the history. */
    fun rolled(today: LocalDate): ChallengeState {
        if (date == today.toString()) return this
        val prev = date?.let { d -> ChallengeRec(d, (doneKey ?: selectedKey)?.takeIf { it != "loop" }, doneKey != null) }
        return ChallengeState(today.toString(), null, null, (history + listOfNotNull(prev)).takeLast(30))
    }
}

sealed interface ChallengeUi {
    /** No city chosen yet. */
    data object NoCity : ChallengeUi
    /** The phone is outside the cities we cover: the service is not available there yet. */
    data object Outside : ChallengeUi
    data class Ready(val city: City, val challenge: Challenge, val done: Boolean, val options: Int) : ChallengeUi
}

/**
 * Builds the daily challenge from everything the engine weighs: steps and goal, habit, step
 * length, where the user is, nearby places, the forecast, prayer times, walked streets and past
 * challenges. Recomputed when any of them changes, and every five minutes as the day goes on.
 */
class ChallengeRepository(private val c: AppContainer) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _origin = MutableStateFlow<LatLon?>(null)
    val origin: StateFlow<LatLon?> = _origin.asStateFlow()
    @Volatile private var lastList: List<Challenge> = emptyList()

    private val walkedCells: Flow<Set<Long>> = c.db.walks().observeAll()
        .map { walks -> walks.flatMapTo(HashSet()) { Explored.cellsOf(Geo.decode(it.polyline)) } as Set<Long> }
        .flowOn(Dispatchers.Default)

    private val averageSteps: Flow<Int> = c.db.days().observeAll().map { days ->
        val closed = days.filter { it.closed }.sortedByDescending { it.date }.take(14)
        if (closed.isEmpty()) 0 else (closed.sumOf { it.steps } / closed.size).toInt()
    }

    private val minuteTick: Flow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(5 * 60_000L) } }

    private data class Inputs(val settings: com.khatwa.app.settings.Settings, val today: com.khatwa.app.steps.Today, val avg: Int)

    val ui: Flow<ChallengeUi> = combine(
        combine(c.settings.flow, c.tracker.today, averageSteps) { s, t, a -> Inputs(s, t, a) },
        c.places.cache, c.weather.cache, walkedCells, combine(_origin, minuteTick) { o, _ -> o },
    ) { inputs, places, weather, cells, origin -> build(inputs, places, weather, cells, origin) }
        .flowOn(Dispatchers.Default)

    private fun state(json: String?): ChallengeState =
        json?.let { runCatching { this.json.decodeFromString(ChallengeState.serializer(), it) }.getOrNull() } ?: ChallengeState()

    private fun build(inputs: Inputs, places: PlacesCache?, weather: WeatherCache?, cells: Set<Long>, origin: LatLon?): ChallengeUi {
        val s = inputs.settings
        val city = c.cities.byId(s.cityId) ?: return ChallengeUi.NoCity
        if (origin != null && Cities.locate(c.cities.all, origin) == null) return ChallengeUi.Outside
        val now = LocalDateTime.now(zone)
        val today = now.toLocalDate()
        val st = state(s.challengeStateJson).rolled(today)
        val input = ChallengeInput(
            now = now,
            stepsToday = inputs.today.steps,
            goal = inputs.today.goal,
            averageSteps = inputs.avg.takeIf { it > 0 } ?: inputs.today.goal,
            strideM = s.strideM,
            origin = origin,
            cityCenter = city.center,
            places = places?.takeIf { p -> Geo.distanceM(p.center, origin ?: city.center) < 8_000 }?.places.orEmpty().mapNotNull { it.toPlace() },
            weather = weather?.takeIf { it.cityId == city.id }?.hours.orEmpty().map { it.toHour() },
            prayers = listOf(Prayers.day(today, city.center, zone), Prayers.day(today.plusDays(1), city.center, zone)),
            walkedCells = cells,
            history = st.history.map { ChallengeRecord(LocalDate.parse(it.date), it.placeId, it.done) },
        )
        val list = ChallengeEngine.candidates(input)
        lastList = list
        val picked = list.firstOrNull { it.key == (st.doneKey ?: st.selectedKey) } ?: list.firstOrNull() ?: return ChallengeUi.NoCity
        return ChallengeUi.Ready(city, picked, st.doneKey != null, list.size)
    }

    /** Refreshes the inputs that come from outside: position, forecast and nearby places. */
    suspend fun refreshInputs() {
        val city = c.cities.byId(c.settings.current().cityId) ?: return
        val loc = c.walks.location
        val fix = loc.lastKnown()?.takeIf { System.currentTimeMillis() - it.timeMs < 30 * 60_000 }
            ?: if (PermissionChecks.location(c.app) || loc.lastKnown() != null) loc.current(8_000) else null
        if (fix != null) _origin.value = fix.point
        // Outside the covered cities there is nothing to fetch (the card says so).
        _origin.value?.let { o -> if (Cities.locate(c.cities.all, o) == null) return }
        c.weather.refreshIfNeeded(city)
        c.places.refreshIfNeeded(_origin.value ?: city.center)
    }

    /** "Another suggestion": the next one in today's list. */
    suspend fun another(current: Challenge) {
        val list = lastList
        if (list.size < 2) return
        val i = list.indexOfFirst { it.key == current.key }
        val next = list[(i + 1).mod(list.size)]
        saveState { it.copy(selectedKey = next.key) }
    }

    /** A walk started for [challengeKey] ended: marks the challenge done if the walk met it. */
    suspend fun onWalkFinished(challengeKey: String?, route: List<LatLon>, distanceM: Double): Boolean {
        val key = challengeKey ?: return false
        val ch = lastList.firstOrNull { it.key == key } ?: return false
        val ok = ChallengeEngine.completes(ch, route, distanceM)
        Log.i("Challenge", "walk for $key completes=$ok (${distanceM.toInt()} m)")
        if (ok) saveState { it.copy(selectedKey = key, doneKey = key) }
        return ok
    }

    private suspend fun saveState(change: (ChallengeState) -> ChallengeState) {
        val today = LocalDate.now(zone)
        val st = change(state(c.settings.current().challengeStateJson).rolled(today))
        c.settings.setChallengeStateJson(json.encodeToString(ChallengeState.serializer(), st))
    }

    /** For tests: sets where the user is without GPS. */
    fun setOrigin(p: LatLon?) { _origin.value = p }
}
