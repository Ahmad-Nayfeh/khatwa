package com.khatwa.app.walks

import android.util.Log
import com.khatwa.app.AppContainer
import com.khatwa.app.data.WalkEntity
import com.khatwa.core.geo.Geo
import com.khatwa.core.geo.LatLon
import com.khatwa.core.walk.Fix
import com.khatwa.core.walk.Stride
import com.khatwa.core.walk.TrackBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** The walk being recorded right now. */
data class ActiveWalk(
    val startMs: Long,
    val stepsAtStart: Long,
    val challengeKey: String?,
    val placeName: String?,
    /** The challenge's destination, drawn on the live map. */
    val target: LatLon?,
    val distanceM: Double = 0.0,
    val points: List<LatLon> = emptyList(),
    /** False until the first usable GPS reading arrives. */
    val hasFix: Boolean = false,
)

/**
 * Records walks: GPS readings from [LocationSource] go through [TrackBuilder] (accuracy, jitter,
 * car-speed jumps). [WalkService] keeps the app alive while recording. On stop the walk is saved
 * (route simplified to ~3 m), the step length is refined from it, and the daily challenge is checked.
 */
class WalkRecorder(private val c: AppContainer) {
    val location: LocationSource by lazy { LocationSource.create(c.app) }
    private val _active = MutableStateFlow<ActiveWalk?>(null)
    val active: StateFlow<ActiveWalk?> = _active.asStateFlow()
    private var track: TrackBuilder? = null

    /** Starts a walk (from the UI, with location permission). Returns false if one is running. */
    fun start(challengeKey: String?, placeName: String?, target: LatLon?): Boolean {
        if (_active.value != null) return false
        track = TrackBuilder()
        _active.value = ActiveWalk(System.currentTimeMillis(), c.tracker.today.value.steps, challengeKey, placeName, target)
        WalkService.start(c.app)
        return true
    }

    /** Called by [WalkService] once it runs in the foreground. */
    internal fun beginRecording(): Boolean {
        if (_active.value == null) return false
        val ok = location.start { onFix(it) }
        Log.i(TAG, "recording started (location=$ok)")
        return ok
    }

    private fun onFix(f: Fix) {
        val t = track ?: return
        val cur = _active.value ?: return
        if (t.add(f)) {
            _active.value = cur.copy(distanceM = t.distanceM, points = t.points.toList(), hasFix = true)
            WalkService.update(c.app, _active.value!!)
        }
    }

    /** Stops and saves the walk (a walk without any movement is dropped). Returns the saved id. */
    suspend fun stop(): Long? {
        val w = _active.value ?: return null
        location.stop()
        _active.value = null
        WalkService.stop(c.app)
        val t = track
        track = null
        if (t == null || t.points.size < 2 || t.distanceM < 20) {
            Log.i(TAG, "walk dropped (too short)")
            return null
        }
        val end = System.currentTimeMillis()
        val steps = (c.tracker.today.value.steps - w.stepsAtStart).coerceAtLeast(0)
        val route = Geo.simplify(t.points, 3.0)
        val completed = c.challenges.onWalkFinished(w.challengeKey, t.points, t.distanceM)
        val id = c.db.walks().insert(
            WalkEntity(
                date = LocalDate.now(ZoneId.systemDefault()).toString(),
                // Walking time from the GPS readings (not the wait for the first fix).
                startMs = t.firstTimeMs ?: w.startMs, endMs = (t.lastTimeMs ?: end).coerceAtLeast(t.firstTimeMs ?: w.startMs),
                distanceM = t.distanceM, steps = steps,
                polyline = Geo.encode(route), challengeKey = w.challengeKey, placeName = w.placeName, completed = completed,
            ),
        )
        // A GPS walk measures the real step length; the estimate moves toward it.
        val s = c.settings.current()
        val stride = Stride.calibrate(s.strideM, t.distanceM, steps)
        if (stride != s.strideM) c.settings.setCalibratedStride(stride)
        Log.i(TAG, "walk $id saved: ${t.distanceM.toInt()} m, $steps steps, completed=$completed")
        return id
    }

    /** From the notification's "stop" button. */
    fun stopAsync() {
        c.scope.launch { stop() }
    }

    companion object {
        private const val TAG = "WalkRecorder"
    }
}
