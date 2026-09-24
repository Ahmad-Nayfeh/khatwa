package com.khatwa.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.strings
import com.khatwa.app.lock.KhatwaAccessibilityService
import com.khatwa.app.lock.AllowlistDefaults
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.steps.SnapshotWorker
import com.khatwa.app.steps.StepService
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.VSpace
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val STEPS = 7

/** Re-runs [onResume] every time the activity comes back (after a system settings screen). */
@Composable
fun OnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(obs)
    }
}

@Composable
fun OnboardingScreen(container: AppContainer) {
    val s = strings
    var step by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        LinearProgressIndicator(progress = { (step + 1f) / STEPS }, modifier = Modifier.fillMaxWidth())
        VSpace(20.dp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (step) {
                0 -> WelcomeStep()
                1 -> SensorStep()
                2 -> NotificationsStep()
                3 -> GoalsStep(container)
                4 -> LockPermissionsStep()
                5 -> BatteryStep()
                else -> DoneStep()
            }
        }
        VSpace(16.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) SecondaryButton(s.previous, Modifier.weight(1f)) { step-- }
            val last = step == STEPS - 1
            PrimaryButton(
                if (last) s.start else s.next,
                Modifier.weight(2f).testTag("onboarding_next"),
                enabled = step != 1 || PermissionChecks.activityRecognition(context),
            ) {
                if (last) {
                    scope.launch {
                        val current = container.settings.current()
                        if (!current.allowlistInitialized) container.settings.setAllowlist(AllowlistDefaults.compute(context))
                        container.settings.setOnboardingDone(LocalDate.now())
                        container.tracker.load()
                        StepService.start(context)
                        container.alarms.scheduleAll(container.settings.current())
                        SnapshotWorker.schedule(context)
                    }
                } else step++
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
    VSpace(12.dp)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
    VSpace(10.dp)
}

@Composable
private fun WelcomeStep() {
    val s = strings
    Title(s.welcomeTitle)
    Body(s.welcomeText1)
    Body(s.welcomeText2)
    KCard(tone = CardTone.Soft) {
        Text(s.importantAssumption, style = MaterialTheme.typography.titleMedium)
        VSpace(6.dp)
        Text(s.assumptionText)
    }
    VSpace()
    Muted(s.screensExplain)
}

@Composable
private fun SensorStep() {
    val s = strings
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(PermissionChecks.activityRecognition(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    OnResume { granted = PermissionChecks.activityRecognition(context) }

    Title(s.stepPermissionTitle)
    Body(s.stepPermissionText)
    if (!PermissionChecks.stepSensor(context)) {
        KCard(tone = CardTone.Warning) {
            Text(s.noSensorLong)
        }
        VSpace()
    }
    if (granted) {
        KCard(tone = CardTone.Accent) { Text(s.permissionGrantedCheck) }
    } else {
        PrimaryButton(s.grantPermission, Modifier.fillMaxWidth().testTag("grant_activity")) {
            if (Build.VERSION.SDK_INT >= 29) launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) else granted = true
        }
        VSpace(8.dp)
        Muted(s.permissionFallbackHint)
        VSpace(8.dp)
        SecondaryButton(s.openAppSettings, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.appInfoIntent(context)) }
    }
}

@Composable
private fun NotificationsStep() {
    val s = strings
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(PermissionChecks.notifications(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    OnResume { granted = PermissionChecks.notifications(context) }

    Title(s.quietNotificationTitle)
    Body(s.quietNotificationText)
    if (granted) {
        KCard(tone = CardTone.Accent) { Text(s.notificationsOnCheck) }
    } else if (Build.VERSION.SDK_INT >= 33) {
        PrimaryButton(s.allowNotifications, Modifier.fillMaxWidth()) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    } else {
        SecondaryButton(s.openNotificationSettings, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.notificationSettingsIntent(context)) }
    }
}

@Composable
private fun GoalsStep(container: AppContainer) {
    val s = strings
    val scope = rememberCoroutineScope()
    var temp by rememberSaveable { mutableStateOf("3000") }
    var final by rememberSaveable { mutableStateOf("8000") }
    var inc by rememberSaveable { mutableStateOf("500") }
    LaunchedEffect(Unit) {
        val current = container.settings.current()
        temp = current.tempGoal.toString(); final = current.finalGoal.toString(); inc = current.weeklyIncrement.toString()
    }
    fun save() {
        scope.launch { container.settings.setGoals(temp.toIntOrNull(), final.toIntOrNull(), inc.toIntOrNull(), null) }
    }
    Title(s.progressiveGoalTitle)
    Body(s.progressiveGoalText)
    NumberField(s.tempGoalLabel, temp, { temp = it; save() })
    NumberField(s.finalGoal, final, { final = it; save() })
    NumberField(s.weeklyIncrement, inc, { inc = it; save() })
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
private fun LockPermissionsStep() {
    val s = strings
    val context = LocalContext.current
    var a11y by rememberSaveable { mutableStateOf(PermissionChecks.accessibilityEnabled(context, KhatwaAccessibilityService::class.java)) }
    var overlay by rememberSaveable { mutableStateOf(PermissionChecks.overlay(context)) }
    OnResume {
        a11y = PermissionChecks.accessibilityEnabled(context, KhatwaAccessibilityService::class.java)
        overlay = PermissionChecks.overlay(context)
    }
    Title(s.phoneLockOptional)
    Body(s.phoneLockNeedsTwo)
    KCard {
        Text(s.accessibilityServiceNumbered, style = MaterialTheme.typography.titleMedium)
        VSpace(6.dp)
        Text(s.accessibilityServiceText)
        VSpace(8.dp)
        if (a11y) Text(s.enabledCheck, color = MaterialTheme.colorScheme.primary)
        else {
            PrimaryButton(s.openAccessibilitySettings, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.accessibilityIntent()) }
            VSpace(8.dp)
            if (Build.VERSION.SDK_INT >= 33) {
                Muted(s.restrictedSettingsHint)
                VSpace(6.dp)
                SecondaryButton(s.openAppSettings, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.appInfoIntent(context)) }
            }
        }
    }
    VSpace()
    KCard {
        Text(s.overlayNumbered, style = MaterialTheme.typography.titleMedium)
        VSpace(6.dp)
        Text(s.overlayText)
        VSpace(8.dp)
        if (overlay) Text(s.grantedCheck, color = MaterialTheme.colorScheme.primary)
        else PrimaryButton(s.grantPermission, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.overlayIntent(context)) }
    }
}

@Composable
private fun BatteryStep() {
    val s = strings
    val context = LocalContext.current
    var ignored by rememberSaveable { mutableStateOf(PermissionChecks.batteryIgnored(context)) }
    OnResume { ignored = PermissionChecks.batteryIgnored(context) }
    Title(s.battery)
    Body(s.batteryText)
    if (ignored) KCard(tone = CardTone.Accent) { Text(s.batteryExemptCheck) }
    else PrimaryButton(s.batteryExempt, Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.batteryIntent(context)) }
    VSpace()
    if (PermissionChecks.isSamsung()) {
        KCard(tone = CardTone.Soft) {
            Text(s.samsungToo, style = MaterialTheme.typography.titleMedium)
            VSpace(6.dp)
            Text(s.samsungBatteryText)
        }
    }
}

@Composable
private fun DoneStep() {
    val s = strings
    Title(s.ready)
    Body(s.readyText)
    Muted(s.readyHint)
}
