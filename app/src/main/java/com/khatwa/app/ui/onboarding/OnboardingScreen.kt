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
            if (step > 0) SecondaryButton("السابق", Modifier.weight(1f)) { step-- }
            val last = step == STEPS - 1
            PrimaryButton(
                if (last) "ابدأ" else "التالي",
                Modifier.weight(2f).testTag("onboarding_next"),
                enabled = step != 1 || PermissionChecks.activityRecognition(context),
            ) {
                if (last) {
                    scope.launch {
                        val s = container.settings.current()
                        if (!s.allowlistInitialized) container.settings.setAllowlist(AllowlistDefaults.compute(context))
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
    Title("أهلاً بك في خطوة")
    Body("تطبيق يعدّ خطواتك كل يوم من حساس الجوال مباشرة، ويعرض إحصائيات هادئة وحكمة يومية، ويستطيع قفل الجوال حتى تمشي.")
    Body("كل شيء يعمل بلا إنترنت، ولا يغادر جوالك أي بيانات.")
    KCard(tone = CardTone.Soft) {
        Text("افتراض مهم", style = MaterialTheme.typography.titleMedium)
        VSpace(6.dp)
        Text("الحساس يعدّ حركة الجوال نفسه. الجوال يجب أن يكون معك أثناء المشي، وإلا لن تُحسب الخطوات.")
    }
    VSpace()
    Muted("الشاشات التالية تشرح كل صلاحية قبل طلبها. لا شيء يحتاج قراءة أي دليل خارجي.")
}

@Composable
private fun SensorStep() {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(PermissionChecks.activityRecognition(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    OnResume { granted = PermissionChecks.activityRecognition(context) }

    Title("صلاحية عدّ الخطوات")
    Body("يحتاج التطبيق صلاحية «النشاط البدني» ليقرأ عدّاد الخطوات المدمج في الجوال. هذا هو المصدر الوحيد للخطوات؛ لا Google Fit ولا أي خدمة أخرى.")
    if (!PermissionChecks.stepSensor(context)) {
        KCard(tone = CardTone.Warning) {
            Text("لم يُعثر على حساس خطوات في هذا الجهاز. على أجهزة المحاكاة هذا طبيعي؛ على جوال حقيقي يعني أن العدّ لن يعمل.")
        }
        VSpace()
    }
    if (granted) {
        KCard(tone = CardTone.Accent) { Text("الصلاحية ممنوحة ✓") }
    } else {
        PrimaryButton("منح الصلاحية", Modifier.fillMaxWidth().testTag("grant_activity")) {
            if (Build.VERSION.SDK_INT >= 29) launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) else granted = true
        }
        VSpace(8.dp)
        Muted("إن لم تظهر نافذة الطلب، افتح إعدادات التطبيق ثم الصلاحيات وفعّل «النشاط البدني».")
        VSpace(8.dp)
        SecondaryButton("فتح إعدادات التطبيق", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.appInfoIntent(context)) }
    }
}

@Composable
private fun NotificationsStep() {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(PermissionChecks.notifications(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    OnResume { granted = PermissionChecks.notifications(context) }

    Title("إشعار ثابت هادئ")
    Body("ليبقى العدّ يعمل في الخلفية يعرض التطبيق إشعاراً ثابتاً صامتاً فيه خطوات اليوم والهدف. لن يصدر أي صوت. الإشعارات الأخرى (الحكمة الصباحية، تذكير الوزن) مطفأة افتراضياً.")
    if (granted) {
        KCard(tone = CardTone.Accent) { Text("الإشعارات مفعّلة ✓") }
    } else if (Build.VERSION.SDK_INT >= 33) {
        PrimaryButton("السماح بالإشعارات", Modifier.fillMaxWidth()) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    } else {
        SecondaryButton("فتح إعدادات الإشعارات", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.notificationSettingsIntent(context)) }
    }
}

@Composable
private fun GoalsStep(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var temp by rememberSaveable { mutableStateOf("3000") }
    var final by rememberSaveable { mutableStateOf("8000") }
    var inc by rememberSaveable { mutableStateOf("500") }
    LaunchedEffect(Unit) {
        val s = container.settings.current()
        temp = s.tempGoal.toString(); final = s.finalGoal.toString(); inc = s.weeklyIncrement.toString()
    }
    fun save() {
        scope.launch { container.settings.setGoals(temp.toIntOrNull(), final.toIntOrNull(), inc.toIntOrNull(), null) }
    }
    Title("الهدف التدريجي")
    Body("أول 7 أيام أسبوع قياس بهدف مؤقت. بعده يرتفع الهدف تلقائياً كل أسبوع حتى يصل إلى هدفك النهائي. لا يهبط تلقائياً أبداً.")
    NumberField("الهدف المؤقت لأسبوع القياس", temp, { temp = it; save() })
    NumberField("الهدف النهائي", final, { final = it; save() })
    NumberField("الزيادة الأسبوعية", inc, { inc = it; save() })
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
    val context = LocalContext.current
    var a11y by rememberSaveable { mutableStateOf(PermissionChecks.accessibilityEnabled(context, KhatwaAccessibilityService::class.java)) }
    var overlay by rememberSaveable { mutableStateOf(PermissionChecks.overlay(context)) }
    OnResume {
        a11y = PermissionChecks.accessibilityEnabled(context, KhatwaAccessibilityService::class.java)
        overlay = PermissionChecks.overlay(context)
    }
    Title("قفل الجوال (اختياري الآن)")
    Body("ليعمل القفل يحتاج التطبيق صلاحيتين. يمكنك تفعيلهما لاحقاً من الإعدادات.")
    KCard {
        Text("1. خدمة الإتاحة", style = MaterialTheme.typography.titleMedium)
        VSpace(6.dp)
        Text("تُستخدم فقط لمعرفة اسم التطبيق المفتوح حالياً حتى تُعرض شاشة القفل فوقه. لا تقرأ محتوى الشاشة ولا ما تكتبه.")
        VSpace(8.dp)
        if (a11y) Text("مفعّلة ✓", color = MaterialTheme.colorScheme.primary)
        else {
            PrimaryButton("فتح إعدادات الإتاحة", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.accessibilityIntent()) }
            VSpace(8.dp)
            if (Build.VERSION.SDK_INT >= 33) {
                Muted(
                    "على أندرويد 13 فأعلى قد يظهر «الإعداد المقيّد» لأن التطبيق من خارج المتجر. الحل: افتح إعدادات التطبيق، اضغط النقاط الثلاث أعلى اليسار، اختر «السماح بالإعدادات المقيّدة»، ثم عد هنا وفعّل الخدمة."
                )
                VSpace(6.dp)
                SecondaryButton("فتح إعدادات التطبيق", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.appInfoIntent(context)) }
            }
        }
    }
    VSpace()
    KCard {
        Text("2. العرض فوق التطبيقات", style = MaterialTheme.typography.titleMedium)
        VSpace(6.dp)
        Text("لتظهر شاشة القفل فوق التطبيق غير المسموح.")
        VSpace(8.dp)
        if (overlay) Text("ممنوحة ✓", color = MaterialTheme.colorScheme.primary)
        else PrimaryButton("منح الصلاحية", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.overlayIntent(context)) }
    }
}

@Composable
private fun BatteryStep() {
    val context = LocalContext.current
    var ignored by rememberSaveable { mutableStateOf(PermissionChecks.batteryIgnored(context)) }
    OnResume { ignored = PermissionChecks.batteryIgnored(context) }
    Title("البطارية")
    Body("حتى لا يوقف النظام عدّ الخطوات في الخلفية، استثنِ التطبيق من تحسين البطارية. استهلاكه ضئيل لأن الحساس يعمل على معالج منفصل.")
    if (ignored) KCard(tone = CardTone.Accent) { Text("التطبيق مستثنى من تحسين البطارية ✓") }
    else PrimaryButton("استثناء من تحسين البطارية", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.batteryIntent(context)) }
    VSpace()
    if (PermissionChecks.isSamsung()) {
        KCard(tone = CardTone.Soft) {
            Text("على سامسونج أيضاً", style = MaterialTheme.typography.titleMedium)
            VSpace(6.dp)
            Text("الإعدادات ← العناية بالجهاز ← البطارية ← حدود استخدام الخلفية ← «التطبيقات غير الخاضعة للسكون» ← أضف «خطوة». وإن ظهرت رسالة عن Auto Blocker فأوقفه مؤقتاً من الإعدادات ← الأمان والخصوصية.")
        }
    }
}

@Composable
private fun DoneStep() {
    Title("جاهز")
    Body("سيبدأ العدّ الآن. الأيام السبعة الأولى أسبوع قياس. ستجد الهدف الفعلي والإحصائيات وقفل الجوال وقفل اللابتوب داخل التطبيق.")
    Muted("يمكنك مراجعة حالة كل الصلاحيات في أي وقت من الإعدادات ← حالة الصلاحيات.")
}
