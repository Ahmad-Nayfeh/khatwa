package com.khatwa.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt

/** Scrollable sub-screen with a title row and a back arrow. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val s = strings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(if (tag != null) Modifier.testTag(tag) else Modifier).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = s.back) }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        VSpace(8.dp)
        content()
        Box(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
fun SettingsEntry(title: String, subtitle: String? = null, tag: String? = null, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) Muted(subtitle)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Muted(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Single-choice chips in a row (theme mode and similar small enumerations). */
@Composable
fun ChoiceRow(options: List<Pair<String, String>>, selected: String, tag: String? = null, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onChange(value) },
                label = { Text(label, maxLines = 1) },
                modifier = Modifier.weight(1f).then(if (tag != null) Modifier.testTag("${tag}_$value") else Modifier),
            )
        }
    }
}

/** Day-of-week chips, ISO numbering (Mon=1..Sun=7), shown Sunday first (the week starts on Sunday). */
@Composable
fun DayOfWeekPicker(selected: Set<Int>, single: Boolean = false, onChange: (Set<Int>) -> Unit) {
    val order = listOf(7, 1, 2, 3, 4, 5, 6)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        order.forEach { d ->
            val on = d in selected
            FilterChip(
                selected = on,
                onClick = {
                    if (single) onChange(setOf(d))
                    else onChange(if (on) selected - d else selected + d)
                },
                label = { Text(Fmt.dayShort(d)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerRow(label: String, minuteOfDay: Int, onChange: (Int) -> Unit) {
    val s = strings
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(Fmt.time(minuteOfDay), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = minuteOfDay / 60, initialMinute = minuteOfDay % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onChange(state.hour * 60 + state.minute); open = false }) { Text(s.save) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(s.cancel) } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
fun ColorDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}
