package com.khatwa.app.ui.lock

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.DangerButton
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt
import kotlinx.coroutines.delay

const val EMERGENCY_WAIT_SECONDS = 60

/**
 * The deliberately slow emergency exit: wait 60 seconds, type the long phrase exactly, confirm.
 * Every completion is recorded as a surrender by the caller.
 */
@Composable
fun EmergencyFlow(phrase: String, remaining: Long, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val s = strings
    var secondsLeft by remember { mutableIntStateOf(EMERGENCY_WAIT_SECONDS) }
    var text by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) { delay(1_000); secondsLeft-- }
    }
    val typed = text.trim() == phrase.trim()

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(s.emergencyTitle, style = MaterialTheme.typography.headlineMedium)
        VSpace(8.dp)
        Muted(s.emergencyWarning(Fmt.n(remaining)), align = TextAlign.Center)
        VSpace(16.dp)
        KCard(tone = CardTone.Soft) {
            if (secondsLeft > 0) {
                Text(s.waitSeconds(secondsLeft), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("lock_countdown"))
                Muted(s.breathe)
            } else {
                Text(s.typePhrase, style = MaterialTheme.typography.titleMedium)
                VSpace(6.dp)
                Text("«$phrase»", style = MaterialTheme.typography.bodyLarge)
                VSpace(10.dp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().testTag("lock_phrase"),
                    singleLine = false,
                    minLines = 2,
                )
                VSpace(10.dp)
                if (!confirming) {
                    DangerButton(s.continue_, Modifier.fillMaxWidth().testTag("lock_continue"), enabled = typed) { confirming = true }
                } else {
                    Text(s.sureSurrender, style = MaterialTheme.typography.bodyLarge)
                    VSpace(8.dp)
                    DangerButton(s.confirmSurrender, Modifier.fillMaxWidth().testTag("lock_confirm"), onClick = onConfirm)
                }
            }
        }
        VSpace(12.dp)
        SecondaryButton(s.backIWillWalk, Modifier.fillMaxWidth().testTag("lock_emergency_cancel"), onClick = onCancel)
        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) { Text("") }
    }
}
