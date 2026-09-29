package com.khatwa.app.debug

import android.content.Context
import android.util.Log
import com.khatwa.app.walks.LocationSource
import com.khatwa.core.geo.LatLon
import com.khatwa.core.walk.Fix

/**
 * Debug build only: GPS readings driven by tests (the emulator has no real walk). Active when
 * enabled explicitly; readings are pushed with [emit], the current position set with [place].
 */
class FakeLocationSource private constructor() : LocationSource {
    private var onFix: ((Fix) -> Unit)? = null
    @Volatile private var last: Fix? = null

    val isStarted: Boolean get() = onFix != null

    override fun start(onFix: (Fix) -> Unit): Boolean {
        this.onFix = onFix
        return true
    }

    override fun stop() { onFix = null }
    override fun lastKnown(): Fix? = last
    override suspend fun current(timeoutMs: Long): Fix? = last

    /** Sets where the phone is (no reading is delivered to a running walk). */
    fun place(lat: Double, lon: Double) {
        last = Fix(LatLon(lat, lon), 8f, System.currentTimeMillis())
    }

    /** Delivers one reading, as the GPS would during a walk. */
    fun emit(p: LatLon, accuracyM: Float = 8f, timeMs: Long = System.currentTimeMillis()) {
        val f = Fix(p, accuracyM, timeMs)
        last = f
        onFix?.invoke(f)
        Log.i("FakeLocationSource", "fix $p")
    }

    companion object {
        private const val PREFS = "khatwa_debug"
        private const val KEY_ENABLED = "fake_location"
        val instance = FakeLocationSource()

        fun shouldUse(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).commit()
        }
    }
}
