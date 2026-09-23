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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
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
    val vm = containerViewModel { StatsViewModel(it) }
    val ui by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("الإحصائيات", style = MaterialTheme.typography.headlineMedium)
        VSpace()

        KCard {
            SectionTitle("آخر 30 يوماً")
            BarChart(
                values = ui.last30.map { it.steps },
                labels = ui.last30.map { Fmt.dayMonth(it.date) },
                goal = ui.goal.toLong(),
                labelEvery = 5,
                thicknessDp = 6,
                height = 180,
            )
            VSpace(10.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("متوسط يومي", Fmt.n(ui.month.averagePerDay), Modifier.weight(1f))
                StatPill("أفضل يوم", Fmt.n(ui.month.bestDay?.steps ?: 0), Modifier.weight(1f))
            }
            VSpace(8.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("أيام تحقق الهدف", "${Fmt.n(ui.month.daysAchieved)} / ${Fmt.n(ui.month.daysCounted)}", Modifier.weight(1f))
                StatPill("جلسات الشهر", Fmt.n(ui.sessions.countThisMonth), Modifier.weight(1f))
            }
            ui.month.bestDay?.let { Muted("أفضل يوم: ${Fmt.iso(it.date)}", Modifier.padding(top = 6.dp)) }
        }
        VSpace()

        KCard {
            SectionTitle("جلسات المشي")
            Muted("الجلسة: مشي متواصل 10 دقائق فأكثر مع توقفات لا تتجاوز دقيقتين.")
            VSpace(6.dp)
            KeyValueRow("جلسات هذا الأسبوع", Fmt.n(ui.sessions.countThisWeek))
            KeyValueRow("أطول جلسة هذا الشهر", Fmt.duration(ui.sessions.longestMs))
            KeyValueRow("متوسط طول الجلسة", Fmt.duration(ui.sessions.averageMs))
        }
        VSpace()

        KCard {
            SectionTitle("آخر 12 شهراً")
            Muted("متوسط الخطوات اليومية لكل شهر، مع خط الوزن إن وُجد.")
            VSpace(6.dp)
            MonthlyStepsAndWeightChart(
                monthLabels = ui.months.map { (ym, _) -> Fmt.arabicMonths[ym.monthValue - 1].take(3) },
                avgSteps = ui.months.map { it.second },
                weightsByMonth = ui.weightByMonth,
            )
        }
        VSpace()

        KCard {
            SectionTitle("توزيع المشي على ساعات اليوم")
            Muted("متوسط الخطوات في كل ساعة خلال آخر 30 يوماً، من لقطات كل 15 دقيقة.")
            VSpace(6.dp)
            BarChart(
                values = ui.hours.map { it.toLong() },
                labels = (0 until 24).map { it.toString() },
                labelEvery = 3,
                thicknessDp = 8,
                height = 160,
                yFormatter = PlainFormatter,
            )
        }
        VSpace()

        KCard {
            SectionTitle("الاستسلامات هذا الشهر")
            Text(Fmt.n(ui.surrendersThisMonth), style = MaterialTheme.typography.displayLarge)
            Muted("كل إلغاء طارئ للقفل يُسجَّل هنا مع التاريخ والخطوات المتبقية.")
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}
