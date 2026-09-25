package com.khatwa.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.khatwa.app.KhatwaApp
import com.khatwa.app.steps.StepService
import com.khatwa.app.ui.theme.KhatwaTheme
import com.khatwa.app.ui.theme.isDarkFor
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = KhatwaApp.container(this)
        setContent {
            val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
            val dark = isDarkFor(settings?.themeMode)
            // Status/navigation bar icons must follow the chosen theme, not only the system one.
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                )
            }
            KhatwaTheme(dark = dark, language = settings?.language) {
                Surface(
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    androidx.compose.foundation.layout.Box {
                        // The living landscape behind every screen; cards and bars stay solid on top of it.
                        com.khatwa.app.ui.theme.LivingBackground(dark)
                        settings?.let { KhatwaRoot(container, it) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The app is in the foreground here, so (re)starting the foreground service is always
        // allowed; this revives the counter if the system killed it.
        val container = KhatwaApp.container(this)
        lifecycleScope.launch {
            if (container.settings.current().onboardingDone) StepService.start(this@MainActivity)
        }
        container.lock.refreshHealth()
    }
}
