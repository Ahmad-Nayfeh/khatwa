package com.khatwa.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.permissions.PermissionChecks
import com.khatwa.app.steps.StepService
import com.khatwa.app.ui.charts.BarChart
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.ProgressRing
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.StatPill
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.containerViewModel
import com.khatwa.app.ui.lock.LockSection
import com.khatwa.app.util.Fmt

@Composable
private fun ComparePill(pct: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            pct?.let { Fmt.pct(it) } ?: "—",
            style = MaterialTheme.typography.titleLarge,
            color = when {
                pct == null -> MaterialTheme.colorScheme.onSurface
                pct >= 0 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("home_compare"),
        )
        Text("مقارنة بالأسبوع الماضي", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun HomeScreen(container: AppContainer, onOpenSettings: () -> Unit) {
    val vm = containerViewModel { HomeViewModel(it) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val today = ui.today
    val context = LocalContext.current
    val remaining = (today.goal - today.steps).coerceAtLeast(0)
    val progress = if (today.goal > 0) today.steps.toFloat() / today.goal else 0f

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("home_scroll").padding(horizontal = 20.dp, vertical = 16.dp),
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

        LockSection(container, today, onOpenSettings)

        // Today's number and ring
        VSpace(4.dp)
        ProgressRing(progress = progress) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.n(today.steps), style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag("home_steps"))
                Muted("من ${Fmt.n(today.goal)} خطوة")
            }
        }
        VSpace(10.dp)
        Text(
            if (remaining == 0L) "أكملت هدف اليوم" else "المتبقي ${Fmt.n(remaining)} خطوة",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("home_remaining"),
        )
        VSpace(16.dp)

        // Quote of the day
        ui.quote?.let { q ->
            KCard(tone = CardTone.Soft) {
                Text("حكمة اليوم", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VSpace(6.dp)
                Text(q.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 28.sp), modifier = Modifier.testTag("home_quote"))
                q.source?.let { Muted("— $it") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { vm.anotherQuote() }) { Text("حكمة أخرى") }
                }
            }
            VSpace()
        }

        // This week
        KCard {
            SectionTitle("هذا الأسبوع")
            BarChart(
                values = ui.week.map { it.steps },
                labels = ui.week.map { Fmt.arabicDaysShort[it.date.dayOfWeek.value] ?: "?" },
                goal = today.goal.toLong(),
                thicknessDp = 22,
                height = 170,
            )
            VSpace(10.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatPill("جلسات هذا الأسبوع", Fmt.n(ui.sessionsThisWeek), Modifier.weight(1f))
                val pct = ui.compare.percent
                ComparePill(pct, Modifier.weight(1f))
            }
            VSpace(6.dp)
            Muted("المقارنة مع نفس الأيام المنقضية من الأسبوع الماضي. الأسبوع يبدأ الأحد.")
        }
        VSpace()

        // Streaks + goal
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatPill("السلسلة الحالية", "${Fmt.n(ui.streaks.current)} يوم", Modifier.weight(1f))
            StatPill("أطول سلسلة", "${Fmt.n(ui.streaks.longest)} يوم", Modifier.weight(1f))
        }
        VSpace(10.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val gi = today.goalInfo
            StatPill("الهدف الفعلي", Fmt.n(today.goal), Modifier.weight(1f))
            StatPill(
                if (gi?.isManual == true) "الهدف" else "الأسبوع",
                when {
                    gi?.isManual == true -> "يدوي ثابت"
                    gi?.isMeasurementWeek == true -> "أسبوع القياس"
                    gi?.reachedFinal == true -> "الهدف النهائي"
                    else -> Fmt.n((gi?.weekIndex ?: 0) + 1)
                },
                Modifier.weight(1f),
            )
        }
        VSpace()

        // Laptop code
        KCard(tone = if (ui.laptopCode != null) CardTone.Accent else CardTone.Normal) {
            SectionTitle("كود اللابتوب لليوم")
            when {
                !ui.laptopSecretSet -> Muted("لم يُفعَّل قفل اللابتوب بعد. فعّله من الإعدادات ← قفل اللابتوب.")
                ui.laptopCode != null -> Text(
                    ui.laptopCode!!, style = MaterialTheme.typography.displayLarge.copy(fontSize = 44.sp, letterSpacing = 6.sp),
                    modifier = Modifier.fillMaxWidth().testTag("home_laptop_code"), textAlign = TextAlign.Center,
                )
                else -> Text("الكود يظهر بعد إكمال هدف اليوم. المتبقي ${Fmt.n(remaining)} خطوة.", modifier = Modifier.testTag("home_laptop_pending"))
            }
        }
        VSpace()

        KCard(tone = CardTone.Soft) {
            Text("العدّاد يعمل في الخلفية", style = MaterialTheme.typography.titleMedium)
            VSpace(6.dp)
            Muted(if (today.sensorSeen) "آخر قراءة من الحساس وصلت." else "بانتظار أول قراءة من الحساس. إن لم تصل، راجع الإعدادات ← حالة الصلاحيات.")
            VSpace(8.dp)
            PrimaryButton("إعادة تشغيل العدّاد", Modifier.fillMaxWidth()) { StepService.start(context) }
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}
