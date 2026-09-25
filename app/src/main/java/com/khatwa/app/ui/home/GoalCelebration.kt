package com.khatwa.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khatwa.app.AppContainer
import com.khatwa.app.i18n.strings
import java.time.LocalDate
import kotlin.math.sin
import kotlin.random.Random

private class Piece(seed: Int) {
    private val r = Random(seed)
    val x = r.nextFloat()
    val delay = r.nextFloat() * 0.35f
    val speed = 0.8f + r.nextFloat() * 0.7f
    val sway = 0.02f + r.nextFloat() * 0.05f
    val spin = r.nextFloat() * 720f - 360f
    val w = 6f + r.nextFloat() * 6f
    val color = listOf(Color(0xFF7BD389), Color(0xFFF2C46D), Color(0xFF8FB8FF), Color(0xFFFF9A8B), Color(0xFFE9ECF1))[r.nextInt(5)]
}

/**
 * A short confetti burst and a "goal reached" chip, shown once per day (the first time Home shows
 * the goal reached that day). The day is remembered in settings so it does not repeat.
 */
@Composable
fun GoalCelebration(container: AppContainer, date: LocalDate, reached: Boolean) {
    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    if (!reached || settings == null || settings?.celebratedDate == date.toString()) return
    val progress = remember(date) { Animatable(0f) }
    LaunchedEffect(date) {
        progress.animateTo(1f, tween(4_000, easing = LinearEasing))
        container.settings.setCelebratedDate(date.toString())
    }
    val pieces = remember { List(90) { Piece(it) } }
    val p = progress.value
    Box(Modifier.fillMaxSize().testTag("goal_celebration")) {
        Canvas(Modifier.fillMaxSize()) {
            for (piece in pieces) {
                val t = ((p - piece.delay) / (1f - piece.delay)).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) continue
                val y = -40f + t * piece.speed * size.height * 1.1f
                val x = (piece.x + sin(t * 12f + piece.x * 6f) * piece.sway) * size.width
                val alpha = if (t > 0.8f) (1f - t) / 0.2f else 1f
                rotate(piece.spin * t, Offset(x, y)) {
                    drawRect(piece.color.copy(alpha = alpha), Offset(x - piece.w * density / 2, y), Size(piece.w * density, piece.w * density * 0.45f))
                }
            }
        }
        if (p < 0.95f) {
            // A plain background (not a Surface): the chip must never swallow taps meant for Home.
            Text(
                "🎉 " + strings.goalDoneToday,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
