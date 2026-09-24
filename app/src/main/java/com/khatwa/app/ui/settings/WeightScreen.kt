package com.khatwa.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.data.WeightEntity
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.charts.WeightLineChart
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun WeightScreen(container: AppContainer, onBack: () -> Unit) {
    val s = strings
    val scope = rememberCoroutineScope()
    val weights by container.db.weights().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var input by rememberSaveable { mutableStateOf("") }

    SubScreen(title = s.weightLog, onBack = onBack) {
        KCard {
            SectionTitle(s.newEntry)
            Muted(s.weightHint)
            VSpace(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> if (v.length <= 6 && v.matches(Regex("^\\d{0,3}([.,]\\d?)?$"))) input = v },
                    label = { Text(s.kg) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(s.save, enabled = input.replace(',', '.').toDoubleOrNull()?.let { it in 20.0..400.0 } == true) {
                    val kg = ((input.replace(',', '.').toDouble()) * 10).roundToInt() / 10.0
                    scope.launch {
                        container.db.weights().insert(WeightEntity(date = LocalDate.now().toString(), kg = kg, createdMs = System.currentTimeMillis()))
                        input = ""
                    }
                }
            }
        }
        VSpace()

        if (weights.size >= 2) {
            KCard {
                SectionTitle(s.trend)
                WeightLineChart(labels = weights.map { Fmt.dayMonth(LocalDate.parse(it.date)) }, values = weights.map { it.kg })
            }
            VSpace()
        }

        settings?.let { st ->
            KCard {
                SectionTitle(s.weeklyReminder)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.enableReminder, style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = st.weightReminderEnabled, onCheckedChange = { on ->
                        scope.launch {
                            container.settings.setWeightReminder(on, st.weightReminderDay, st.weightReminderMinute)
                            container.alarms.scheduleAll(container.settings.current())
                        }
                    })
                }
                if (st.weightReminderEnabled) {
                    VSpace(6.dp)
                    DayOfWeekPicker(selected = setOf(st.weightReminderDay), single = true) { days ->
                        scope.launch {
                            container.settings.setWeightReminder(true, days.first(), st.weightReminderMinute)
                            container.alarms.scheduleAll(container.settings.current())
                        }
                    }
                    VSpace(6.dp)
                    TimePickerRow(label = s.time, minuteOfDay = st.weightReminderMinute) { m ->
                        scope.launch {
                            container.settings.setWeightReminder(true, st.weightReminderDay, m)
                            container.alarms.scheduleAll(container.settings.current())
                        }
                    }
                }
            }
            VSpace()
        }

        KCard {
            SectionTitle(s.log)
            if (weights.isEmpty()) Muted(s.noEntries)
            weights.asReversed().forEach { w ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(s.kgValue(Fmt.kg(w.kg)), style = MaterialTheme.typography.bodyLarge)
                    Muted(w.date)
                    TextButton(onClick = { scope.launch { container.db.weights().delete(w.id) } }) { Text(s.delete) }
                }
            }
        }
    }
}
