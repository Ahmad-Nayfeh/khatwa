package com.khatwa.core.challenge

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import com.khatwa.core.geo.Explored
import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt

enum class PlaceKind { PARK, WALKWAY, MOSQUE, MALL }

/** A named place worth walking to (from OpenStreetMap). */
data class Place(val id: String, val nameAr: String?, val nameEn: String?, val kind: PlaceKind, val loc: LatLon) {
    fun name(arabic: Boolean): String = (if (arabic) nameAr ?: nameEn else nameEn ?: nameAr).orEmpty()
}

/** One forecast hour: air and felt temperature, chance of rain, UV index. */
data class HourWeather(val hour: LocalDateTime, val tempC: Double, val feelsC: Double, val rainPct: Int, val uv: Double)

enum class Prayer { FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA }

/** A day's prayer times (local wall-clock). */
data class PrayerDay(val date: LocalDate, val times: Map<Prayer, LocalDateTime>) {
    operator fun get(p: Prayer): LocalDateTime = times.getValue(p)
}

object Prayers {
    /** Umm al-Qura (the official Saudi calendar method), Shafi'i Asr, computed on the phone. */
    fun day(date: LocalDate, at: LatLon, zone: ZoneId): PrayerDay {
        val params = CalculationMethod.UMM_AL_QURA.parameters.apply { madhab = Madhab.SHAFI }
        val pt = PrayerTimes(Coordinates(at.lat, at.lon), DateComponents(date.year, date.monthValue, date.dayOfMonth), params)
        fun local(d: java.util.Date) = LocalDateTime.ofInstant(d.toInstant(), zone)
        return PrayerDay(
            date,
            mapOf(
                Prayer.FAJR to local(pt.fajr), Prayer.SUNRISE to local(pt.sunrise), Prayer.DHUHR to local(pt.dhuhr),
                Prayer.ASR to local(pt.asr), Prayer.MAGHRIB to local(pt.maghrib), Prayer.ISHA to local(pt.isha),
            ),
        )
    }
}

/** How the challenge is walked. */
enum class Mode {
    /** Walk from where you are to the place and back. */
    TO,
    /** Go to the place (park track, mall) and walk the distance there. */
    AT,
    /** A round walk from where you are, toward streets you have not walked yet. */
    LOOP,
}

/** When to go, in words people use: after a prayer, in the morning, or now. */
enum class TimeLabel { NOW, AFTER_FAJR, MORNING, AFTER_DHUHR, AFTER_ASR, AFTER_MAGHRIB, AFTER_ISHA, FOR_PRAYER }

/** A past daily challenge: which place, and whether it was walked. */
data class ChallengeRecord(val date: LocalDate, val placeId: String?, val done: Boolean)

data class ChallengeInput(
    val now: LocalDateTime,
    val stepsToday: Long,
    val goal: Int,
    /** Average daily steps over the last two weeks. */
    val averageSteps: Int,
    val strideM: Double,
    /** Where the user is (null without location permission). */
    val origin: LatLon?,
    val cityCenter: LatLon,
    val places: List<Place>,
    val weather: List<HourWeather>,
    /** Today's and tomorrow's prayer times. */
    val prayers: List<PrayerDay>,
    val walkedCells: Set<Long>,
    val history: List<ChallengeRecord>,
)

data class Challenge(
    val mode: Mode,
    val place: Place?,
    val targetSteps: Int,
    /** Walking distance of the challenge (round trip for [Mode.TO]). */
    val distanceM: Double,
    val start: LocalDateTime,
    val label: TimeLabel,
    /** The prayer a mosque walk is timed for. */
    val prayer: Prayer?,
    /** Felt temperature at the start, when a forecast is available. */
    val feelsC: Double?,
    /** Heading for a [Mode.LOOP] walk (degrees from north), when the user's position is known. */
    val bearingDeg: Double?,
    val tomorrow: Boolean,
    val score: Double,
) {
    /** Stable key of this suggestion (a place, or the loop). */
    val key: String get() = place?.id ?: "loop"
    val minutes: Int get() = (distanceM / ChallengeEngine.WALK_MPS / 60.0).roundToInt().coerceAtLeast(1)
}

/**
 * Picks today's walk. Every candidate (walk to a place and back, walk at a place, or a loop) gets a
 * score from how well its distance fits the steps you still need, the kind of place, how new the
 * way is, and whether you went there lately; then the best start time is found from the felt
 * temperature, sun (UV), rain, prayer times, darkness and how long you would wait.
 */
object ChallengeEngine {
    const val WALK_MPS = 1.3
    /** Streets are longer than straight lines. */
    private const val DETOUR = 1.25
    private const val MAX_DRIVE_M = 10_000.0
    private val PRAYERS_IN_DAY = listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

    /** Steps the challenge asks for: what is left of today's goal (or a bonus), adjusted by habit. */
    fun targetSteps(input: ChallengeInput, tomorrow: Boolean): Int {
        val remaining = input.goal - input.stepsToday
        val base = when {
            tomorrow -> maxOf(1500.0, input.goal * 0.4)
            remaining >= 800 -> remaining.toDouble()
            else -> maxOf(1500.0, input.averageSteps * 0.25)
        }
        val day = if (tomorrow) input.now.toLocalDate().plusDays(1) else input.now.toLocalDate()
        var factor = if (day.dayOfWeek == DayOfWeek.FRIDAY || day.dayOfWeek == DayOfWeek.SATURDAY) 1.15 else 1.0
        val recent = input.history.filter { it.date < input.now.toLocalDate() }.sortedByDescending { it.date }.take(3)
        if (recent.size >= 2 && recent.count { !it.done } >= 2) factor *= 0.8
        if (recent.size == 3 && recent.all { it.done }) factor *= 1.1
        // The small epsilon keeps 3000 x 1.15 at 3 500 (binary floating point gives 3449.999...).
        return ((base * factor).coerceIn(1000.0, 8000.0) / 100 + 1e-6).roundToInt() * 100
    }

    /** All suggestions, best first, one per place (the loop is always there as a fallback). */
    fun candidates(input: ChallengeInput): List<Challenge> {
        val tomorrow = planTomorrow(input)
        val day = if (tomorrow) input.now.toLocalDate().plusDays(1) else input.now.toLocalDate()
        val prayers = input.prayers.firstOrNull { it.date == day } ?: return emptyList()
        val target = targetSteps(input, tomorrow)
        val targetM = target * input.strideM
        val from = input.origin
        val recentIds = input.history.filter { it.date >= input.now.toLocalDate().minusDays(14) }.mapNotNull { it.placeId }.toSet()
        val doneIds = input.history.filter { it.done && it.date >= input.now.toLocalDate().minusDays(7) }.mapNotNull { it.placeId }.toSet()
        val out = ArrayList<Challenge>()

        for (p in input.places) {
            val kindScore = when (p.kind) { PlaceKind.WALKWAY -> 1.1; PlaceKind.PARK -> 1.0; PlaceKind.MOSQUE -> 0.9; PlaceKind.MALL -> 0.6 }
            var history = 0.0
            if (p.id in recentIds) history -= 1.0
            if (p.id in doneIds) history -= 0.5
            val straight = Geo.distanceM(from ?: input.cityCenter, p.loc)
            // Walk there and back.
            if (from != null && p.kind != PlaceKind.MALL) {
                val dist = 2 * straight * DETOUR
                val fit = fit(dist / targetM)
                if (fit >= 0.25) {
                    val novelty = Explored.novelty(from, p.loc, input.walkedCells)
                    val base = 2.5 * fit + kindScore + 0.8 * novelty + history
                    val slot = if (p.kind == PlaceKind.MOSQUE) bestMosqueSlot(input, day, prayers, dist, tomorrow)
                    else bestSlot(input, day, prayers, dist, tomorrow, indoor = false)
                    if (slot != null) out += challenge(Mode.TO, p, target, dist, slot, base, tomorrow)
                }
            }
            // Walk at the place: parks, walkways and malls within a short drive.
            if (p.kind != PlaceKind.MOSQUE && straight <= MAX_DRIVE_M && (from == null || 2 * straight * DETOUR > targetM * 1.3)) {
                val drive = if (from == null) 0.4 else 0.6
                val base = 2.5 + kindScore - drive + history
                val slot = bestSlot(input, day, prayers, targetM, tomorrow, indoor = p.kind == PlaceKind.MALL)
                if (slot != null) out += challenge(Mode.AT, p, target, targetM, slot, base, tomorrow)
            }
        }
        // The loop: always possible, heading to the least-walked direction when the position is known.
        val bearing = from?.let { o ->
            (0 until 8).map { it * 45.0 }.maxByOrNull { b ->
                Explored.novelty(o, Geo.destination(o, b, targetM / 2 / DETOUR), input.walkedCells)
            }
        }
        val loopNovelty = if (from != null && bearing != null)
            Explored.novelty(from, Geo.destination(from, bearing, targetM / 2 / DETOUR), input.walkedCells) else 0.5
        bestSlot(input, day, prayers, targetM, tomorrow, indoor = false)?.let {
            out += challenge(Mode.LOOP, null, target, targetM, it, 2.5 + 0.5 + 0.6 * loopNovelty, tomorrow, bearing)
        }
        return out.groupBy { it.key }.map { (_, list) -> list.maxBy { it.score } }.sortedByDescending { it.score }
    }

    /** After 22:00, or when no walk fits before 23:00, the challenge is for tomorrow. */
    private fun planTomorrow(input: ChallengeInput): Boolean = input.now.toLocalTime() >= LocalTime.of(22, 0)

    private fun fit(ratio: Double): Double = exp(-((ratio - 1.0) / 0.3).let { it * it })

    private data class Slot(val start: LocalDateTime, val penalty: Double, val feels: Double?, val label: TimeLabel, val prayer: Prayer?)

    private fun challenge(mode: Mode, place: Place?, target: Int, dist: Double, slot: Slot, base: Double, tomorrow: Boolean, bearing: Double? = null) =
        Challenge(mode, place, target, dist, slot.start, slot.label, slot.prayer, slot.feels, bearing, tomorrow, base - slot.penalty)

    /** Candidate start times every 15 minutes from now (or tomorrow after Fajr) until 22:30. */
    private fun starts(input: ChallengeInput, day: LocalDate, prayers: PrayerDay, tomorrow: Boolean): List<LocalDateTime> {
        val first = if (tomorrow) prayers[Prayer.FAJR].plusMinutes(10)
        else maxOf(roundUp(input.now), prayers[Prayer.FAJR].plusMinutes(10))
        val last = day.atTime(22, 30)
        return generateSequence(first) { it.plusMinutes(15) }.takeWhile { it <= last }.toList()
    }

    private fun roundUp(t: LocalDateTime): LocalDateTime {
        val m = (t.minute + 14) / 15 * 15
        return t.withSecond(0).withNano(0).withMinute(0).plusMinutes(m.toLong())
    }

    private fun bestSlot(input: ChallengeInput, day: LocalDate, prayers: PrayerDay, distM: Double, tomorrow: Boolean, indoor: Boolean): Slot? {
        val dur = Duration.ofSeconds((distM / WALK_MPS).toLong())
        return starts(input, day, prayers, tomorrow)
            .filter { it.plus(dur) <= day.atTime(23, 0) }
            .filter { !indoor || it.toLocalTime() >= LocalTime.of(10, 0) } // malls open late morning
            .map { start ->
                val w = weatherAt(input.weather, start)
                var pen = if (indoor) 0.0 else outdoorPenalty(w, start)
                if (PRAYERS_IN_DAY.any { p -> prayers[p].let { it > start && it < start.plus(dur) } }) pen += if (indoor) 0.3 else 0.6
                if (start.toLocalTime() >= LocalTime.of(22, 0) || start > prayers[Prayer.ISHA].plusMinutes(150)) pen += 0.3
                pen += if (tomorrow) 0.3 else 0.04 * Duration.between(input.now, start).toMinutes() / 60.0
                Slot(start, pen, w?.feelsC, label(input, start, prayers, tomorrow), null)
            }
            .minByOrNull { it.penalty }
    }

    /** A mosque walk arrives about ten minutes before a prayer. */
    private fun bestMosqueSlot(input: ChallengeInput, day: LocalDate, prayers: PrayerDay, distM: Double, tomorrow: Boolean): Slot? {
        val half = Duration.ofSeconds((distM / 2 / WALK_MPS).toLong())
        val earliest = if (tomorrow) day.atStartOfDay() else roundUp(input.now)
        return (listOf(Prayer.FAJR) + PRAYERS_IN_DAY).mapNotNull { p ->
            val start = prayers[p].minusMinutes(10).minus(half).withSecond(0).withNano(0)
            if (start < earliest || start.toLocalDate() != day) return@mapNotNull null
            val w = weatherAt(input.weather, start)
            var pen = outdoorPenalty(w, start) - 0.3 // walking to prayer is a good habit
            pen += if (tomorrow) 0.3 else 0.04 * Duration.between(input.now, start).toMinutes() / 60.0
            Slot(start, pen, w?.feelsC, TimeLabel.FOR_PRAYER, p)
        }.minByOrNull { it.penalty }
    }

    private fun outdoorPenalty(w: HourWeather?, start: LocalDateTime): Double {
        if (w == null) {
            // No forecast: Saudi summer middays are too hot to walk outside.
            val summer = start.monthValue in 5..9
            val midday = start.toLocalTime() >= LocalTime.of(10, 0) && start.toLocalTime() <= LocalTime.of(17, 30)
            return if (summer && midday) 1.5 else 0.0
        }
        val f = w.feelsC
        var pen = when {
            f <= 29 -> 0.0
            f <= 36 -> (f - 29) * 0.1
            f <= 42 -> 0.7 + (f - 36) * 0.25
            else -> 2.2 + (f - 42) * 0.4
        }
        pen += when { w.uv >= 8 -> 0.4; w.uv >= 6 -> 0.2; else -> 0.0 }
        pen += when { w.rainPct >= 60 -> 0.8; w.rainPct >= 30 -> 0.3; else -> 0.0 }
        return pen
    }

    /** Forecast at [t], interpolated between hours; null without a forecast for that time. */
    fun weatherAt(hours: List<HourWeather>, t: LocalDateTime): HourWeather? {
        val before = hours.lastOrNull { !it.hour.isAfter(t) } ?: return null
        val after = hours.firstOrNull { it.hour.isAfter(t) } ?: return before.takeIf { Duration.between(it.hour, t).toMinutes() <= 60 }
        val k = Duration.between(before.hour, t).toMinutes() / Duration.between(before.hour, after.hour).toMinutes().toDouble()
        fun mix(a: Double, b: Double) = a + (b - a) * k
        return HourWeather(t, mix(before.tempC, after.tempC), mix(before.feelsC, after.feelsC),
            mix(before.rainPct.toDouble(), after.rainPct.toDouble()).roundToInt(), mix(before.uv, after.uv))
    }

    private fun label(input: ChallengeInput, t: LocalDateTime, p: PrayerDay, tomorrow: Boolean): TimeLabel = when {
        !tomorrow && Duration.between(input.now, t).toMinutes() <= 20 -> TimeLabel.NOW
        t < p[Prayer.SUNRISE] -> TimeLabel.AFTER_FAJR
        t < p[Prayer.DHUHR] -> TimeLabel.MORNING
        t < p[Prayer.ASR] -> TimeLabel.AFTER_DHUHR
        t < p[Prayer.MAGHRIB] -> TimeLabel.AFTER_ASR
        t < p[Prayer.ISHA] -> TimeLabel.AFTER_MAGHRIB
        else -> TimeLabel.AFTER_ISHA
    }

    /**
     * Whether a recorded walk completes [c]: it reached the place (to), stayed near it for most of
     * the distance (at), or was long enough (loop).
     */
    fun completes(c: Challenge, route: List<LatLon>, walkedM: Double): Boolean = when (c.mode) {
        Mode.TO -> route.any { Geo.distanceM(it, c.place!!.loc) <= 80.0 }
        Mode.AT -> walkedM >= c.distanceM * 0.8 && route.count { Geo.distanceM(it, c.place!!.loc) <= 400.0 } >= route.size / 2
        Mode.LOOP -> walkedM >= c.distanceM * 0.9
    }
}
