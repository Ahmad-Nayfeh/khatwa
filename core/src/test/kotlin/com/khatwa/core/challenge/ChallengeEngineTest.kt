package com.khatwa.core.challenge

import com.khatwa.core.geo.Explored
import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChallengeEngineTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val home = LatLon(24.7340, 46.7740) // Al-Rawdah, Riyadh
    private val day = LocalDate.of(2026, 9, 29) // a Tuesday
    private val prayers = listOf(Prayers.day(day, home, zone), Prayers.day(day.plusDays(1), home, zone))

    private fun place(id: String, kind: PlaceKind, bearing: Double, meters: Double) =
        Place(id, "مكان $id", "Place $id", kind, Geo.destination(home, bearing, meters))

    /** Felt temperature by hour: [hot] from 10:00 to 17:00, [cool] otherwise. */
    private fun weather(hot: Double, cool: Double, d: LocalDate = day) = (0 until 48).map { h ->
        val t = d.atStartOfDay().plusHours(h.toLong())
        val f = if (t.hour in 10..17) hot else cool
        HourWeather(t, f - 2, f, 0, if (t.hour in 10..15) 9.0 else 1.0)
    }

    private fun input(
        now: LocalDateTime = day.atTime(9, 0),
        steps: Long = 2000,
        goal: Int = 5000,
        places: List<Place> = emptyList(),
        weather: List<HourWeather> = weather(44.0, 30.0),
        walked: Set<Long> = emptySet(),
        history: List<ChallengeRecord> = emptyList(),
        origin: LatLon? = home,
    ) = ChallengeInput(now, steps, goal, 6000, 0.7, origin, home, places, weather, prayers, walked, history)

    @Test
    fun `prayer times for Riyadh are in order and Isha is 90 minutes after Maghrib`() {
        val p = prayers.first()
        val order = listOf(Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA).map { p[it] }
        assertEquals(order.sorted(), order)
        assertEquals(90, Duration.between(p[Prayer.MAGHRIB], p[Prayer.ISHA]).toMinutes())
        assertTrue(p[Prayer.DHUHR].toLocalTime() in LocalTime.of(11, 30)..LocalTime.of(12, 0), "dhuhr ${p[Prayer.DHUHR]}")
        assertTrue(p[Prayer.FAJR].toLocalTime() in LocalTime.of(4, 0)..LocalTime.of(4, 45), "fajr ${p[Prayer.FAJR]}")
        assertTrue(p[Prayer.MAGHRIB].toLocalTime() in LocalTime.of(17, 30)..LocalTime.of(18, 0), "maghrib ${p[Prayer.MAGHRIB]}")
    }

    @Test
    fun `target steps follow what is left of the goal and the user's habit`() {
        assertEquals(3000, ChallengeEngine.targetSteps(input(steps = 2000), tomorrow = false))
        // Goal already met: a bonus walk of a quarter of the average day (at least 1 500).
        assertEquals(1500, ChallengeEngine.targetSteps(input(steps = 6000), tomorrow = false))
        // Friday: 15% more.
        val friday = input(now = LocalDate.of(2026, 10, 2).atTime(9, 0), steps = 2000)
        assertEquals(3500, ChallengeEngine.targetSteps(friday.copy(prayers = prayers), tomorrow = false))
        // Two of the last three challenges skipped: easier.
        val skipped = listOf(ChallengeRecord(day.minusDays(1), null, false), ChallengeRecord(day.minusDays(2), null, false), ChallengeRecord(day.minusDays(3), null, true))
        assertEquals(2400, ChallengeEngine.targetSteps(input(steps = 2000, history = skipped), tomorrow = false))
        // Never more than one long walk.
        assertEquals(8000, ChallengeEngine.targetSteps(input(steps = 0, goal = 15000), tomorrow = false))
    }

    @Test
    fun `on a hot day the walk moves to the cool evening`() {
        val c = ChallengeEngine.candidates(input(now = day.atTime(9, 30))).first()
        assertEquals(Mode.LOOP, c.mode)
        assertTrue(c.start.toLocalTime() >= LocalTime.of(18, 0) || c.start.toLocalTime() < LocalTime.of(10, 0), "start ${c.start}")
        assertTrue((c.feelsC ?: 99.0) <= 32.0, "feels ${c.feelsC}")
    }

    @Test
    fun `a park at the right distance beats one too far and the plain loop`() {
        // 3 000 steps x 0.7 m = 2.1 km; there and back with street detours needs a park ~840 m away.
        val good = place("good", PlaceKind.PARK, 90.0, 840.0)
        val far = place("far", PlaceKind.PARK, 180.0, 3000.0)
        val list = ChallengeEngine.candidates(input(places = listOf(good, far)))
        val first = list.first()
        assertEquals("good", first.place?.id)
        assertEquals(Mode.TO, first.mode)
        assertEquals(2 * 840.0 * 1.25, first.distanceM, 5.0)
        // The far park is still offered, as a walk there (a short drive).
        assertTrue(list.any { it.place?.id == "far" && it.mode == Mode.AT })
        assertTrue(list.any { it.mode == Mode.LOOP })
    }

    @Test
    fun `a place used in the last two weeks gives way to a new one`() {
        val a = place("a", PlaceKind.PARK, 0.0, 840.0)
        val b = place("b", PlaceKind.PARK, 90.0, 840.0)
        val history = listOf(ChallengeRecord(day.minusDays(2), "a", true))
        assertEquals("b", ChallengeEngine.candidates(input(places = listOf(a, b), history = history)).first().place?.id)
        assertEquals("a", ChallengeEngine.candidates(input(places = listOf(a, b))).first().place?.id)
    }

    @Test
    fun `new streets are preferred over streets already walked`() {
        val north = place("north", PlaceKind.PARK, 0.0, 840.0)
        val east = place("east", PlaceKind.PARK, 90.0, 840.0)
        val walkedNorth = Explored.cellsOf(listOf(home, north.loc))
        assertEquals("east", ChallengeEngine.candidates(input(places = listOf(north, east), walked = walkedNorth)).first().place?.id)
        // The loop heads away from walked streets too.
        val loop = ChallengeEngine.candidates(input(walked = walkedNorth)).first { it.mode == Mode.LOOP }
        assertNotNull(loop.bearingDeg)
        assertTrue(loop.bearingDeg!! in 45.0..315.0, "bearing ${loop.bearingDeg}")
    }

    @Test
    fun `when it is hot all day a mall is suggested`() {
        val park = place("park", PlaceKind.PARK, 90.0, 840.0)
        val mall = place("mall", PlaceKind.MALL, 180.0, 4000.0)
        val allHot = weather(46.0, 43.0)
        val c = ChallengeEngine.candidates(input(places = listOf(park, mall), weather = allHot)).first()
        assertEquals("mall", c.place?.id)
        assertEquals(Mode.AT, c.mode)
        assertTrue(c.start.toLocalTime() >= LocalTime.of(10, 0))
    }

    @Test
    fun `a mosque walk arrives about ten minutes before a prayer`() {
        val mosque = place("m", PlaceKind.MOSQUE, 45.0, 840.0)
        val c = ChallengeEngine.candidates(input(places = listOf(mosque), weather = weather(30.0, 26.0))).first { it.place?.id == "m" }
        assertEquals(TimeLabel.FOR_PRAYER, c.label)
        val prayer = assertNotNull(c.prayer)
        val arrival = c.start.plusSeconds((c.distanceM / 2 / ChallengeEngine.WALK_MPS).toLong())
        val before = Duration.between(arrival, prayers.first()[prayer]).toMinutes()
        assertTrue(before in 9..11, "arrives $before min before $prayer")
    }

    @Test
    fun `without a forecast summer middays are avoided`() {
        val c = ChallengeEngine.candidates(input(now = day.atTime(11, 0), weather = emptyList())).first()
        assertTrue(c.start.toLocalTime() > LocalTime.of(17, 30), "start ${c.start}")
        assertEquals(null, c.feelsC)
    }

    @Test
    fun `late at night the challenge is for tomorrow`() {
        val c = ChallengeEngine.candidates(input(now = day.atTime(22, 30))).first()
        assertTrue(c.tomorrow)
        assertEquals(day.plusDays(1), c.start.toLocalDate())
    }

    @Test
    fun `without location places are walked at and the loop has no heading`() {
        val park = place("p", PlaceKind.PARK, 90.0, 2000.0)
        val list = ChallengeEngine.candidates(input(origin = null, places = listOf(park)))
        assertTrue(list.none { it.mode == Mode.TO })
        assertTrue(list.any { it.mode == Mode.AT && it.place?.id == "p" })
        assertEquals(null, list.first { it.mode == Mode.LOOP }.bearingDeg)
    }

    @Test
    fun `a recorded walk completes the challenge it was for`() {
        val park = place("p", PlaceKind.PARK, 90.0, 840.0)
        val c = ChallengeEngine.candidates(input(places = listOf(park))).first()
        val there = (0..84).map { Geo.destination(home, 90.0, it * 10.0) }
        assertTrue(ChallengeEngine.completes(c, there + there.reversed(), 1680.0))
        assertTrue(!ChallengeEngine.completes(c, there.take(40), 400.0))
        val loop = c.copy(mode = Mode.LOOP, place = null)
        assertTrue(ChallengeEngine.completes(loop, there, c.distanceM * 0.95))
        assertTrue(!ChallengeEngine.completes(loop, there, c.distanceM * 0.5))
    }
}
