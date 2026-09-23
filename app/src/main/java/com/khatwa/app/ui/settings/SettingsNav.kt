package com.khatwa.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.VSpace

/** Settings hub with in-place sub-screens (kept out of the bottom-bar NavHost on purpose). */
@Composable
fun SettingsNav(container: AppContainer) {
    var screen by rememberSaveable { mutableStateOf("root") }
    BackHandler(enabled = screen != "root") { screen = "root" }
    val back = { screen = "root" }
    when (screen) {
        "weight" -> WeightScreen(container, back)
        else -> SettingsRoot(container) { screen = it }
    }
}

@Composable
private fun SettingsRoot(container: AppContainer, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineMedium)
        VSpace()
        SettingsEntry("سجل الوزن", "إدخال أسبوعي وتذكير اختياري") { open("weight") }
        Muted("بقية الإعدادات تُضاف في المراحل التالية.", Modifier.padding(top = 12.dp))
        Box(Modifier.padding(bottom = 24.dp).testTag("settings_root"))
    }
}
