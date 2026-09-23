package com.khatwa.app.ui.charts

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.shape.rounded
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.util.Locale

/**
 * Chart wrappers around Vico. Every chart used by the app goes through here so the UI code
 * does not depend on the library's API surface.
 */

/**
 * Maps an x index to its label. Vico throws if a formatter returns an empty string (it may ask
 * for indices outside the data when measuring), so anything unknown becomes a single space.
 */
private fun labelsFormatter(labels: List<String>) = object : CartesianValueFormatter {
    override fun format(context: CartesianMeasuringContext, value: Double, verticalAxisPosition: Axis.Position.Vertical?): CharSequence {
        val i = Math.round(value).toInt()
        return labels.getOrNull(i)?.takeIf { it.isNotBlank() } ?: " "
    }
}

private val thousands = object : CartesianValueFormatter {
    override fun format(context: CartesianMeasuringContext, value: Double, verticalAxisPosition: Axis.Position.Vertical?): CharSequence =
        if (value >= 1000) String.format(Locale.US, "%.0fk", value / 1000) else String.format(Locale.US, "%.0f", value)
}

private val plain = object : CartesianValueFormatter {
    override fun format(context: CartesianMeasuringContext, value: Double, verticalAxisPosition: Axis.Position.Vertical?): CharSequence =
        String.format(Locale.US, "%.0f", value)
}

private val oneDecimal = object : CartesianValueFormatter {
    override fun format(context: CartesianMeasuringContext, value: Double, verticalAxisPosition: Axis.Position.Vertical?): CharSequence =
        String.format(Locale.US, "%.1f", value)
}

/** Vertical bars with an optional horizontal goal line. */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    goal: Long? = null,
    barColor: Color = MaterialTheme.colorScheme.primary,
    goalColor: Color = MaterialTheme.colorScheme.secondary,
    labelEvery: Int = 1,
    thicknessDp: Int = 14,
    height: Int = 180,
    yFormatter: CartesianValueFormatter = thousands,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        modelProducer.runTransaction {
            columnSeries { series(if (values.isEmpty()) listOf(0L) else values) }
        }
    }
    val maxY = maxOf(values.maxOrNull() ?: 0L, goal ?: 0L, 10L).toDouble() * 1.1
    val goalLine = goal?.let { g ->
        val line = rememberLineComponent(fill = fill(goalColor), thickness = 2.dp)
        remember(g, line) { HorizontalLine(y = { g.toDouble() }, line = line) }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(
                columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(
                        fill = fill(barColor),
                        thickness = thicknessDp.dp,
                        shape = CorneredShape.rounded(topLeft = 4.dp, topRight = 4.dp),
                    )
                ),
                rangeProvider = CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = maxY),
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = yFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = labelsFormatter(labels),
                guideline = null,
                itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { labelEvery }),
            ),
            decorations = listOfNotNull(goalLine),
        ),
        modelProducer = modelProducer,
        modifier = modifier.height(height.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false),
    )
}

/**
 * Monthly average steps as bars (start axis) plus a weight line on the end axis.
 * Months without a weight entry are skipped in the line (null values).
 */
@Composable
fun MonthlyStepsAndWeightChart(
    monthLabels: List<String>,
    avgSteps: List<Int>,
    weightsByMonth: List<Double?>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    lineColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val hasWeight = weightsByMonth.any { it != null }
    LaunchedEffect(avgSteps, weightsByMonth) {
        modelProducer.runTransaction {
            columnSeries { series(avgSteps.ifEmpty { listOf(0) }) }
            if (hasWeight) {
                val xs = ArrayList<Int>()
                val ys = ArrayList<Double>()
                weightsByMonth.forEachIndexed { i, w -> if (w != null) { xs += i; ys += w } }
                lineSeries { series(xs, ys) }
            }
        }
    }
    val maxSteps = maxOf(avgSteps.maxOrNull() ?: 0, 10).toDouble() * 1.15
    val weights = weightsByMonth.filterNotNull()
    val minW = (weights.minOrNull() ?: 0.0) - 2.0
    val maxW = (weights.maxOrNull() ?: 10.0) + 2.0

    val columnLayer = rememberColumnCartesianLayer(
        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
            rememberLineComponent(fill = fill(barColor), thickness = 12.dp, shape = CorneredShape.rounded(topLeft = 4.dp, topRight = 4.dp))
        ),
        rangeProvider = CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = maxSteps),
    )
    val chart = if (hasWeight) {
        rememberCartesianChart(
            columnLayer,
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(lineColor)), areaFill = null)
                ),
                rangeProvider = CartesianLayerRangeProvider.fixed(minY = minW, maxY = maxW),
                verticalAxisPosition = Axis.Position.Vertical.End,
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = thousands),
            endAxis = VerticalAxis.rememberEnd(valueFormatter = oneDecimal),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = labelsFormatter(monthLabels), guideline = null),
        )
    } else {
        rememberCartesianChart(
            columnLayer,
            startAxis = VerticalAxis.rememberStart(valueFormatter = thousands),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = labelsFormatter(monthLabels), guideline = null),
        )
    }
    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier.height(200.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false),
    )
}

/** Weight over time as a simple line. */
@Composable
fun WeightLineChart(
    labels: List<String>,
    values: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(values) {
        modelProducer.runTransaction { lineSeries { series(values.ifEmpty { listOf(0.0) }) } }
    }
    val minW = (values.minOrNull() ?: 0.0) - 1.0
    val maxW = (values.maxOrNull() ?: 10.0) + 1.0
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(lineColor)), areaFill = null)
                ),
                rangeProvider = CartesianLayerRangeProvider.fixed(minY = minW, maxY = maxW),
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = oneDecimal),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = labelsFormatter(labels), guideline = null,
                itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { maxOf(1, labels.size / 6) }),
            ),
        ),
        modelProducer = modelProducer,
        modifier = modifier.height(160.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false),
    )
}

val PlainFormatter: CartesianValueFormatter get() = plain
