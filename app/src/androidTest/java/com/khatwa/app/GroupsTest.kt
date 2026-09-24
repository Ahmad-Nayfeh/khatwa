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
 * Flow: user A creates an email account (keeping an old anonymous uid) and a group -> the invite
 * code appears; user B (a second email account in the same process) joins with the code, publishes
 * numbers, and the leaderboard ranks both. A signs in again and gets the group back, then the admin
 * key opens the admin panel, which removes a member and deletes the group.
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
        // First touch goes through the repository: it points Auth + Firestore at the emulator, so no
        // call below can ever reach the real project.
        runBlocking { runCatching { c.groups.ensureSignedIn() } }
        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
        auth.signOut()
        val run = System.currentTimeMillis()
        val emailA = "ahmad-$run@khatwa.test"
        val emailB = "sara-$run@khatwa.test"
        val password = "walk-123456"
        // User A has walked 4 200 steps today (fake sensor), so the board has real numbers.
        addSteps(4_200)
        // A phone that still has an old anonymous account: creating the email account keeps its uid.
        val anonUid = runBlocking { auth.signInAnonymously().awaitTask().user!!.uid }

        // --- user A through the UI: create the account, create a group, read the invite code.
        TestSupport.launchApp()
        assertNotNull(device.wait(Until.findObject(By.res("home_steps")), 15_000))
        assertTrue(TestSupport.clickRes("tab_groups"))
        val nick = device.wait(Until.findObject(By.res("groups_nickname")), 8_000)
        assertNotNull("account card missing", nick)
        nick!!.text = "أحمد"
        device.findObject(By.res("groups_email")).text = emailA
        device.findObject(By.res("groups_password")).text = password
        TestSupport.screenshot("50-groups-account")
        assertTrue(TestSupport.clickRes("groups_account_submit"))
        assertNotNull("enabled view missing", device.wait(Until.findObject(By.res("groups_create")), 20_000))
        assertEquals("the old anonymous account keeps its uid", anonUid, c.groups.uid)
        assertEquals(emailA, auth.currentUser?.email)
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

        // --- user B: a second email account in the same process joins with the code.
        runBlocking {
            c.groups.signUp(emailB, password, "سارة")
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
        val uidB = c.groups.uid!!

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
        val secondRow = TestSupport.findRes("member_row_1", 20_000)
        if (secondRow == null) TestSupport.dump("missing-member_row_1")
        assertNotNull("second leaderboard row missing", secondRow)
        TestSupport.screenshot("54-group-leaderboard")
        device.pressBack()
        // Public list: the group is visible with 2 members.
        assertTrue("all-groups tab missing", TestSupport.clickRes("groups_tab_all", 8_000))
        assertNotNull(device.wait(Until.findObject(By.textContains("مشاة الحي")), 15_000))
        Thread.sleep(800)
        TestSupport.screenshot("55-groups-public")

        // --- A signs in again (what happens after a reinstall or on a new phone): the group is back.
        runBlocking { c.groups.signOut(); c.settings.setGroupsEnabled(false); c.settings.setGroupsNickname("") }
        TestSupport.launchApp()
        assertTrue(TestSupport.clickRes("tab_groups"))
        assertTrue("switch to sign-in missing", TestSupport.clickRes("groups_account_switch", 8_000))
        device.wait(Until.findObject(By.res("groups_email")), 5_000)!!.text = emailA
        device.findObject(By.res("groups_password")).text = password
        assertTrue(TestSupport.clickRes("groups_account_submit"))
        assertNotNull("the group did not come back after signing in", device.wait(Until.findObject(By.res("group_card_$gid")), 20_000))
        assertEquals(uidA, c.groups.uid)
        assertEquals("nickname restored from the account", "أحمد", runBlocking { c.settings.current().groupsNickname })
        TestSupport.screenshot("56-groups-signed-in-again")

        // --- admin: the key (test key; the real one is never in the repository) opens the panel.
        repeat(3) { TestSupport.scrollForward("groups_scroll"); Thread.sleep(300) }
        assertTrue("admin key entry missing", TestSupport.clickRes("admin_key_open", 8_000))
        device.wait(Until.findObject(By.res("admin_key")), 5_000)!!.text = "khat-wate-stad-mink-ey22" // case and dashes do not matter
        assertTrue(TestSupport.clickRes("admin_key_confirm"))
        assertTrue("admin access not granted", runBlocking { kotlinx.coroutines.withTimeoutOrNull(15_000) { c.groups.observeIsAdmin().first { it } } } == true)
        repeat(3) { TestSupport.scrollBackward("groups_scroll"); Thread.sleep(300) }
        assertTrue("admin panel button missing", TestSupport.clickRes("admin_open", 15_000))
        assertNotNull("admin list misses the group", device.wait(Until.findObject(By.res("admin_group_$gid")), 15_000))
        TestSupport.screenshot("57-admin-groups")
        assertTrue(TestSupport.clickRes("admin_group_$gid"))
        assertNotNull(TestSupport.findRes("admin_member_$uidB", 15_000))
        TestSupport.screenshot("58-admin-group")
        // Remove B, then delete the whole group.
        assertTrue(TestSupport.clickRes("admin_remove_$uidB"))
        assertTrue(TestSupport.clickRes("admin_confirm"))
        assertTrue("member not removed", device.wait(Until.gone(By.res("admin_member_$uidB")), 15_000))
        assertTrue(TestSupport.clickRes("admin_delete_group"))
        assertTrue(TestSupport.clickRes("admin_confirm"))
        assertTrue("group not deleted", device.wait(Until.gone(By.res("admin_group_detail")), 15_000))
        assertTrue("group still listed", device.wait(Until.gone(By.res("admin_group_$gid")), 15_000))
        assertEquals(0, runBlocking { c.groups.groupIdsOf(uidB).size })
        TestSupport.evidence("admin removed a member and deleted the group")

        runBlocking { c.settings.setGroupsEnabled(false) }
        c.groups.signOut()
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

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        val e = t.exception
        if (e != null) cont.resumeWith(Result.failure(e)) else cont.resumeWith(Result.success(t.result))
    }
}
