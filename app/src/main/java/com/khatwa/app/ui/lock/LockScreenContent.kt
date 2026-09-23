package com.khatwa.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.khatwa.app.AppContainer
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.ProgressRing
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt
import com.khatwa.core.quotes.QuotePicker
import kotlinx.coroutines.launch

/** The lock screen shown over blocked apps (and reused inside the app for the emergency exit). */
@Composable
fun LockScreenContent(c: AppContainer, onOpenAllowed: () -> Unit) {
    val today by c.tracker.today.collectAsState()
    val lock by c.lock.state.collectAsState()
    val quotes by c.features.quotes.observeAll().collectAsState(initial = emptyList())
    val settings by c.settings.flow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var emergency by remember { mutableStateOf(false) }

    val remaining = lock.remaining(today.steps)
    val target = lock.target().coerceAtLeast(1)
    val progress = 1f - remaining.toFloat() / target
    val quote = quotes.getOrNull(QuotePicker.indexFor(today.date, quotes.size))?.text

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (emergency) {
            EmergencyFlow(
                phrase = settings?.emergencyPhrase ?: com.khatwa.app.settings.Settings.DEFAULT_EMERGENCY_PHRASE,
                remaining = remaining,
                onCancel = { emergency = false },
                onConfirm = { scope.launch { c.lock.surrender() } },
            )
            return@Column
        }
        Text("امشِ لتفتح جوالك", style = MaterialTheme.typography.headlineMedium)
        VSpace(20.dp)
        ProgressRing(progress = progress, size = 240.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.n(remaining), style = MaterialTheme.typography.displayLarge, modifier = Modifier.testTag("lock_remaining"))
                Muted("خطوة متبقية")
            }
        }
        VSpace(16.dp)
        Muted(
            when (val l = lock) {
                is com.khatwa.core.lock.LockState.Manual -> "قفل يدوي: ${Fmt.n(l.targetSteps)} خطوة من لحظة التفعيل"
                is com.khatwa.core.lock.LockState.Scheduled -> "قفل مجدول حتى إكمال هدف اليوم (${Fmt.n(l.goal)}) أو الساعة ${Fmt.time(minuteOfDay(l.endAtMs))}"
                else -> ""
            },
            align = TextAlign.Center,
        )
        VSpace(20.dp)
        quote?.let {
            KCard(tone = CardTone.Soft) {
                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 28.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            VSpace(20.dp)
        }
        PrimaryButton("فتح التطبيقات المسموحة", Modifier.fillMaxWidth().testTag("lock_open_allowed"), onClick = onOpenAllowed)
        VSpace(8.dp)
        TextButton(onClick = { emergency = true }, modifier = Modifier.testTag("lock_emergency")) {
            Text("طوارئ / إلغاء القفل", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun minuteOfDay(epochMs: Long): Int {
    val t = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return t.hour * 60 + t.minute
}
