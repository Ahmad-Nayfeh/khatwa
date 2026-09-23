package com.khatwa.app.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.khatwa.app.debug.DebugHooks
import kotlin.math.abs

/** Receives cumulative step-counter readings with the wall-clock time of the event. */
fun interface StepListener {
    fun onReading(reading: Long, eventTimeMs: Long)
}

/** Abstraction over Sensor.TYPE_STEP_COUNTER so the debug build can substitute a fake source. */
interface StepSource {
    val name: String
    val available: Boolean
    fun start(listener: StepListener): Boolean
    fun stop()
}

object StepSourceFactory {
    /** The debug build may return a fake source; the release build always uses the sensor. */
    fun create(context: Context): StepSource =
        DebugHooks.fakeStepSource(context) ?: SensorStepSource(context)

    fun hasHardwareSensor(context: Context): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
    }
}

class SensorStepSource(context: Context) : StepSource {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var sensorListener: SensorEventListener? = null

    override val name: String = "hardware"
    override val available: Boolean get() = sensor != null

    override fun start(listener: StepListener): Boolean {
        val s = sensor ?: return false
        stop()
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val reading = event.values[0].toLong()
                listener.onReading(reading, eventTimeToEpochMs(event.timestamp))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListener = l
        // Latency 0: deliver every step immediately (the lock screen needs a near-live count).
        val ok = sensorManager.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, 0)
        if (!ok) Log.w(TAG, "registerListener failed")
        return ok
    }

    override fun stop() {
        sensorListener?.let { sensorManager.unregisterListener(it) }
        sensorListener = null
    }

    companion object {
        private const val TAG = "SensorStepSource"

        /**
         * Sensor timestamps are nanoseconds on the elapsedRealtime clock. Convert to wall clock;
         * if the result is absurd (some devices use a different clock) fall back to "now".
         */
        fun eventTimeToEpochMs(timestampNs: Long): Long {
            val now = System.currentTimeMillis()
            val ageMs = (SystemClock.elapsedRealtimeNanos() - timestampNs) / 1_000_000
            val candidate = now - ageMs
            return if (abs(now - candidate) > 6 * 60 * 60 * 1000L) now else candidate
        }
    }
}
