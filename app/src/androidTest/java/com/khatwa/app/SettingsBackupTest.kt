package com.khatwa.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import com.khatwa.app.backup.Backup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 5 evidence: settings screens open, quotes are seeded, backup export/import round-trips. */
@RunWith(AndroidJUnit4::class)
class SettingsBackupTest {
    private val c get() = TestSupport.container

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
    }

    @Test
    fun quotesAreSeededAndSettingsScreensOpen() {
        val count = runBlocking { c.db.quotes().count() }
        assertTrue("expected at least 365 bundled quotes, got $count", count >= 365)
        TestSupport.evidence("quotes seeded: $count")

        TestSupport.launchApp()
        device.wait(Until.findObject(By.res("home_steps")), 15_000)
        device.findObject(By.res("tab_settings"))?.click()
        assertNotNull(device.wait(Until.findObject(By.text("قفل اللابتوب")), 5_000))
        TestSupport.screenshot("40-settings-root")

        assertTrue(TestSupport.clickText("قفل اللابتوب"))
        val gen = device.wait(Until.findObject(By.res("laptop_generate")), 5_000)
        if (gen != null) {
            gen.click()
            val secret = device.wait(Until.findObject(By.res("laptop_secret")), 5_000)
            assertNotNull(secret)
            assertEquals("XXXX-XXXX-XXXX-XXXX".length, secret.text.length)
            TestSupport.evidence("laptop pairing code generated: ${secret.text}")
        }
        TestSupport.screenshot("41-laptop-lock-settings")
        device.pressBack()

        assertTrue(TestSupport.clickText("حالة الصلاحيات"))
        assertNotNull(device.wait(Until.findObject(By.textContains("النشاط البدني")), 5_000))
        TestSupport.screenshot("42-permissions-status")
        device.pressBack()

        assertTrue(TestSupport.clickText("الحكم"))
        assertNotNull(device.wait(Until.findObject(By.textContains("حكمة.")), 5_000))
        TestSupport.screenshot("43-quotes-editor")

        // Light theme evidence: the same screens with the light colour scheme.
        runBlocking { c.settings.setThemeMode(com.khatwa.app.settings.ThemeMode.LIGHT) }
        TestSupport.launchApp()
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
        Thread.sleep(800)
        TestSupport.screenshot("44-light-home")
        device.findObject(By.res("tab_stats"))?.click()
        device.wait(Until.hasObject(By.textContains("آخر 30 يوماً")), 5_000)
        Thread.sleep(800)
        TestSupport.screenshot("45-light-stats")
        runBlocking { c.settings.setThemeMode(com.khatwa.app.settings.ThemeMode.DARK) }
    }

    @Test
    fun backupRoundTripRestoresData() {
        val backup = Backup(c)
        runBlocking {
            c.db.weights().deleteAll()
            c.db.weights().insert(com.khatwa.app.data.WeightEntity(date = "2026-01-05", kg = 77.7, createdMs = 1L))
            c.settings.setLaptopSecret("abcdefghijklmnopqrstuvwx")
        }
        val json = runBlocking { backup.export() }
        assertTrue(json.contains("\"app\": \"khatwa\""))
        assertTrue(json.contains("77.7"))
        TestSupport.evidence("backup exported: ${json.length} chars")

        runBlocking {
            c.db.weights().deleteAll()
            c.settings.setLaptopSecret(null)
        }
        assertEquals(0, runBlocking { c.db.weights().all().size })

        val summary = runBlocking { backup.import(json) }
        TestSupport.evidence("backup imported: $summary")
        val weights = runBlocking { c.db.weights().all() }
        assertEquals(1, weights.size)
        assertEquals(77.7, weights[0].kg, 0.001)
        assertEquals("abcdefghijklmnopqrstuvwx", runBlocking { c.settings.current().laptopSecret })
        assertTrue(runBlocking { c.settings.current().onboardingDone })
        assertTrue(runBlocking { c.db.quotes().count() } >= 365)
    }
}
