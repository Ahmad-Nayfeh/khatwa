package com.khatwa.core.lock

/** Why an app is allowed through the lock screen. */
enum class AllowReason { ALWAYS_ALLOWED, USER_ALLOWED, SETTINGS_BLOCKED, BLOCKED }

/**
 * Package-level allowlist decision. The app layer resolves the concrete package names
 * (launcher, dialer, system UI, input methods, settings); this object only applies the rules:
 *  - Always allowed and not blockable: own package, system UI, launcher, dialer / in-call UI,
 *    emergency, input methods.
 *  - Settings is blocked while locked when the user keeps "block settings" on.
 *  - Everything else follows the user's allowlist.
 */
data class LockPolicy(
    val ownPackage: String,
    val alwaysAllowed: Set<String>,
    val settingsPackages: Set<String>,
    val userAllowed: Set<String>,
    val blockSettings: Boolean,
) {
    fun decide(packageName: String): AllowReason = when {
        packageName == ownPackage -> AllowReason.ALWAYS_ALLOWED
        packageName in alwaysAllowed -> AllowReason.ALWAYS_ALLOWED
        packageName in settingsPackages -> if (blockSettings) AllowReason.SETTINGS_BLOCKED else AllowReason.USER_ALLOWED
        packageName in userAllowed -> AllowReason.USER_ALLOWED
        else -> AllowReason.BLOCKED
    }

    fun isAllowed(packageName: String): Boolean = decide(packageName).let {
        it == AllowReason.ALWAYS_ALLOWED || it == AllowReason.USER_ALLOWED
    }
}

/** The active lock, if any. Pure data so the app can persist it as JSON. */
sealed class LockState {
    data object None : LockState()

    /** "Lock me until I walk [targetSteps] steps", counted from [stepsAtStart]. */
    data class Manual(val stepsAtStart: Long, val targetSteps: Int, val startedAtMs: Long) : LockState()

    /** Scheduled daily lock: active until today's steps reach [goal] or [endAtMs] passes. */
    data class Scheduled(val goal: Int, val startedAtMs: Long, val endAtMs: Long) : LockState()

    val isActive: Boolean get() = this !is None

    /** Steps still required, given today's total so far. */
    fun remaining(stepsToday: Long): Long = when (this) {
        None -> 0
        is Manual -> (targetSteps - (stepsToday - stepsAtStart)).coerceAtLeast(0)
        is Scheduled -> (goal - stepsToday).coerceAtLeast(0)
    }

    /** Total steps this lock asks for (for the progress ring). */
    fun target(): Long = when (this) {
        None -> 0
        is Manual -> targetSteps.toLong()
        is Scheduled -> goal.toLong()
    }

    fun isComplete(stepsToday: Long): Boolean = isActive && remaining(stepsToday) == 0L

    fun isExpired(nowMs: Long): Boolean = this is Scheduled && nowMs >= endAtMs
}

/** Scheduled-lock configuration. Times are minutes since local midnight; days are ISO 1..7 (Mon=1). */
data class ScheduleConfig(
    val enabled: Boolean = false,
    val startMinute: Int = 20 * 60,
    val endMinute: Int = 23 * 60,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val warnMinutesBefore: Int = 30,
) {
    fun appliesOn(isoDayOfWeek: Int): Boolean = enabled && isoDayOfWeek in days
}
