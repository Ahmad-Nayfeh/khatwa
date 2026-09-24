package com.khatwa.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.khatwa.app.i18n.strings
import com.khatwa.app.ui.components.ProgressRing
import com.khatwa.app.ui.components.StatPill
import com.khatwa.app.ui.components.VSpace
import com.khatwa.app.util.Fmt
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Interactive day slider: the centred day is large and sharp, its neighbours are smaller, faded
 * and blurred. Swiping moves the focus; tapping a neighbour jumps to it. The day after is always
 * on the right and the day before on the left, whatever the layout direction.
 *
 * [days] is oldest first. [onFocus] reports the focused index (in [days]).
 */
@Composable
fun DaySlider(days: List<DayCard>, modifier: Modifier = Modifier, onFocus: (Int) -> Unit = {}) {
    if (days.isEmpty()) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Pager page 0 sits at the layout start (right edge in RTL). Reverse the list in RTL so the
    // newer day is always to the right of the focused one.
    val ordered = remember(days, rtl) { if (rtl) days.asReversed() else days }
    val toIndex: (Int) -> Int = { page -> if (rtl) days.size - 1 - page else page }
    val todayPage = ordered.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: (ordered.size - 1)
    val state = rememberPagerState(initialPage = todayPage) { ordered.size }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state, days.size, rtl) {
        snapshotFlow { state.currentPage }.collect { onFocus(toIndex(it)) }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val pageWidth = 156.dp
        val side = ((maxWidth - pageWidth) / 2).coerceAtLeast(0.dp)
        HorizontalPager(
            state = state,
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(horizontal = side),
            pageSpacing = 4.dp,
            beyondViewportPageCount = 2,
            modifier = Modifier.fillMaxWidth().testTag("stats_day_pager"),
        ) { page ->
            val day = ordered[page]
            // 0 when centred, 1 when a full page away.
            val distance = ((state.currentPage - page) + state.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            val focused = distance < 0.5f
            DayPage(
                day = day,
                distance = distance,
                focused = focused,
                modifier = Modifier
                    .width(pageWidth)
                    .clickable(enabled = !focused) { scope.launch { state.animateScrollToPage(page) } },
            )
        }
    }
}

@Composable
private fun DayPage(day: DayCard, distance: Float, focused: Boolean, modifier: Modifier = Modifier) {
    val s = strings
    val scale = lerp(1f, 0.78f, distance)
    val alpha = lerp(1f, 0.38f, distance)
    val blurRadius = lerp(0f, 3f, distance)
    val stat = day.stat
    val ratio = if (stat.goal > 0) (stat.steps.toFloat() / stat.goal).coerceIn(0f, 1f) else 0f
    val ringColor = if (stat.achieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val bg = if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .blur(blurRadius.dp)
            .background(bg, RoundedCornerShape(22.dp))
            .padding(vertical = 14.dp, horizontal = 10.dp)
            .then(if (focused) Modifier.testTag("stats_day_focused") else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (day.isToday) s.today else Fmt.dayName(stat.date.dayOfWeek.value),
            style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1,
        )
        Text(Fmt.dayMonth(stat.date), style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.7f))
        VSpace(8.dp)
        ProgressRing(progress = ratio, size = 104.dp, stroke = 9.dp, color = ringColor, track = fg.copy(alpha = 0.12f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    Fmt.n(stat.steps),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
                    color = fg, maxLines = 1, textAlign = TextAlign.Center,
                    modifier = if (focused) Modifier.testTag("stats_day_steps") else Modifier,
                )
                Text(s.step, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
            }
        }
        VSpace(8.dp)
        Text(
            if (stat.achieved) s.goalReachedCheck else s.goalLabel(Fmt.n(stat.goal)),
            style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1,
        )
    }
}

/** Details of the focused day, shown under the slider. */
@Composable
fun DayDetails(day: DayCard, modifier: Modifier = Modifier) {
    val s = strings
    val stat = day.stat
    val pct = if (stat.goal > 0) (stat.steps * 100 / stat.goal).toInt() else 0
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill(s.ofGoalPct, "$pct%", Modifier.weight(1f))
        StatPill(s.sessions, Fmt.n(day.sessions), Modifier.weight(1f))
        StatPill(s.longestSession, if (day.longestSessionMs > 0) Fmt.duration(day.longestSessionMs) else "—", Modifier.weight(1f))
    }
    Box(Modifier.height(0.dp))
}
