package com.khatwa.app.debug

import android.content.Context
import android.util.Log
import com.khatwa.app.steps.StepListener
import com.khatwa.app.steps.StepSource
import com.khatwa.app.steps.StepSourceFactory

/**
 * Debug build only. The fake step source replaces the hardware sensor when:
 *  - the device has no TYPE_STEP_COUNTER (the emulator), or
 *  - it was enabled explicitly (tests / `adb shell am broadcast ... ENABLE_FAKE`).
 */
object DebugHooks {
    const val ENABLED = true

    fun fakeStepSource(context: Context): StepSource? =
        if (FakeStepSource.shouldUse(context)) FakeStepSource.get(context) else null

    fun onAppCreate(context: Context) {
        Log.i("DebugHooks", "debug build; fake source active=${FakeStepSource.shouldUse(context)}")
    }
}

/**
 * A cumulative counter that behaves like the hardware step counter: it only ever grows,
 * except on a simulated reboot when it restarts from a small number.
 */
class FakeStepSource private constructor(context: Context) : StepSource {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var listener: StepListener? = null

    override val name: String = "fake"
    override val available: Boolean = true
    val isStarted: Boolean get() = listener != null

    var reading: Long
        get() = prefs.getLong(KEY_READING, 0L)
        private set(v) { prefs.edit().putLong(KEY_READING, v).apply() }

    override fun start(listener: StepListener): Boolean {
        this.listener = listener
        // Like the real sensor: report the current value immediately on registration.
        listener.onReading(reading, System.currentTimeMillis())
        return true
    }

    override fun stop() { listener = null }

    /** Adds [steps] as [events] separate events spread over the last [spreadMs] milliseconds. */
    fun add(steps: Long, events: Int = 1, spreadMs: Long = 0) {
        if (steps <= 0) return
        val now = System.currentTimeMillis()
        val n = events.coerceAtLeast(1)
        val per = steps / n
        var rem = steps - per * n
        var r = reading
        for (i in 0 until n) {
            val chunk = per + if (rem > 0) { rem--; 1 } else 0
            r += chunk
            val t = if (n == 1) now else now - spreadMs + (spreadMs * (i + 1)) / n
            listener?.onReading(r, t)
        }
        reading = r
        Log.i(TAG, "added $steps steps -> reading $r")
    }

    /** Emits one event at an explicit timestamp (for session tests). */
    fun emitAt(timestampMs: Long, steps: Long) {
        val r = reading + steps
        reading = r
        listener?.onReading(r, timestampMs)
    }

    fun reboot() {
        reading = 3
        listener?.onReading(3, System.currentTimeMillis())
        Log.i(TAG, "simulated reboot")
    }

    companion object {
        private const val TAG = "FakeStepSource"
        private const val PREFS = "khatwa_debug"
        private const val KEY_READING = "fake_reading"
        private const val KEY_ENABLED = "fake_enabled"

        @Volatile private var instance: FakeStepSource? = null

        fun get(context: Context): FakeStepSource =
            instance ?: synchronized(this) { instance ?: FakeStepSource(context.applicationContext).also { instance = it } }

        fun shouldUse(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ENABLED, false) || !StepSourceFactory.hasHardwareSensor(context)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }
}
