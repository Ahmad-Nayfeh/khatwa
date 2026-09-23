package com.khatwa.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.components.Muted

@Composable
fun SettingsNav(container: AppContainer) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineMedium)
        Muted("تُضاف في مرحلة لاحقة.")
    }
}
