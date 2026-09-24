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
import com.khatwa.app.i18n.strings
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
    val s = strings
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val today by container.tracker.today.collectAsStateWithLifecycle()
    var temp by rememberSaveable { mutableStateOf("") }
    var final by rememberSaveable { mutableStateOf("") }
    var inc by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(settings) {
        val cfg = settings ?: return@LaunchedEffect
        if (!loaded) {
            temp = cfg.tempGoal.toString(); final = cfg.finalGoal.toString(); inc = cfg.weeklyIncrement.toString()
            manual = (cfg.manualGoal ?: today.goal).toString(); loaded = true
        }
    }

    SubScreen(title = s.goal, onBack = onBack) {
        val gi = today.goalInfo
        KCard(tone = CardTone.Accent) {
            SectionTitle(s.statusNow)
            KeyValueRow(s.currentEffectiveGoal, s.stepsCount(Fmt.n(today.goal)))
            KeyValueRow(s.week, if (gi?.isMeasurementWeek == true) s.measurementWeek else Fmt.n((gi?.weekIndex ?: 0) + 1))
            KeyValueRow(s.mode, when {
                gi?.isManual == true -> s.manualMode
                gi?.reachedFinal == true -> s.reachedFinalGoal
                else -> s.progressive
            })
            settings?.goalStartDate?.let { KeyValueRow(s.startedOn, it.toString()) }
        }
        VSpace()
        KCard {
            SectionTitle(s.progression)
            Muted(s.progressionText)
            VSpace(6.dp)
            NumberField(s.finalGoal, final, { final = it })
            NumberField(s.weeklyIncrement, inc, { inc = it })
            NumberField(s.tempGoalMeasurement, temp, { temp = it })
            VSpace(6.dp)
            PrimaryButton(s.save, Modifier.fillMaxWidth(), enabled = final.toIntOrNull() != null && inc.toIntOrNull() != null && temp.toIntOrNull() != null) {
                scope.launch { container.settings.setGoals(temp.toIntOrNull(), final.toIntOrNull(), inc.toIntOrNull(), null) }
            }
        }
        VSpace()
        KCard {
            SectionTitle(s.manualEdit)
            Muted(s.manualEditHint)
            VSpace(6.dp)
            NumberField(s.manualGoal, manual, { manual = it })
            VSpace(6.dp)
            if (settings?.manualGoal == null) {
                PrimaryButton(s.fixGoalStopProgression, Modifier.fillMaxWidth(), enabled = (manual.toIntOrNull() ?: 0) >= 100) {
                    scope.launch { container.settings.setGoals(null, null, null, manual.toInt()) }
                }
            } else {
                PrimaryButton(s.updateManualGoal, Modifier.fillMaxWidth(), enabled = (manual.toIntOrNull() ?: 0) >= 100) {
                    scope.launch { container.settings.setGoals(null, null, null, manual.toInt()) }
                }
                VSpace(6.dp)
                SecondaryButton(s.backToProgression, Modifier.fillMaxWidth()) {
                    scope.launch { container.settings.setGoals(null, null, null, null, clearManual = true) }
                }
            }
        }
    }
}
