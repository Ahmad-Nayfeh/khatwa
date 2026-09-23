package com.khatwa.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = KhatwaApp.container(this)
        setContent {
            val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
            KhatwaTheme(dark = settings?.darkMode ?: true) {
                Surface(
                    modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    settings?.let { KhatwaRoot(container, it) }
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
