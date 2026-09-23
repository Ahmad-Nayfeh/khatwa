package com.khatwa.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.steps.Today
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.onboarding.OnResume
import com.khatwa.app.util.Fmt
import com.khatwa.core.lock.LockState
import kotlinx.coroutines.launch

/** Home-screen lock controls: health warning, manual lock buttons, active-lock card with emergency exit. */
@Composable
fun LockSection(container: AppContainer, today: Today, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock by container.lock.state.collectAsStateWithLifecycle()
    val health by container.lock.health.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }
    var emergency by remember { mutableStateOf(false) }
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    OnResume { container.lock.refreshHealth() }

    if (!health.ok) {
        KCard(tone = CardTone.Warning) {
            Text("القفل لا يعمل", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("lock_warning"))
            VSpace(6.dp)
            if (!health.accessibility) Text("خدمة الإتاحة غير مفعّلة.")
            if (!health.overlay) Text("صلاحية العرض فوق التطبيقات غير ممنوحة.")
            VSpace(8.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!health.accessibility) PrimaryButton("فتح إعدادات الإتاحة", Modifier.weight(1f)) { context.startActivity(PermissionChecks.accessibilityIntent()) }
                if (!health.overlay) PrimaryButton("منح العرض فوق التطبيقات", Modifier.weight(1f)) { context.startActivity(PermissionChecks.overlayIntent(context)) }
            }
            VSpace(6.dp)
            Muted("الخطوات الكاملة (بما فيها «الإعدادات المقيّدة» على أندرويد 13+) في الإعدادات ← حالة الصلاحيات.")
        }
        VSpace()
    }

    if (lock.isActive) {
        KCard(tone = CardTone.Accent) {
            Text("القفل نشط", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("lock_active"))
            VSpace(4.dp)
            Text("المتبقي ${Fmt.n(lock.remaining(today.steps))} خطوة", style = MaterialTheme.typography.headlineMedium)
            Muted(
                when (val l = lock) {
                    is LockState.Manual -> "يدوي: ${Fmt.n(l.targetSteps)} خطوة من لحظة التفعيل"
                    is LockState.Scheduled -> "مجدول حتى إكمال الهدف أو انتهاء الوقت"
                    else -> ""
                }
            )
            VSpace(8.dp)
            TextButton(onClick = { emergency = true }, modifier = Modifier.testTag("home_emergency")) { Text("طوارئ / إلغاء القفل") }
        }
        VSpace()
    } else {
        KCard {
            Text("اقفلني حتى أمشي", style = MaterialTheme.typography.titleMedium)
            VSpace(4.dp)
            Muted("يُقفل الجوال (عدا التطبيقات المسموحة) حتى تمشي العدد المحدد من لحظة الضغط.")
            VSpace(10.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1000, 2000, 3000).forEach { n ->
                    SecondaryButton(Fmt.n(n), Modifier.weight(1f).testTag("lock_start_$n"), enabled = health.ok) {
                        scope.launch { container.lock.startManual(n) }
                    }
                }
                SecondaryButton("مخصص", Modifier.weight(1f).testTag("lock_start_custom"), enabled = health.ok) { custom = true }
            }
        }
        VSpace()
    }

    if (custom) {
        var value by remember { mutableStateOf("1500") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("عدد الخطوات") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) value = v },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    value.toIntOrNull()?.takeIf { it in 100..100_000 }?.let { n -> scope.launch { container.lock.startManual(n) } }
                    custom = false
                }) { Text("اقفل") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("إلغاء") } },
        )
    }

    if (emergency) {
        AlertDialog(
            onDismissRequest = { emergency = false },
            confirmButton = {},
            text = {
                Column {
                    EmergencyFlow(
                        phrase = settings?.emergencyPhrase ?: com.khatwa.app.settings.Settings.DEFAULT_EMERGENCY_PHRASE,
                        remaining = lock.remaining(today.steps),
                        onCancel = { emergency = false },
                        onConfirm = { scope.launch { container.lock.surrender(); emergency = false } },
                    )
                }
            },
        )
    }
}
