package com.khatwa.app.ui.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt
import com.khatwa.core.lock.ScheduleConfig
import kotlinx.coroutines.launch

@Composable
fun ScheduledLockScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val s = settings?.schedule ?: ScheduleConfig()

    fun save(next: ScheduleConfig) {
        scope.launch {
            container.settings.setSchedule(next)
            container.alarms.scheduleAll(container.settings.current())
        }
    }

    SubScreen(title = "القفل المجدول", onBack = onBack) {
        KCard {
            SwitchRow("تفعيل القفل المجدول يومياً", s.enabled, "عند وقت البدء، إن لم تُكمل الهدف الفعلي يُقفل الجوال حتى تكمله أو حتى وقت الانتهاء.") { on ->
                save(s.copy(enabled = on))
            }
        }
        VSpace()
        KCard {
            SectionTitle("الأوقات")
            TimePickerRow("وقت البدء", s.startMinute) { m ->
                val end = if (s.endMinute <= m) minOf(m + 60, 23 * 60 + 59) else s.endMinute
                save(s.copy(startMinute = m, endMinute = end))
            }
            TimePickerRow("وقت الانتهاء", s.endMinute) { m ->
                if (m > s.startMinute) save(s.copy(endMinute = m))
            }
            Muted("وقت الانتهاء يجب أن يكون بعد وقت البدء في نفس اليوم. تنبيه هادئ قبل البدء بـ ${s.warnMinutesBefore} دقيقة.")
        }
        VSpace()
        KCard {
            SectionTitle("أيام الأسبوع")
            DayOfWeekPicker(selected = s.days) { days -> save(s.copy(days = days)) }
        }
        VSpace()
        KCard(tone = CardTone.Soft) {
            Text("الملخص", modifier = Modifier.testTag("schedule_summary"))
            VSpace(4.dp)
            Muted(
                if (s.enabled) "من ${Fmt.time(s.startMinute)} إلى ${Fmt.time(s.endMinute)} في ${s.days.sorted().joinToString("، ") { Fmt.arabicDays[it] ?: "" }}."
                else "القفل المجدول مطفأ."
            )
        }
    }
}
