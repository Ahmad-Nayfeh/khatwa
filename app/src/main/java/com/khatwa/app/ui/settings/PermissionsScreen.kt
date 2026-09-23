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
    val context = LocalContext.current
    var tick by rememberSaveable { mutableIntStateOf(0) }
    OnResume { tick++; container.lock.refreshHealth() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    SubScreen(title = "حالة الصلاحيات", onBack = onBack) {
        key(tick) {
            StatusRow("حساس الخطوات", PermissionChecks.stepSensor(context), "الجهاز يملك حساس عدّ خطوات.", null, null)
            StatusRow("النشاط البدني", PermissionChecks.activityRecognition(context), "مطلوبة لقراءة الحساس.", "منح") {
                if (Build.VERSION.SDK_INT >= 29) launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            StatusRow("الإشعارات", PermissionChecks.notifications(context), "للإشعار الثابت وتنبيهات القفل.", "فتح") {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(PermissionChecks.notificationSettingsIntent(context))
            }
            StatusRow("خدمة الإتاحة", PermissionChecks.accessibilityEnabled(context, KhatwaAccessibilityService::class.java), "لمعرفة التطبيق المفتوح أثناء القفل.", "فتح") {
                context.startActivity(PermissionChecks.accessibilityIntent())
            }
            StatusRow("العرض فوق التطبيقات", PermissionChecks.overlay(context), "لعرض شاشة القفل.", "فتح") {
                context.startActivity(PermissionChecks.overlayIntent(context))
            }
            StatusRow("استبعاد البطارية", PermissionChecks.batteryIgnored(context), "حتى لا يوقف النظام العدّ.", "فتح") {
                context.startActivity(PermissionChecks.batteryIntent(context))
            }
            StatusRow("العدّاد يعمل", container.tracker.today.value.sensorSeen, "وصلت قراءة من الحساس منذ آخر تشغيل.", "تشغيل") {
                StepService.start(context); tick++
            }
        }
        VSpace()
        if (Build.VERSION.SDK_INT >= 33) {
            KCard(tone = CardTone.Soft) {
                SectionTitle("الإعدادات المقيّدة (أندرويد 13+)")
                Text("لأن التطبيق مثبّت من خارج المتجر، قد يرفض النظام تفعيل خدمة الإتاحة ويعرض «إعداد مقيّد». الحل:")
                VSpace(4.dp)
                Text("1. افتح إعدادات التطبيق (الزر أدناه).\n2. اضغط النقاط الثلاث أعلى الشاشة.\n3. اختر «السماح بالإعدادات المقيّدة» وأكّد.\n4. عد إلى الإتاحة وفعّل «خطوة».")
                VSpace(8.dp)
                SecondaryButton("فتح إعدادات التطبيق", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.appInfoIntent(context)) }
            }
            VSpace()
        }
        if (PermissionChecks.isSamsung()) {
            KCard(tone = CardTone.Soft) {
                SectionTitle("سامسونج")
                Text("• Auto Blocker: الإعدادات ← الأمان والخصوصية ← Auto Blocker ← أوقفه مؤقتاً أثناء التثبيت والتفعيل.\n• التطبيقات غير الخاضعة للسكون: الإعدادات ← العناية بالجهاز ← البطارية ← حدود استخدام الخلفية ← أضف «خطوة».")
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
