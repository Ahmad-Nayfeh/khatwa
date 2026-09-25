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
import com.khatwa.app.i18n.strings
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
    val s = strings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lock by container.lock.state.collectAsStateWithLifecycle()
    val health by container.lock.health.collectAsStateWithLifecycle()
    var custom by remember { mutableStateOf(false) }
    var emergency by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var showA11yGuide by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val challenge by container.lock.challenge.collectAsStateWithLifecycle()
    val laptopSecret = settings?.laptopPairing
    OnResume { container.lock.refreshHealth() }

    if (!health.ok) {
        KCard(tone = CardTone.Warning) {
            Text(s.lockNotWorking, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("lock_warning"))
            VSpace(6.dp)
            if (!health.accessibility) Text(s.accessibilityOff)
            if (!health.overlay) Text(s.overlayOff)
            VSpace(8.dp)
            if (!health.accessibility) {
                // Android 13+ needs three steps for an app from outside the store: a guided list,
                // folded behind one button so today's steps stay on the first screen of Home.
                if (android.os.Build.VERSION.SDK_INT >= 33 && !showA11yGuide) {
                    PrimaryButton(s.a11yShowSteps, Modifier.fillMaxWidth().testTag("lock_warning_how")) { showA11yGuide = true }
                } else {
                    com.khatwa.app.ui.components.AccessibilityGuide(false)
                }
                VSpace(8.dp)
            }
            if (!health.overlay) PrimaryButton(s.grantOverlay, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.overlayIntent(context)) }
            VSpace(6.dp)
            Muted(s.fullStepsHint)
        }
        VSpace()
    }

    if (lock.isActive) {
        KCard(tone = CardTone.Accent) {
            Text(s.lockActive, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("lock_active"))
            VSpace(4.dp)
            Text(s.remainingSteps(Fmt.n(lock.remaining(today.steps))), style = MaterialTheme.typography.headlineMedium)
            Muted(
                when (val l = lock) {
                    is LockState.Manual -> s.manualLockShort(Fmt.n(l.targetSteps))
                    is LockState.Scheduled -> s.scheduledLockShort
                    else -> ""
                }
            )
            LaptopLockCodeRow(container, laptopSecret, challenge) { pairing = true }
            VSpace(8.dp)
            TextButton(onClick = { emergency = true }, modifier = Modifier.testTag("home_emergency")) { Text(s.emergencyTitle) }
        }
        VSpace()
    } else {
        LaptopUnlockCard(container, laptopSecret, challenge)
        KCard {
            Text(s.lockMeUntilIWalk, style = MaterialTheme.typography.titleMedium)
            VSpace(4.dp)
            Muted(s.lockMeHint)
            VSpace(10.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1000, 2000, 3000).forEach { n ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { scope.launch { container.lock.startManual(n) } },
                        enabled = health.ok,
                        modifier = Modifier.weight(1f).testTag("lock_start_$n"),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                    ) { Text(Fmt.n(n), maxLines = 1, softWrap = false) }
                }
            }
            VSpace(8.dp)
            SecondaryButton(s.customCount, Modifier.fillMaxWidth().testTag("lock_start_custom"), enabled = health.ok) { custom = true }
        }
        VSpace()
    }

    if (pairing) PairingDialog(container, laptopSecret) { pairing = false }

    if (custom) {
        var value by remember { mutableStateOf("1500") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(s.stepCount) },
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
                }) { Text(s.lock) }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(s.cancel) } },
        )
    }

    if (emergency) {
        AlertDialog(
            onDismissRequest = { emergency = false },
            confirmButton = {},
            text = {
                Column {
                    EmergencyFlow(
                        phrase = settings?.effectiveEmergencyPhrase ?: s.defaultEmergencyPhrase,
                        remaining = lock.remaining(today.steps),
                        onCancel = { emergency = false },
                        onConfirm = { scope.launch { container.lock.surrender(); emergency = false } },
                    )
                }
            },
        )
    }
}
