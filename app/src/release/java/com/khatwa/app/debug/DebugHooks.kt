package com.khatwa.app.debug

import android.content.Context
import com.khatwa.app.steps.StepSource

/**
 * Release build: no fake step source, no debug receiver, nothing. The debug variant of this
 * file (src/debug) provides the test-only hooks. CI verifies the release APK contains none.
 */
object DebugHooks {
    const val ENABLED = false
    fun fakeStepSource(context: Context): StepSource? = null
    fun onAppCreate(context: Context) = Unit
}
