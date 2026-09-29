package com.khatwa.app.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.challenge.ChallengeUi
import com.khatwa.app.i18n.Strings
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.CardTone
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.PrimaryButton
import com.khatwa.app.ui.components.SecondaryButton
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.onboarding.OnResume
import com.khatwa.app.ui.walks.rememberWithLocation
import com.khatwa.app.util.Fmt
import com.khatwa.core.challenge.Challenge
import com.khatwa.core.challenge.Mode
import com.khatwa.core.challenge.TimeLabel
import com.khatwa.core.walk.Distances
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** "When to go" in words: after a prayer, in the morning, now; with the clock time and feel. */
fun Strings.whenText(c: Challenge): String {
    val label = when (c.label) {
        TimeLabel.NOW -> timeNow
        TimeLabel.AFTER_FAJR -> timeAfterFajr
        TimeLabel.MORNING -> timeMorning
        TimeLabel.AFTER_DHUHR -> timeAfterDhuhr
        TimeLabel.AFTER_ASR -> timeAfterAsr
        TimeLabel.AFTER_MAGHRIB -> timeAfterMaghrib
        TimeLabel.AFTER_ISHA -> timeAfterIsha
        TimeLabel.FOR_PRAYER -> timeForPrayer(prayerName(c.prayer!!.ordinal))
    }
    val time = (if (c.tomorrow) "$tomorrowWord " else "") + Fmt.time(c.start.hour * 60 + c.start.minute)
    val feel = c.feelsC?.let { " · " + feelsLike(it.roundToInt().toString()) }.orEmpty()
    return bestTime(label, time) + feel
}

fun Strings.challengeText(c: Challenge, arabic: Boolean): String = when (c.mode) {
    Mode.TO -> challengeTo(c.place!!.name(arabic))
    Mode.AT -> challengeAt(c.place!!.name(arabic))
    Mode.LOOP -> c.bearingDeg?.let { challengeLoopToward(directionName((it / 45.0).roundToInt())) } ?: challengeLoop
}

/** The daily challenge on Home: where, how far, when; start walking, directions, another idea. */
@Composable
fun ChallengeCard(container: AppContainer, onOpenCity: () -> Unit, onOpenLive: () -> Unit) {
    val s = strings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by remember { container.challenges.ui }.collectAsStateWithLifecycle(initialValue = null)
    val active by container.walks.active.collectAsStateWithLifecycle()
    val withLocation = rememberWithLocation()
    LaunchedEffect(Unit) { launch(Dispatchers.IO) { container.challenges.refreshInputs() } }
    OnResume { scope.launch(Dispatchers.IO) { container.challenges.refreshInputs() } }

    when (val u = ui) {
        null -> Unit
        ChallengeUi.NoCity -> KCard(tone = CardTone.Soft, modifier = Modifier.testTag("challenge_no_city")) {
            Text(s.challengeTitle, style = MaterialTheme.typography.titleMedium)
            VSpace(4.dp)
            Muted(s.chooseCityPrompt)
            VSpace(8.dp)
            PrimaryButton(s.chooseCity, Modifier.fillMaxWidth().testTag("challenge_choose_city")) { onOpenCity() }
        }
        ChallengeUi.Outside -> KCard(tone = CardTone.Warning, modifier = Modifier.testTag("challenge_outside")) {
            Text(s.challengeUnavailable, style = MaterialTheme.typography.titleMedium)
            VSpace(4.dp)
            Muted(s.challengeUnavailableHint)
        }
        is ChallengeUi.Ready -> {
            val c = u.challenge
            val arabic = s.rtl
            if (u.done) {
                KCard(tone = CardTone.Accent, modifier = Modifier.testTag("challenge_done")) {
                    Text("✓ " + s.dailyChallengeDone, style = MaterialTheme.typography.titleMedium)
                    VSpace(4.dp)
                    Muted(s.challengeText(c, arabic))
                    Muted(s.dailyChallengeDoneHint)
                }
            } else KCard(modifier = Modifier.testTag("challenge_card")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (c.tomorrow) s.challengeTomorrow else s.challengeTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    c.place?.let { Text(s.placeKind(it.kind.ordinal), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                VSpace(4.dp)
                Text(s.challengeText(c, arabic), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("challenge_text"))
                if (c.mode == Mode.AT) Muted(s.challengeAtHint)
                VSpace(4.dp)
                Text(
                    s.challengeDistance(Distances.km(c.distanceM), Fmt.n(c.targetSteps), Fmt.n(c.minutes)),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("challenge_distance"),
                )
                Text(s.whenText(c), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("challenge_when"))
                VSpace(10.dp)
                if (active == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(s.startWalk, Modifier.weight(1f).testTag("challenge_start")) {
                        withLocation {
                            val started = container.walks.start(c.key, c.place?.name(arabic), c.place?.loc)
                            if (started) onOpenLive()
                        }
                    }
                    c.place?.let { p ->
                        SecondaryButton(s.directions, Modifier.weight(1f).testTag("challenge_directions")) {
                            val mode = if (c.mode == Mode.AT) "driving" else "walking"
                            val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.loc.lat},${p.loc.lon}&travelmode=$mode")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
                if (u.options > 1) TextButton(onClick = { scope.launch { container.challenges.another(c) } }, modifier = Modifier.testTag("challenge_another")) {
                    Text(s.anotherChallenge)
                }
            }
        }
    }
}
