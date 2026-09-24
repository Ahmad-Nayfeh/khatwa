package com.khatwa.app.ui.stats

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.charts.BarChart
import com.khatwa.app.ui.charts.MonthlyStepsAndWeightChart
import com.khatwa.app.ui.charts.PlainFormatter
import com.khatwa.app.ui.components.KCard
import com.khatwa.app.ui.components.KeyValueRow
import com.khatwa.app.ui.components.Muted
import com.khatwa.app.ui.components.SectionTitle
import com.khatwa.app.ui.components.StatPill
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.ui.containerViewModel
import com.khatwa.app.util.Fmt

@Composable
fun StatsScreen(container: AppContainer) {
    val s = strings
    val vm = containerViewModel { StatsViewModel(it) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val focusedDay = ui.days.getOrNull(focusedIndex) ?: ui.days.lastOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("stats_scroll").padding(vertical = 16.dp)) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(s.statistics, style = MaterialTheme.typography.headlineMedium)
            Muted(s.swipeHint)
        }
        VSpace(8.dp)

        // Day slider (bleeds to the screen edges so neighbours peek in).
        if (ui.days.isNotEmpty()) {
            DaySlider(ui.days, onFocus = { focusedIndex = it })
            VSpace(10.dp)
            focusedDay?.let { DayDetails(it, Modifier.padding(horizontal = 20.dp)) }
            VSpace()
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            // Summary strip
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(s.currentStreak, s.daysCount(Fmt.n(ui.streaks.current)), Modifier.weight(1f))
                StatPill(s.longestStreak, s.daysCount(Fmt.n(ui.streaks.longest)), Modifier.weight(1f))
            }
            VSpace(8.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(s.dailyAverage, Fmt.n(ui.month.averagePerDay), Modifier.weight(1f))
                StatPill(s.bestDay, Fmt.n(ui.month.bestDay?.steps ?: 0), Modifier.weight(1f))
                StatPill(s.goalDays, "${Fmt.n(ui.month.daysAchieved)}/${Fmt.n(ui.month.daysCounted)}", Modifier.weight(1f))
            }
            VSpace()

            KCard {
                SectionTitle(s.last30Days)
                BarChart(
                    values = ui.last30.map { it.steps },
                    labels = ui.last30.map { Fmt.dayMonth(it.date) },
                    goal = ui.goal.toLong(),
                    labelEvery = 5,
                    thicknessDp = 6,
                    height = 170,
                )
                ui.month.bestDay?.let { Muted(s.bestDayLine(Fmt.iso(it.date)), Modifier.padding(top = 6.dp)) }
            }
            VSpace()

            KCard {
                SectionTitle(s.walkingSessions)
                Muted(s.sessionDefinition)
                VSpace(6.dp)
                KeyValueRow(s.thisWeek, Fmt.n(ui.sessions.countThisWeek))
                KeyValueRow(s.thisMonth, Fmt.n(ui.sessions.countThisMonth))
                KeyValueRow(s.longestSessionThisMonth, Fmt.duration(ui.sessions.longestMs))
                KeyValueRow(s.averageSessionLength, Fmt.duration(ui.sessions.averageMs))
            }
            VSpace()

            KCard {
                SectionTitle(s.last12Months)
                Muted(s.last12MonthsHint)
                VSpace(6.dp)
                MonthlyStepsAndWeightChart(
                    monthLabels = ui.months.map { (ym, _) -> Fmt.monthShort(ym.monthValue) },
                    avgSteps = ui.months.map { it.second },
                    weightsByMonth = ui.weightByMonth,
                )
            }
            VSpace()

            KCard {
                SectionTitle(s.hourDistribution)
                Muted(s.hourDistributionHint)
                VSpace(6.dp)
                BarChart(
                    values = ui.hours.map { it.toLong() },
                    labels = (0 until 24).map { it.toString() },
                    labelEvery = 3,
                    thicknessDp = 8,
                    height = 150,
                    yFormatter = PlainFormatter,
                )
            }
            VSpace()

            KCard {
                SectionTitle(s.surrendersThisMonth)
                KeyValueRow(s.emergencyCancelCount, Fmt.n(ui.surrendersThisMonth))
                Muted(s.surrenderHint)
            }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}
