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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineMedium)
        VSpace()
        SettingsEntry("الهدف", "الهدف الفعلي والنهائي والزيادة الأسبوعية والتعديل اليدوي") { open("goal") }
        SettingsEntry("القفل المجدول", "وقت البدء والانتهاء وأيام الأسبوع") { open("schedule") }
        SettingsEntry("قائمة المسموح", "التطبيقات المتاحة أثناء القفل، وحظر الإعدادات") { open("allowlist") }
        SettingsEntry("قفل اللابتوب", "السر المشترك وكود اليوم") { open("laptop") }
        SettingsEntry("سجل الوزن", "إدخال أسبوعي وتذكير اختياري") { open("weight") }
        SettingsEntry("الحكم", "تعديل وتصدير واستيراد") { open("quotes") }
        SettingsEntry("النسخة الاحتياطية", "تصدير واستيراد ومسح البيانات") { open("backup") }
        SettingsEntry("حالة الصلاحيات", "الحساس والإتاحة والعرض فوق التطبيقات والبطارية") { open("permissions") }
        VSpace()
        settings?.let { s ->
            KCard {
                SectionTitle("الإشعارات والمظهر")
                SwitchRow("إظهار حكمة اليوم في الصفحة الرئيسية", s.showQuote) { on ->
                    scope.launch { container.settings.setShowQuote(on) }
                }
                SwitchRow("إشعار صباحي بحكمة اليوم وخطوات الأمس", s.morningEnabled) { on ->
                    scope.launch { container.settings.setMorning(on, s.morningMinute); container.alarms.scheduleAll(container.settings.current()) }
                }
                if (s.morningEnabled) TimePickerRow("وقت الإشعار الصباحي", s.morningMinute) { m ->
                    scope.launch { container.settings.setMorning(true, m); container.alarms.scheduleAll(container.settings.current()) }
                }
                VSpace(8.dp)
                Text("المظهر", style = MaterialTheme.typography.bodyLarge)
                VSpace(6.dp)
                ChoiceRow(
                    options = listOf(
                        com.khatwa.app.settings.ThemeMode.SYSTEM to "تلقائي",
                        com.khatwa.app.settings.ThemeMode.LIGHT to "فاتح",
                        com.khatwa.app.settings.ThemeMode.DARK to "داكن",
                    ),
                    selected = s.themeMode,
                ) { mode -> scope.launch { container.settings.setThemeMode(mode) } }
            }
        }
        VSpace()
        Muted("خطوة · مفتوح المصدر برخصة MIT · يعمل بلا إنترنت ولا يرسل أي بيانات.")
        Box(Modifier.padding(bottom = 24.dp).testTag("settings_root"))
    }
}
