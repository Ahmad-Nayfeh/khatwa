package com.khatwa.app.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import kotlinx.coroutines.launch

/** Settings hub with in-place sub-screens (kept out of the bottom-bar NavHost on purpose). */
@Composable
fun SettingsNav(container: AppContainer) {
    var screen by rememberSaveable { mutableStateOf("root") }
    BackHandler(enabled = screen != "root") { screen = "root" }
    val back = { screen = "root" }
    when (screen) {
        "goal" -> GoalScreen(container, back)
        "schedule" -> ScheduledLockScreen(container, back)
        "allowlist" -> AllowlistScreen(container, back)
        "laptop" -> LaptopLockScreen(container, back)
        "weight" -> WeightScreen(container, back)
        "quotes" -> QuotesScreen(container, back)
        "backup" -> BackupScreen(container, back)
        "permissions" -> PermissionsScreen(container, back)
        else -> SettingsRoot(container) { screen = it }
    }
}

@Composable
private fun SettingsRoot(container: AppContainer, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val str = strings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings_scroll").padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(str.settings, style = MaterialTheme.typography.headlineMedium)
        VSpace()
        SettingsEntry(str.goal, str.goalSubtitle) { open("goal") }
        SettingsEntry(str.scheduledLock, str.scheduledLockSubtitle) { open("schedule") }
        SettingsEntry(str.allowlist, str.allowlistSubtitle) { open("allowlist") }
        SettingsEntry(str.laptopLock, str.laptopLockSubtitle) { open("laptop") }
        SettingsEntry(str.weightLog, str.weightLogSubtitle) { open("weight") }
        SettingsEntry(str.quotes, str.quotesSubtitle) { open("quotes") }
        SettingsEntry(str.backup, str.backupSubtitle) { open("backup") }
        SettingsEntry(str.permissionsStatus, str.permissionsSubtitle) { open("permissions") }
        VSpace()
        settings?.let { s ->
            KCard {
                SectionTitle(str.notificationsAndAppearance)
                SwitchRow(str.showQuoteOnHome, s.showQuote) { on ->
                    scope.launch { container.settings.setShowQuote(on) }
                }
                SwitchRow(str.morningNotification, s.morningEnabled) { on ->
                    scope.launch { container.settings.setMorning(on, s.morningMinute); container.alarms.scheduleAll(container.settings.current()) }
                }
                if (s.morningEnabled) TimePickerRow(str.morningNotificationTime, s.morningMinute) { m ->
                    scope.launch { container.settings.setMorning(true, m); container.alarms.scheduleAll(container.settings.current()) }
                }
                VSpace(8.dp)
                Text(str.appearance, style = MaterialTheme.typography.bodyLarge)
                VSpace(6.dp)
                ChoiceRow(
                    options = listOf(
                        com.khatwa.app.settings.ThemeMode.SYSTEM to str.themeSystem,
                        com.khatwa.app.settings.ThemeMode.LIGHT to str.themeLight,
                        com.khatwa.app.settings.ThemeMode.DARK to str.themeDark,
                    ),
                    selected = s.themeMode,
                ) { mode -> scope.launch { container.settings.setThemeMode(mode) } }
                VSpace(8.dp)
                Text(str.language, style = MaterialTheme.typography.bodyLarge)
                VSpace(6.dp)
                ChoiceRow(
                    options = listOf(
                        com.khatwa.app.settings.AppLanguage.AR to str.languageArabic,
                        com.khatwa.app.settings.AppLanguage.EN to str.languageEnglish,
                    ),
                    selected = s.language,
                    tag = "settings_language",
                ) { lang -> scope.launch { container.settings.setLanguage(lang) } }
            }
        }
        VSpace()
        Muted(str.footer)
        Box(Modifier.padding(bottom = 24.dp).testTag("settings_root"))
    }
}
