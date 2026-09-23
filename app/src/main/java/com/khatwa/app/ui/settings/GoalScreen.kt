package com.khatwa.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.KeyValueRow
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.onboarding.NumberField
import com.khatwa.app.util.Fmt
import kotlinx.coroutines.launch

@Composable
fun GoalScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val today by container.tracker.today.collectAsStateWithLifecycle()
    var temp by rememberSaveable { mutableStateOf("") }
    var final by rememberSaveable { mutableStateOf("") }
    var inc by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!loaded) {
            temp = s.tempGoal.toString(); final = s.finalGoal.toString(); inc = s.weeklyIncrement.toString()
            manual = (s.manualGoal ?: today.goal).toString(); loaded = true
        }
    }

    SubScreen(title = "الهدف", onBack = onBack) {
        val gi = today.goalInfo
        KCard(tone = CardTone.Accent) {
            SectionTitle("الحالة الآن")
            KeyValueRow("الهدف الفعلي الحالي", "${Fmt.n(today.goal)} خطوة")
            KeyValueRow("الأسبوع", if (gi?.isMeasurementWeek == true) "أسبوع القياس" else Fmt.n((gi?.weekIndex ?: 0) + 1))
            KeyValueRow("الوضع", when {
                gi?.isManual == true -> "يدوي (التدرّج متوقف)"
                gi?.reachedFinal == true -> "وصل للهدف النهائي"
                else -> "تدريجي"
            })
            settings?.goalStartDate?.let { KeyValueRow("بداية الاستخدام", it.toString()) }
        }
        VSpace()
        KCard {
            SectionTitle("التدرّج")
            Muted("أسبوع القياس (7 أيام) بالهدف المؤقت، ثم متوسط القياس + الزيادة، ثم يرتفع بالزيادة كل أسبوع حتى الهدف النهائي. إن فشلت أكثر من 4 أيام في أسبوع يثبت الهدف في الأسبوع التالي. لا يهبط تلقائياً أبداً.")
            VSpace(6.dp)
            NumberField("الهدف النهائي", final, { final = it })
            NumberField("الزيادة الأسبوعية", inc, { inc = it })
            NumberField("الهدف المؤقت (أسبوع القياس)", temp, { temp = it })
            VSpace(6.dp)
            PrimaryButton("حفظ", Modifier.fillMaxWidth(), enabled = final.toIntOrNull() != null && inc.toIntOrNull() != null && temp.toIntOrNull() != null) {
                scope.launch { container.settings.setGoals(temp.toIntOrNull(), final.toIntOrNull(), inc.toIntOrNull(), null) }
            }
        }
        VSpace()
        KCard {
            SectionTitle("تعديل يدوي")
            Muted("يوقف التدرّج ويثبت الهدف على قيمة تختارها. يمكنك العودة للتدرّج في أي وقت.")
            VSpace(6.dp)
            NumberField("الهدف اليدوي", manual, { manual = it })
            VSpace(6.dp)
            if (settings?.manualGoal == null) {
                PrimaryButton("تثبيت هذا الهدف وإيقاف التدرّج", Modifier.fillMaxWidth(), enabled = (manual.toIntOrNull() ?: 0) >= 100) {
                    scope.launch { container.settings.setGoals(null, null, null, manual.toInt()) }
                }
            } else {
                PrimaryButton("تحديث الهدف اليدوي", Modifier.fillMaxWidth(), enabled = (manual.toIntOrNull() ?: 0) >= 100) {
                    scope.launch { container.settings.setGoals(null, null, null, manual.toInt()) }
                }
                VSpace(6.dp)
                SecondaryButton("العودة إلى التدرّج التلقائي", Modifier.fillMaxWidth()) {
                    scope.launch { container.settings.setGoals(null, null, null, null, clearManual = true) }
                }
            }
        }
    }
}
