package com.khatwa.app.lock

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.lock.LockScreenContent
import com.khatwa.app.ui.theme.KhatwaTheme

/**
 * Full-screen lock window drawn over other apps through SYSTEM_ALERT_WINDOW
 * (TYPE_APPLICATION_OVERLAY). Hosted with the application context so it survives the
 * accessibility service being rebound. Main-thread only.
 */
class LockOverlay(private val c: AppContainer) {
    private val context: Context get() = c.app
    private val wm: WindowManager get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LockRootView? = null
    private var owner: OverlayOwner? = null

    val isShown: Boolean get() = root != null

    fun show() {
        if (root != null) return
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing; cannot show lock screen")
            return
        }
        val o = OverlayOwner().also { it.create() }
        val view = LockRootView(context)
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { OverlayContent() }
        }
        view.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        view.setViewTreeLifecycleOwner(o)
        view.setViewTreeSavedStateRegistryOwner(o)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "khatwa-lock"
        }
        try {
            wm.addView(view, params)
            root = view
            owner = o
            o.resume()
            Log.i(TAG, "overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            o.destroy()
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        try { wm.removeViewImmediate(v) } catch (e: Exception) { Log.w(TAG, "removeView failed: ${e.message}") }
        owner?.destroy()
        owner = null
        Log.i(TAG, "overlay hidden")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @androidx.compose.runtime.Composable
    private fun OverlayContent() {
        val settings by c.settings.flow.collectAsState(initial = null)
        KhatwaTheme(dark = com.khatwa.app.ui.theme.isDarkFor(settings?.themeMode)) {
            Surface(
                modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                color = MaterialTheme.colorScheme.background,
            ) {
                LockScreenContent(c, onOpenAllowed = { hide(); openLauncher() })
            }
        }
    }

    private fun openLauncher() {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Consumes Back so the lock screen cannot be dismissed with it. */
    private class LockRootView(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
            return super.dispatchKeyEvent(event)
        }
    }

    /** Minimal lifecycle + saved-state owner so ComposeView can live in a WindowManager window. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun create() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun resume() {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        private const val TAG = "LockOverlay"
    }
}

/** Utility for views that need a non-null token-free context check. */
internal fun View.isAttached(): Boolean = isAttachedToWindow
