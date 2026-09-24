package com.khatwa.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.strings
import com.khatwa.app.lock.KhatwaAccessibilityService
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.steps.StepService
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.onboarding.OnResume

@Composable
fun PermissionsScreen(container: AppContainer, onBack: () -> Unit) {
    val s = strings
    val context = LocalContext.current
    var tick by rememberSaveable { mutableIntStateOf(0) }
    OnResume { tick++; container.lock.refreshHealth() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    SubScreen(title = s.permissionsStatus, onBack = onBack) {
        key(tick) {
            StatusRow(s.stepSensor, PermissionChecks.stepSensor(context), s.stepSensorText, null, null)
            StatusRow(s.physicalActivity, PermissionChecks.activityRecognition(context), s.physicalActivityText, s.grant) {
                if (Build.VERSION.SDK_INT >= 29) launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            StatusRow(s.notifications, PermissionChecks.notifications(context), s.notificationsText, s.open) {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(PermissionChecks.notificationSettingsIntent(context))
            }
            StatusRow(s.accessibilityService, PermissionChecks.accessibilityEnabled(context, KhatwaAccessibilityService::class.java), s.accessibilityServiceShort, s.open) {
                context.startActivity(PermissionChecks.accessibilityIntent())
            }
            StatusRow(s.overlay, PermissionChecks.overlay(context), s.overlayShort, s.open) {
                context.startActivity(PermissionChecks.overlayIntent(context))
            }
            StatusRow(s.batteryExemption, PermissionChecks.batteryIgnored(context), s.batteryExemptionText, s.open) {
                context.startActivity(PermissionChecks.batteryIntent(context))
            }
            StatusRow(s.counterRuns, container.tracker.today.value.sensorSeen, s.counterRunsText, s.run) {
                StepService.start(context); tick++
            }
        }
        VSpace()
        if (Build.VERSION.SDK_INT >= 33) {
            KCard(tone = CardTone.Soft) {
                SectionTitle(s.restrictedSettings)
                Text(s.restrictedSettingsText)
                VSpace(4.dp)
                Text(s.restrictedSettingsSteps)
                VSpace(8.dp)
                SecondaryButton(s.openAppSettings, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.appInfoIntent(context)) }
            }
            VSpace()
        }
        if (PermissionChecks.isSamsung()) {
            KCard(tone = CardTone.Soft) {
                SectionTitle(s.samsung)
                Text(s.samsungText)
            }
        }
    }
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

@Composable
private fun StatusRow(title: String, ok: Boolean, subtitle: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text((if (ok) "✓ " else "✗ ") + title, style = MaterialTheme.typography.titleMedium, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Muted(subtitle)
        }
        if (!ok && action != null && onAction != null) SecondaryButton(action, onClick = onAction)
    }
}
