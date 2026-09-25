package com.khatwa.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * The app's living background: a landscape drawn in code whose sky follows the real time of day
 * (dawn, day, sunset, night with stars), with the sun or the moon on its arc, drifting clouds and
 * slowly moving hills. It is kept soft (a veil in the theme's background colour lies over it) so
 * text stays readable in both themes. It redraws ~15 times a second, and stands still when the
 * phone's animations are turned off.
 */
@Composable
fun LivingBackground(dark: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animate = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
    var seconds by remember { mutableFloatStateOf(0f) }
    var hour by remember { mutableStateOf(LivingBackgroundPreview.hour ?: hourNow()) }
    LaunchedEffect(animate) {
        var ticks = 0
        while (true) {
            delay(if (animate) 66L else 60_000L)
            if (animate) seconds += 0.066f
            if (++ticks % 300 == 0 || !animate) hour = LivingBackgroundPreview.hour ?: hourNow()
        }
    }
    val stars = remember { List(70) { Star(Random(it * 31 + 7).nextFloat(), Random(it * 17 + 3).nextFloat() * 0.55f, 0.6f + Random(it).nextFloat() * 1.4f, Random(it * 5).nextFloat() * 6.28f) } }
    val veil = if (dark) KhatwaColors.DarkBg else KhatwaColors.LightBg

    Canvas(modifier.fillMaxSize()) {
        val sky = skyAt(hour)
        val night = nightness(hour)
        drawRect(Brush.verticalGradient(listOf(sky.top, sky.bottom)))
        drawStars(stars, night, seconds)
        drawSunOrMoon(hour, seconds)
        drawClouds(seconds, lerp(Color.White, sky.top, 0.35f + 0.4f * night))
        drawHills(seconds, sky, night)
        // The veil: the theme's background colour over the scene keeps every text readable.
        drawRect(veil.copy(alpha = if (dark) 0.62f else 0.5f))
    }
}

/** Tests fix the hour to show the landscape at dawn, day, sunset and night. Null: the real time. */
object LivingBackgroundPreview {
    @Volatile var hour: Float? = null
}

private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
private data class Sky(val top: Color, val bottom: Color, val land: Color)

private fun hourNow(): Float = LocalTime.now().let { it.hour + it.minute / 60f }

/** Sky colours through the day; values between key hours are blended. */
private val keys = listOf(
    0f to Sky(Color(0xFF0B1026), Color(0xFF1B2A4A), Color(0xFF0F1A2E)),
    5f to Sky(Color(0xFF141B3A), Color(0xFF2E3B66), Color(0xFF15203A)),
    6.5f to Sky(Color(0xFF3A4A7A), Color(0xFFF2A97E), Color(0xFF2E4A3E)),
    9f to Sky(Color(0xFF4F97CF), Color(0xFFBFE3F5), Color(0xFF4E8A5A)),
    15f to Sky(Color(0xFF4A8FC9), Color(0xFFCBE7F4), Color(0xFF4F8B58)),
    17.5f to Sky(Color(0xFF5C6FA8), Color(0xFFF6C089), Color(0xFF4A6E4B)),
    18.7f to Sky(Color(0xFF3B2F63), Color(0xFFEF8A5D), Color(0xFF30403A)),
    20f to Sky(Color(0xFF1A2145), Color(0xFF3E4F7A), Color(0xFF16233A)),
    24f to Sky(Color(0xFF0B1026), Color(0xFF1B2A4A), Color(0xFF0F1A2E)),
)

private fun skyAt(h: Float): Sky {
    val i = keys.indexOfLast { it.first <= h }.coerceIn(0, keys.size - 2)
    val (h0, a) = keys[i]
    val (h1, b) = keys[i + 1]
    val t = ((h - h0) / (h1 - h0)).coerceIn(0f, 1f)
    return Sky(lerp(a.top, b.top, t), lerp(a.bottom, b.bottom, t), lerp(a.land, b.land, t))
}

/** 1 in the middle of the night, 0 in full day, blended around dawn and dusk. */
private fun nightness(h: Float): Float = when {
    h < 5f || h >= 20f -> 1f
    h < 7f -> 1f - (h - 5f) / 2f
    h >= 18.5f -> (h - 18.5f) / 1.5f
    else -> 0f
}

private fun DrawScope.drawStars(stars: List<Star>, night: Float, t: Float) {
    if (night <= 0f) return
    for (s in stars) {
        val twinkle = 0.55f + 0.45f * sin(t * 1.3f + s.phase)
        drawCircle(Color.White.copy(alpha = night * twinkle * 0.9f), radius = s.r * density, center = Offset(s.x * size.width, s.y * size.height))
    }
}

/** The sun from 6 to 18, the moon otherwise, each on an arc across the upper sky. */
private fun DrawScope.drawSunOrMoon(h: Float, t: Float) {
    val isDay = h in 6f..18f
    val p = if (isDay) (h - 6f) / 12f else ((h + 6f) % 24f) / 12f
    val x = size.width * (0.1f + 0.8f * p)
    val y = size.height * (0.32f - 0.22f * sin(PI.toFloat() * p))
    val r = size.minDimension * 0.07f
    if (isDay) {
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFE8A3), Color.Transparent), Offset(x, y), r * 4f), r * 4f, Offset(x, y))
        drawCircle(Color(0xFFFFE08A), r, Offset(x, y))
    } else {
        drawCircle(Brush.radialGradient(listOf(Color(0x33DDE6FF), Color.Transparent), Offset(x, y), r * 3.5f), r * 3.5f, Offset(x, y))
        drawCircle(Color(0xFFE9EEF9), r * 0.85f, Offset(x, y))
        // The crescent: a disc in the sky's colour cut out of the moon.
        drawCircle(Color(0xFF1B2A4A).copy(alpha = 0.9f), r * 0.75f, Offset(x + r * 0.35f, y - r * 0.15f))
    }
    if (!isDay) return
    // A faint slow shimmer so the day sky is never completely still.
    drawCircle(Color.White.copy(alpha = 0.04f + 0.03f * sin(t * 0.5f)), r * 6f, Offset(x, y))
}

private fun DrawScope.drawClouds(t: Float, color: Color) {
    val w = size.width
    // (height as a fraction, scale, speed in widths per minute, start offset)
    val clouds = listOf(
        floatArrayOf(0.12f, 1.0f, 0.10f, 0.1f),
        floatArrayOf(0.22f, 0.7f, 0.07f, 0.55f),
        floatArrayOf(0.30f, 1.25f, 0.05f, 0.8f),
        floatArrayOf(0.17f, 0.55f, 0.12f, 0.35f),
    )
    for (c in clouds) {
        val span = w * 1.6f
        val x = ((c[3] * span + t / 60f * c[2] * w) % span) - w * 0.3f
        val y = size.height * c[0]
        val s = size.minDimension * 0.09f * c[1]
        val a = color.copy(alpha = 0.55f)
        drawCircle(a, s, Offset(x, y))
        drawCircle(a, s * 1.3f, Offset(x + s * 1.2f, y - s * 0.4f))
        drawCircle(a, s * 1.05f, Offset(x + s * 2.4f, y))
        drawCircle(a, s * 0.9f, Offset(x + s * 1.1f, y + s * 0.35f))
    }
}

/** Three layers of hills, the nearer ones darker and drifting a little faster. */
private fun DrawScope.drawHills(t: Float, sky: Sky, night: Float) {
    val layers = listOf(
        Triple(0.66f, 0.35f, 0.004f),
        Triple(0.74f, 0.55f, 0.008f),
        Triple(0.83f, 0.8f, 0.014f),
    )
    for ((i, layer) in layers.withIndex()) {
        val (base, depth, speed) = layer
        val color = lerp(lerp(sky.bottom, sky.land, depth), Color.Black, 0.18f * i + 0.25f * night)
        val path = Path()
        val w = size.width
        val h = size.height
        val shift = t * speed * w
        path.moveTo(0f, h)
        var x = 0f
        while (x <= w + 8f) {
            val u = (x + shift) / w
            val y = h * base - h * 0.035f * (sin(u * 2f * PI.toFloat() * (1.2f + i * 0.5f) + i) + 0.6f * cos(u * 2f * PI.toFloat() * (2.7f + i) + i * 2f))
            path.lineTo(x, max(0f, y))
            x += 8f
        }
        path.lineTo(w, h)
        path.close()
        drawPath(path, color)
    }
}
