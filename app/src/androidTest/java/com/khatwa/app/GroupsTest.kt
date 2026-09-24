package com.khatwa.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.khatwa.app.TestSupport.device
import com.khatwa.app.groups.GroupsRepository
import com.khatwa.app.groups.GroupsSync
import com.khatwa.core.groups.MemberSort
import com.khatwa.core.groups.Period
import com.khatwa.core.groups.Ranking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Phase 7 evidence against the Firebase Emulator Suite (auth + firestore) running on the CI
 * runner (reachable from the Android emulator as 10.0.2.2). Skipped when no emulator is listening.
 *
 * Flow: user A enables groups and creates a group -> the invite code appears; user B (a second
 * anonymous sign-in in the same process) joins with the code, publishes numbers, and the
 * leaderboard ranks both.
 */
@RunWith(AndroidJUnit4::class)
class GroupsTest {

    private val c get() = TestSupport.container

    @Before
    fun setUp() {
        TestSupport.grantBasics()
        TestSupport.onboardWithFakeSteps()
    }

    private fun emulatorReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(EMULATOR_HOST, 8080), 2_000) }
        true
    }.getOrDefault(false)

    @Test
    fun createJoinAndRank() {
        assumeTrue("firebase emulator not reachable at $EMULATOR_HOST:8080", emulatorReachable())
        GroupsRepository.emulatorHost = EMULATOR_HOST
        assumeTrue("google-services placeholder: groups not configured in this build", c.groups.configured)
        runBlocking { c.settings.setGroupsEnabled(false) }
        // User A has walked 4 200 steps today (fake sensor), so the board has real numbers.
        addSteps(4_200)

        // --- user A through the UI: enable, create a group, read the invite code.
        TestSupport.launchApp()
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
        assertTrue(TestSupport.clickRes("tab_groups"))
        val nick = device.wait(Until.findObject(By.res("groups_nickname")), 8_000)
        assertNotNull("opt-in card missing", nick)
        TestSupport.screenshot("50-groups-optin")
        nick!!.text = "أحمد"
        assertTrue(TestSupport.clickRes("groups_enable"))
        assertNotNull("enabled view missing", device.wait(Until.findObject(By.res("groups_create")), 20_000))
        TestSupport.screenshot("51-groups-enabled-empty")
        assertTrue(TestSupport.clickRes("groups_create"))
        val name = device.wait(Until.findObject(By.res("group_name")), 5_000)
        assertNotNull(name)
        name!!.text = "مشاة الحي"
        assertTrue(TestSupport.clickRes("group_create_confirm"))
        val codeView = device.wait(Until.findObject(By.res("group_invite_code")), 20_000)
        assertNotNull("group detail / invite code missing", codeView)
        var code = codeView!!.text.replace("-", "")
        val end = System.currentTimeMillis() + 10_000
        while (code.length != 8 && System.currentTimeMillis() < end) { Thread.sleep(300); code = device.findObject(By.res("group_invite_code"))?.text?.replace("-", "") ?: "" }
        assertEquals("invite code should be 8 chars, got '$code'", 8, code.length)
        TestSupport.evidence("group created, invite code $code")
        TestSupport.screenshot("52-group-detail-owner")
        val gid = runBlocking { c.groups.observeMyGroups().first().single().id }
        val uidA = c.groups.uid!!
        runBlocking { c.groups.publish(GroupsSync.localStats(c), c.tracker.today.value.date) }
        // User B (same phone in this test) has walked 2 600 more by the time it publishes.
        addSteps(2_600)

        // --- user B: a fresh anonymous identity in the same process joins with the code.
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        runBlocking {
            c.settings.setGroupsNickname("سارة")
            c.groups.ensureProfile("سارة")
            assertTrue(c.groups.uid != uidA)
            val joined = c.groups.joinByCode(code)
            assertEquals(gid, joined)
            // B publishes today's fake steps (whatever the tracker has) so the board has two rows.
            c.groups.publish(GroupsSync.localStats(c), c.tracker.today.value.date)
            val stats = c.groups.observeStats(gid).first()
            assertTrue("expected 2 contributions, got ${stats.size}", stats.size == 2)
            val members = c.groups.observeMembers(gid).first()
            assertEquals(2, members.size)
            val ranked = Ranking.rankMembers(stats, Period.TODAY, MemberSort.STEPS, true, c.tracker.today.value.date)
            TestSupport.evidence("leaderboard: ${ranked.joinToString { "${it.uid.take(6)}=${it.steps}" }}")
            assertEquals("B walked more and should lead", c.groups.uid, ranked[0].uid)
            assertEquals(uidA, ranked[1].uid)
            assertTrue("B should have 2 600 more steps than A", ranked[0].steps - ranked[1].steps == 2_600L)
            // A wrong code and a second join are rejected.
            assertTrue(runCatching { c.groups.joinByCode("ZZZZ9999") }.isFailure)
            assertTrue(runCatching { c.groups.joinByCode(code) }.isFailure)
        }

        // --- the UI as B: two members on the board.
        TestSupport.launchApp()
        assertTrue(TestSupport.clickRes("tab_groups"))
        val card = device.wait(Until.findObject(By.res("group_card_$gid")), 20_000)
        assertNotNull("group card missing for member", card)
        TestSupport.screenshot("53-groups-mine")
        // Open the group. The list can recompose right as the tap lands (a sync finishes), so
        // wait for the detail screen and tap again if it did not open.
        var opened = false
        repeat(3) {
            if (!opened) {
                (device.findObject(By.res("group_card_$gid")) ?: card)?.click()
                opened = device.wait(Until.hasObject(By.res("group_detail")), 8_000)
            }
        }
        if (!opened) TestSupport.dump("missing-group_detail")
        assertTrue("group detail did not open", opened)
        val secondRow = device.wait(Until.findObject(By.res("member_row_1")), 20_000)
        if (secondRow == null) TestSupport.dump("missing-member_row_1")
        assertNotNull("second leaderboard row missing", secondRow)
        TestSupport.screenshot("54-group-leaderboard")
        device.pressBack()
        // Public list: the group is visible with 2 members.
        assertTrue("all-groups tab missing", TestSupport.clickRes("groups_tab_all", 8_000))
        assertNotNull(device.wait(Until.findObject(By.textContains("مشاة الحي")), 15_000))
        Thread.sleep(800)
        TestSupport.screenshot("55-groups-public")

        // B leaves; A cannot leave (owner) but can delete.
        runBlocking {
            c.groups.leave(gid)
            assertEquals(0, c.groups.observeMyGroups().first().size)
        }
        runBlocking { c.settings.setGroupsEnabled(false) }
        GroupsRepository.emulatorHost = null
    }

    private fun addSteps(n: Long) {
        val before = c.tracker.today.value.steps
        TestSupport.fake().add(n)
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && c.tracker.today.value.steps < before + n) Thread.sleep(200)
        assertTrue("fake steps not counted", c.tracker.today.value.steps >= before + n)
        runBlocking { c.tracker.flush() }
    }

    companion object {
        const val EMULATOR_HOST = "10.0.2.2"
    }
}
