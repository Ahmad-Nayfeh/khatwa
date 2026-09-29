package com.khatwa.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import com.khatwa.app.challenge.ChallengeUi
import com.khatwa.app.challenge.HourJson
import com.khatwa.app.challenge.PlaceJson
import com.khatwa.app.challenge.PlacesCache
import com.khatwa.app.challenge.WeatherCache
import com.khatwa.app.data.KhatwaDatabase
import com.khatwa.app.debug.FakeLocationSource
import com.khatwa.core.challenge.Mode
import com.khatwa.core.challenge.PlaceKind
import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * 0.7: choosing the city (list and GPS, and the notice outside the covered cities), the daily
 * challenge on Home, a walk recorded with (fake) GPS that completes it, the walk on the map, and
 * the database upgrade that adds walks without touching older data.
 */
@RunWith(AndroidJUnit4::class)
class WalkChallengeTest {
    private val c get() = TestSupport.container
    private val fake get() = FakeLocationSource.instance
    private val home = LatLon(24.7340, 46.7740) // Al-Rawdah, Riyadh
    private val riyadhId = "108410"

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KhatwaDatabase::class.java)

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
    }

    @Test
    fun databaseUpgradeKeepsDaysAndAddsWalks() {
        val name = "migration-test.db"
        migrations.createDatabase(name, 2).apply {
            execSQL("INSERT INTO days (date, zone, carry, baseline, lastReading, steps, goal, lastUpdatedMs, closed) VALUES ('2026-09-01', 'Asia/Riyadh', 0, 0, 0, 4321, 3000, 0, 1)")
            close()
        }
        val db = migrations.runMigrationsAndValidate(name, 3, true, KhatwaDatabase.MIGRATION_2_3)
        db.query("SELECT steps FROM days WHERE date = '2026-09-01'").use { cur ->
            assertTrue(cur.moveToFirst())
            assertEquals(4321L, cur.getLong(0))
        }
        db.execSQL("INSERT INTO walks (date, startMs, endMs, distanceM, steps, polyline, completed) VALUES ('2026-09-01', 1, 2, 1200.0, 1700, '', 0)")
        db.query("SELECT COUNT(*) FROM walks").use { cur -> cur.moveToFirst(); assertEquals(1, cur.getInt(0)) }
        TestSupport.evidence("migration 2 -> 3: days kept, walks table added")
    }

    @Test
    fun cityFromTheListAndFromGpsAndOutsideTheCoveredCities() {
        runBlocking { c.settings.setCityId(null) }
        TestSupport.launchApp()
        assertNotNull(TestSupport.findRes("home_steps", 15_000))
        assertNotNull("distance missing on Home", TestSupport.findRes("home_distance", 5_000))
        // Home asks for the city first.
        assertNotNull("no 'choose your city' card", TestSupport.scrollToRes("home_scroll", "challenge_choose_city"))
        TestSupport.screenshot("80-home-choose-city")
        assertTrue(TestSupport.clickRes("challenge_choose_city"))
        val field = device.wait(Until.findObject(By.res("city_field")), 8_000)
        assertNotNull("city screen missing", field)
        field!!.click()
        field.text = "الخرج"
        // The drop-down item (not the text field, which shows the same words).
        val item = device.wait(Until.findObject(By.res("city_109353")), 5_000)
            ?: device.findObjects(By.text("الخرج")).firstOrNull { it.className != "android.widget.EditText" }
        if (item == null) TestSupport.dump("missing-city-item")
        assertNotNull("city not in the drop-down", item)
        TestSupport.screenshot("81-city-dropdown")
        item!!.click()
        waitUntil(5_000, "city saved") { runBlocking { c.settings.current().cityId } == "109353" }

        // "Find my city" outside Saudi Arabia (Dubai): the service is not available there yet.
        fake.place(25.2048, 55.2708)
        assertTrue(TestSupport.clickRes("city_detect"))
        assertNotNull("no outside-area notice", device.wait(Until.findObject(By.res("city_outside")), 15_000))
        TestSupport.screenshot("82-city-outside-area")
        assertEquals("the chosen city is kept", "109353", runBlocking { c.settings.current().cityId })

        // In Riyadh, GPS finds the city.
        fake.place(home.lat, home.lon)
        assertTrue(TestSupport.clickRes("city_detect"))
        assertNotNull("city not found by GPS", device.wait(Until.findObject(By.res("city_found")), 15_000))
        waitUntil(5_000, "Riyadh chosen") { runBlocking { c.settings.current().cityId } == riyadhId }
        TestSupport.screenshot("83-city-found-by-gps")
        TestSupport.evidence("city: list (Al Kharj), GPS outside (Dubai) -> notice, GPS in Riyadh -> Riyadh")
    }

    @Test
    fun outsideTheCoveredCitiesHomeSaysTheServiceIsNotAvailable() {
        runBlocking { c.settings.setCityId(riyadhId) }
        fake.place(25.2048, 55.2708) // Dubai
        c.challenges.setOrigin(LatLon(25.2048, 55.2708))
        TestSupport.launchApp()
        assertNotNull(TestSupport.findRes("home_steps", 15_000))
        assertNotNull("no 'not available in your area' card", TestSupport.scrollToRes("home_scroll", "challenge_outside"))
        TestSupport.screenshot("84-home-outside-area")
        fake.place(home.lat, home.lon)
        c.challenges.setOrigin(home)
    }

    @Test
    fun dailyChallengeWalkedWithGpsCompletesAndShowsOnTheMap() {
        runBlocking {
            c.settings.setCityId(riyadhId)
            c.settings.setChallengeStateJson(null)
            c.db.walks().deleteAll()
        }
        fake.place(home.lat, home.lon)
        c.challenges.setOrigin(home)
        seedPlacesAndWeather()
        TestSupport.launchApp()
        assertNotNull(TestSupport.findRes("home_steps", 15_000))
        // Scroll until the card's buttons (its bottom) are on screen.
        val start = TestSupport.scrollToRes("home_scroll", "challenge_start")
        if (start == null) TestSupport.dump("missing-challenge_card")
        assertNotNull("no challenge card", start)
        assertNotNull(TestSupport.findRes("challenge_when", 5_000))
        TestSupport.screenshot("85-home-challenge")

        val ready = runBlocking { withTimeout(10_000) { c.challenges.ui.first { it is ChallengeUi.Ready } } } as ChallengeUi.Ready
        val ch = ready.challenge
        TestSupport.evidence("challenge: ${ch.mode} ${ch.place?.nameAr} ${ch.distanceM.toInt()} m, ${ch.targetSteps} steps, ${ch.label} ${ch.start} feels ${ch.feelsC}")
        assertEquals("a park walk was expected", Mode.TO, ch.mode)
        assertTrue("challenge text shows the place", device.findObject(By.res("challenge_text")).text.contains(ch.place!!.nameAr!!))

        // Start walking: the live map opens and the (fake) GPS walks to the park and back.
        assertTrue(TestSupport.clickRes("challenge_start"))
        assertNotNull("live walk screen missing", device.wait(Until.findObject(By.res("live_walk")), 10_000))
        waitUntil(10_000, "GPS started") { fake.isStarted }
        val route = walkTo(home, ch.place!!.loc) + walkTo(ch.place!!.loc, home).drop(1)
        var t = System.currentTimeMillis()
        val half = route.size / 2
        route.forEachIndexed { i, p ->
            t += 10_000
            fake.emit(p, 6f, t)
            // About 18 steps per 13 m, as a real walk would count.
            if (i > 0) TestSupport.fake().add(18)
            if (i == half) {
                Thread.sleep(1_500)
                TestSupport.screenshot("86-live-walk-at-the-park")
            }
        }
        Thread.sleep(1_500)
        val walked = c.walks.active.value?.distanceM ?: 0.0
        TestSupport.evidence("walked ${walked.toInt()} m over ${route.size} fixes")
        assertTrue("distance not recorded ($walked m)", walked > ch.distanceM * 0.6)
        assertTrue(TestSupport.clickRes("live_stop"))

        // The saved walk: completed, with its route on the map.
        assertNotNull("walk detail missing", device.wait(Until.findObject(By.res("walk_detail")), 10_000))
        assertNotNull("walk did not complete the challenge", device.wait(Until.findObject(By.res("walk_completed")), 5_000))
        Thread.sleep(2_500) // map tiles
        TestSupport.screenshot("87-walk-detail")
        val saved = runBlocking { c.db.walks().all() }
        assertEquals(1, saved.size)
        assertTrue(saved.single().completed)
        assertTrue(Geo.decode(saved.single().polyline).size >= 3)

        // Home: the challenge is done.
        device.pressBack()
        assertTrue(TestSupport.clickRes("tab_home"))
        assertNotNull("challenge not marked done", TestSupport.scrollToRes("home_scroll", "challenge_done"))
        TestSupport.screenshot("88-home-challenge-done")

        // Stats: my walks, and every walk on one map.
        assertTrue(TestSupport.clickRes("tab_stats"))
        assertNotNull("walks card missing", TestSupport.scrollToRes("stats_scroll", "walks_card"))
        TestSupport.screenshot("89-stats-my-walks")
        assertTrue(TestSupport.clickRes("walks_my_map"))
        assertNotNull(device.wait(Until.findObject(By.res("my_map_explored")), 10_000))
        Thread.sleep(2_500)
        TestSupport.screenshot("90-my-map")
        device.pressBack()
    }

    /** Places around Al-Rawdah at many distances (one fits any step target) and a mild forecast. */
    private fun seedPlacesAndWeather() {
        val places = listOf(250.0, 400.0, 550.0, 700.0, 900.0, 1100.0, 1400.0, 1800.0, 2300.0).mapIndexed { i, d ->
            val p = Geo.destination(home, 20.0 + i * 40.0, d)
            PlaceJson("test/$i", "حديقة الاختبار ${i + 1}", "Test park ${i + 1}", PlaceKind.PARK.name, p.lat, p.lon)
        } + PlaceJson("test/mall", "مول الاختبار", "Test mall", PlaceKind.MALL.name, Geo.destination(home, 200.0, 4000.0).lat, Geo.destination(home, 200.0, 4000.0).lon)
        c.places.save(PlacesCache(home.lat, home.lon, System.currentTimeMillis(), places))
        val start = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0).minusHours(1)
        val hours = (0 until 48).map { h -> HourJson(start.plusHours(h.toLong()).toString(), 24.0, 25.0, 0, 2.0) }
        c.weather.save(WeatherCache(riyadhId, System.currentTimeMillis(), hours))
    }

    /** Points every 13 m (1.3 m/s with a reading every 10 s). */
    private fun walkTo(a: LatLon, b: LatLon): List<LatLon> {
        val d = Geo.distanceM(a, b)
        val n = (d / 13.0).toInt().coerceAtLeast(1)
        val bearing = Geo.bearingDeg(a, b)
        return (0..n).map { Geo.destination(a, bearing, d * it / n) }
    }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out: $what")
    }
}
