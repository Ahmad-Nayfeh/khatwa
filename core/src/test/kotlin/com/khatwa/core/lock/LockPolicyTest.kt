package com.khatwa.core.lock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockPolicyTest {
    private val policy = LockPolicy(
        ownPackage = "com.khatwa.app",
        alwaysAllowed = setOf("com.android.systemui", "com.android.launcher3", "com.google.android.dialer", "com.android.inputmethod.latin"),
        settingsPackages = setOf("com.android.settings"),
        userAllowed = setOf("com.google.android.youtube", "com.google.android.dialer", "com.android.contacts", "com.google.android.apps.messaging"),
        blockSettings = true,
    )

    @Test
    fun `own app and system packages are always allowed`() {
        assertEquals(AllowReason.ALWAYS_ALLOWED, policy.decide("com.khatwa.app"))
        assertEquals(AllowReason.ALWAYS_ALLOWED, policy.decide("com.android.systemui"))
        assertEquals(AllowReason.ALWAYS_ALLOWED, policy.decide("com.android.launcher3"))
    }

    @Test
    fun `user allowlist passes and unknown apps are blocked`() {
        assertEquals(AllowReason.USER_ALLOWED, policy.decide("com.google.android.youtube"))
        assertTrue(policy.isAllowed("com.google.android.youtube"))
        assertEquals(AllowReason.BLOCKED, policy.decide("com.instagram.android"))
        assertFalse(policy.isAllowed("com.instagram.android"))
    }

    @Test
    fun `settings follows the block-settings switch`() {
        assertEquals(AllowReason.SETTINGS_BLOCKED, policy.decide("com.android.settings"))
        assertFalse(policy.isAllowed("com.android.settings"))
        val relaxed = policy.copy(blockSettings = false)
        assertTrue(relaxed.isAllowed("com.android.settings"))
    }

    @Test
    fun `manual lock counts from the moment it started`() {
        val lock = LockState.Manual(stepsAtStart = 4200, targetSteps = 1000, startedAtMs = 0)
        assertEquals(1000, lock.remaining(4200))
        assertEquals(400, lock.remaining(4800))
        assertEquals(0, lock.remaining(5200))
        assertTrue(lock.isComplete(5300))
        assertFalse(lock.isComplete(5199))
    }

    @Test
    fun `scheduled lock counts today's total against the goal and expires`() {
        val lock = LockState.Scheduled(goal = 6000, startedAtMs = 1_000, endAtMs = 10_000)
        assertEquals(1500, lock.remaining(4500))
        assertTrue(lock.isComplete(6000))
        assertFalse(lock.isExpired(9_999))
        assertTrue(lock.isExpired(10_000))
    }

    @Test
    fun `no lock has nothing remaining`() {
        assertEquals(0, LockState.None.remaining(0))
        assertFalse(LockState.None.isActive)
        assertFalse(LockState.None.isComplete(10_000))
    }

    @Test
    fun `schedule applies only on enabled days`() {
        val s = ScheduleConfig(enabled = true, days = setOf(1, 2, 3))
        assertTrue(s.appliesOn(1))
        assertFalse(s.appliesOn(6))
        assertFalse(s.copy(enabled = false).appliesOn(1))
    }
}
