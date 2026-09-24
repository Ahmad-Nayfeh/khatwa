package com.khatwa.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import com.khatwa.app.backup.Backup
import kotlinx.coroutines.flow.first
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
        // The bundled set is sourced (attributed) quotes in Arabic and English.
        assertTrue("expected at least 100 bundled quotes, got $count", count >= 100)
        val arabic = runBlocking { c.db.quotes().observeByLang("ar").first().size }
        val english = runBlocking { c.db.quotes().observeByLang("en").first().size }
        assertTrue("expected Arabic and English quotes, got ar=$arabic en=$english", arabic >= 50 && english >= 40)
        TestSupport.evidence("quotes seeded: $count")

        TestSupport.launchApp()
        device.wait(Until.findObject(By.res("home_steps")), 15_000)
        device.findObject(By.res("tab_settings"))?.click()
        assertNotNull(device.wait(Until.findObject(By.text("قفل اللابتوب")), 5_000))
        TestSupport.screenshot("40-settings-root")

        assertTrue(TestSupport.clickText("قفل اللابتوب"))
        val gen = device.wait(Until.findObject(By.res("laptop_generate")), 5_000)
        if (gen != null) {
            // The node can go stale while the screen settles; clickRes re-finds and retries.
            assertTrue(TestSupport.clickRes("laptop_generate"))
            val secret = device.wait(Until.findObject(By.res("laptop_secret")), 5_000)
            assertNotNull(secret)
            assertEquals("1234 5678".length, secret.text.length)
            // The laptop accepts it: 6 digits + 2 check digits.
            assertTrue(com.khatwa.core.laptop.LaptopCode.isValidPairingCode(secret.text))
            TestSupport.evidence("laptop pairing code generated: ${secret.text}")
        }
        TestSupport.screenshot("41-laptop-lock-settings")
        device.pressBack()

        assertTrue(TestSupport.clickText("حالة الصلاحيات"))
        assertNotNull(device.wait(Until.findObject(By.textContains("النشاط البدني")), 5_000))
        TestSupport.screenshot("42-permissions-status")
        device.pressBack()

        assertTrue(TestSupport.clickText("الحكم"))
        assertNotNull(device.wait(Until.findObject(By.textContains("حكمة اليوم")), 5_000))
        TestSupport.screenshot("43-quotes-editor")

        // Light theme evidence: the same screens with the light colour scheme.
        runBlocking { c.settings.setThemeMode(com.khatwa.app.settings.ThemeMode.LIGHT) }
        TestSupport.launchApp()
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
        Thread.sleep(800)
        TestSupport.screenshot("44-light-home")
        device.findObject(By.res("tab_stats"))?.click()
        device.wait(Until.hasObject(By.res("stats_day_steps")), 8_000)
        Thread.sleep(800)
        TestSupport.screenshot("45-light-stats")
        runBlocking { c.settings.setThemeMode(com.khatwa.app.settings.ThemeMode.DARK) }

        // English: the whole UI switches language and direction from the language setting.
        runBlocking { c.settings.setLanguage(com.khatwa.app.settings.AppLanguage.EN) }
        TestSupport.launchApp()
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
        assertNotNull("English tab label missing", device.wait(Until.findObject(By.text("Statistics")), 8_000))
        Thread.sleep(800)
        TestSupport.screenshot("46-english-home")
        device.findObject(By.res("tab_stats"))?.click()
        assertNotNull(device.wait(Until.findObject(By.res("stats_day_steps")), 8_000))
        Thread.sleep(800)
        TestSupport.screenshot("47-english-stats")
        device.findObject(By.res("tab_settings"))?.click()
        assertNotNull(device.wait(Until.findObject(By.text("Laptop lock")), 8_000))
        TestSupport.screenshot("48-english-settings")
        // Switch back through the UI itself (the language chips), then verify Arabic is back.
        TestSupport.scrollForward("settings_scroll")
        val arChip = device.wait(Until.findObject(By.res("settings_language_ar")), 5_000)
        if (arChip != null) arChip.click() else runBlocking { c.settings.setLanguage(com.khatwa.app.settings.AppLanguage.AR) }
        assertNotNull("Arabic not restored", device.wait(Until.findObject(By.text("الإعدادات")), 8_000))
        TestSupport.evidence("language switched en -> ar via ${if (arChip != null) "chip" else "settings"}")
    }

    @Test
    fun backupRoundTripRestoresData() {
        val backup = Backup(c)
        runBlocking {
            c.db.weights().deleteAll()
            c.db.weights().insert(com.khatwa.app.data.WeightEntity(date = "2026-01-05", kg = 77.7, createdMs = 1L))
            c.settings.setLaptopSecret("24681357")
            c.settings.setLaptopCounter(5)
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
        assertEquals("24681357", runBlocking { c.settings.current().laptopSecret })
        assertEquals(5L, runBlocking { c.settings.current().laptopCounter })
        assertTrue(runBlocking { c.settings.current().onboardingDone })
        assertTrue(runBlocking { c.db.quotes().count() } >= 100)
    }
}
