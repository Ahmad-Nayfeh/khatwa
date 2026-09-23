package com.khatwa.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.steps.StepService
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.ProgressRing
import com.khatwa.app.ui.components.StatPill
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt

@Composable
fun HomeScreen(container: AppContainer, onOpenSettings: () -> Unit) {
    val today by container.tracker.today.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val remaining = (today.goal - today.steps).coerceAtLeast(0)
    val progress = if (today.goal > 0) today.steps.toFloat() / today.goal else 0f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!PermissionChecks.activityRecognition(context)) {
            KCard(tone = CardTone.Warning) {
                Text("العدّ متوقف: صلاحية النشاط البدني غير ممنوحة", style = MaterialTheme.typography.titleMedium)
                VSpace(8.dp)
                PrimaryButton("فتح إعدادات التطبيق", Modifier.fillMaxWidth()) { context.startActivity(PermissionChecks.appInfoIntent(context)) }
            }
            VSpace()
        } else if (!PermissionChecks.stepSensor(context) && !com.khatwa.app.debug.DebugHooks.ENABLED) {
            KCard(tone = CardTone.Warning) { Text("لا يوجد حساس خطوات في هذا الجهاز") }
            VSpace()
        }

        VSpace(8.dp)
        ProgressRing(progress = progress) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.n(today.steps), style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag("home_steps"))
                Muted("من ${Fmt.n(today.goal)} خطوة")
            }
        }
        VSpace(12.dp)
        Text(
            if (remaining == 0L) "أكملت هدف اليوم" else "المتبقي ${Fmt.n(remaining)} خطوة",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("home_remaining"),
        )
        VSpace(20.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val gi = today.goalInfo
            StatPill("الهدف الفعلي", Fmt.n(today.goal), Modifier.weight(1f))
            StatPill(
                if (gi?.isManual == true) "هدف يدوي" else "الأسبوع",
                if (gi?.isManual == true) "ثابت" else if (gi?.isMeasurementWeek == true) "قياس" else Fmt.n((gi?.weekIndex ?: 0) + 1),
                Modifier.weight(1f),
            )
        }
        VSpace(20.dp)
        KCard(tone = CardTone.Soft) {
            Text("العدّاد يعمل في الخلفية", style = MaterialTheme.typography.titleMedium)
            VSpace(6.dp)
            Muted(if (today.sensorSeen) "آخر قراءة من الحساس وصلت." else "بانتظار أول قراءة من الحساس. إن لم تصل، افتح الإعدادات ← حالة الصلاحيات.")
            VSpace(8.dp)
            PrimaryButton("إعادة تشغيل العدّاد", Modifier.fillMaxWidth()) { StepService.start(context) }
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}
