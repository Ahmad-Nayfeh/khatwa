package com.khatwa.core.sessions

/** A detected walking session: continuous walking of at least [SessionConfig.minDurationMs]. */
data class WalkSession(val startMs: Long, val endMs: Long, val steps: Long) {
    val durationMs: Long get() = endMs - startMs
}

data class SessionConfig(
    /** Steps within a trailing window of [windowMs] needed to count as "walking now" (cadence). */
    val minStepsPerWindow: Int = 60,
    val windowMs: Long = 60_000,
    /** The cadence must be sustained for at least this long before it counts as walking. */
    val minPaceSpanMs: Long = 10_000,
    /** Pauses up to this long do not end a session. */
    val maxPauseMs: Long = 2 * 60_000,
    /** A session must last at least this long (first walking event to last walking event). */
    val minDurationMs: Long = 10 * 60_000,
)

/**
 * Streaming session detector fed with raw sensor events (timestamp + step delta).
 *
 * "Walking now" = a cadence of at least [SessionConfig.minStepsPerWindow] per
 * [SessionConfig.windowMs] over the trailing window, sustained for [SessionConfig.minPaceSpanMs].
 * A candidate session starts at the first event of that window and ends
 * at the last walking event; a gap longer than [SessionConfig.maxPauseMs] after that event
 * closes it. It is reported only if its duration is at least [SessionConfig.minDurationMs].
 *
 * Deterministic and Android-free so it is tested with synthetic data.
 */
class SessionDetector(private val config: SessionConfig = SessionConfig()) {

    private data class Ev(val t: Long, val steps: Long)

    private val window = ArrayDeque<Ev>()
    private var windowSteps = 0L

    private var candidateStart: Long = -1
    private var lastWalkingT: Long = -1
    private var stepsSinceStart = 0L
    private var stepsAtLastWalking = 0L

    val hasCandidate: Boolean get() = candidateStart >= 0

    /** Feed one sensor event. Returns a completed session if this event closed one. */
    fun onSteps(timestampMs: Long, deltaSteps: Long): WalkSession? {
        if (deltaSteps < 0) return null
        var finished: WalkSession? = null

        // A long silence closes any candidate before the new event is considered.
        if (hasCandidate && timestampMs - lastWalkingT > config.maxPauseMs) {
            finished = closeCandidate()
            window.clear(); windowSteps = 0
        }

        window.addLast(Ev(timestampMs, deltaSteps))
        windowSteps += deltaSteps
        while (window.isNotEmpty() && timestampMs - window.first().t > config.windowMs) {
            windowSteps -= window.removeFirst().steps
        }

        if (hasCandidate) stepsSinceStart += deltaSteps

        // Walking = the cadence over the trailing window (which may be shorter than windowMs
        // right after a pause) is at least minStepsPerWindow per windowMs, sustained for at
        // least minPaceSpanMs. Scaling by the span keeps the pause tolerance honest: a walk
        // that resumes after 90 s is recognised within seconds, not after a full minute.
        val span = timestampMs - window.first().t
        val walking = span >= config.minPaceSpanMs &&
            windowSteps * config.windowMs >= config.minStepsPerWindow.toLong() * span
        if (walking) {
            if (!hasCandidate) {
                candidateStart = window.first().t
                stepsSinceStart = windowSteps
            }
            lastWalkingT = timestampMs
            stepsAtLastWalking = stepsSinceStart
        }
        return finished
    }

    /** Call when time passes without events (e.g. periodic tick). Closes a stale candidate. */
    fun flush(nowMs: Long): WalkSession? {
        if (!hasCandidate) return null
        if (nowMs - lastWalkingT <= config.maxPauseMs) return null
        val s = closeCandidate()
        window.clear(); windowSteps = 0
        return s
    }

    /** Force-close (e.g. day rollover). */
    fun reset(): WalkSession? {
        val s = if (hasCandidate) closeCandidate() else null
        window.clear(); windowSteps = 0
        return s
    }

    private fun closeCandidate(): WalkSession? {
        val start = candidateStart
        val end = lastWalkingT
        val steps = stepsAtLastWalking
        candidateStart = -1; lastWalkingT = -1; stepsSinceStart = 0; stepsAtLastWalking = 0
        return if (end - start >= config.minDurationMs) WalkSession(start, end, steps) else null
    }
}
