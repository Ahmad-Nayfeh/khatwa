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
import com.khatwa.app.i18n.strings
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
    val s = strings
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val cfg = settings?.schedule ?: ScheduleConfig()

    fun save(next: ScheduleConfig) {
        scope.launch {
            container.settings.setSchedule(next)
            container.alarms.scheduleAll(container.settings.current())
        }
    }

    SubScreen(title = s.scheduledLock, onBack = onBack) {
        KCard {
            SwitchRow(s.enableScheduledLock, cfg.enabled, s.scheduledLockHint) { on ->
                save(cfg.copy(enabled = on))
            }
        }
        VSpace()
        KCard {
            SectionTitle(s.times)
            TimePickerRow(s.startTime, cfg.startMinute) { m ->
                val end = if (cfg.endMinute <= m) minOf(m + 60, 23 * 60 + 59) else cfg.endMinute
                save(cfg.copy(startMinute = m, endMinute = end))
            }
            TimePickerRow(s.endTime, cfg.endMinute) { m ->
                if (m > cfg.startMinute) save(cfg.copy(endMinute = m))
            }
            Muted(s.endAfterStartHint(cfg.warnMinutesBefore))
        }
        VSpace()
        KCard {
            SectionTitle(s.weekDays)
            DayOfWeekPicker(selected = cfg.days) { days -> save(cfg.copy(days = days)) }
        }
        VSpace()
        KCard(tone = CardTone.Soft) {
            Text(s.summary, modifier = Modifier.testTag("schedule_summary"))
            VSpace(4.dp)
            Muted(
                if (cfg.enabled) s.scheduleSummary(Fmt.time(cfg.startMinute), Fmt.time(cfg.endMinute), cfg.days.sorted().joinToString(s.listSeparator) { Fmt.dayName(it) })
                else s.scheduledLockOff
            )
        }
    }
}
