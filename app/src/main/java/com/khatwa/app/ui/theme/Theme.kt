package com.khatwa.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

object KhatwaColors {
    val Green = Color(0xFF7BD389)
    val GreenDark = Color(0xFF2E8B57)
    val Amber = Color(0xFFF2C46D)
    val Blue = Color(0xFF8FB8FF)
    val Red = Color(0xFFFF7A7A)
    val RedDark = Color(0xFFC0392B)

    val DarkBg = Color(0xFF0F1115)
    val DarkSurface = Color(0xFF171A21)
    val DarkSurfaceVariant = Color(0xFF1F232C)
    val DarkOutline = Color(0xFF2C313C)
    val DarkText = Color(0xFFE9ECF1)
    val DarkMuted = Color(0xFFA7AEBB)

    val LightBg = Color(0xFFF6F7F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEEF0F4)
    val LightOutline = Color(0xFFD9DDE5)
    val LightText = Color(0xFF15181E)
    val LightMuted = Color(0xFF5B6270)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = KhatwaColors.Green,
    onPrimary = Color(0xFF0B1A10),
    primaryContainer = Color(0xFF1E3A28),
    onPrimaryContainer = KhatwaColors.Green,
    secondary = KhatwaColors.Amber,
    onSecondary = Color(0xFF241B05),
    secondaryContainer = Color(0xFF3A3115),
    onSecondaryContainer = KhatwaColors.Amber,
    tertiary = KhatwaColors.Blue,
    onTertiary = Color(0xFF0A1830),
    error = KhatwaColors.Red,
    onError = Color(0xFF2B0B0B),
    errorContainer = Color(0xFF3A1B1B),
    onErrorContainer = KhatwaColors.Red,
    background = KhatwaColors.DarkBg,
    onBackground = KhatwaColors.DarkText,
    surface = KhatwaColors.DarkSurface,
    onSurface = KhatwaColors.DarkText,
    surfaceVariant = KhatwaColors.DarkSurfaceVariant,
    onSurfaceVariant = KhatwaColors.DarkMuted,
    outline = KhatwaColors.DarkOutline,
    outlineVariant = KhatwaColors.DarkOutline,
    surfaceContainer = KhatwaColors.DarkSurface,
    surfaceContainerHigh = KhatwaColors.DarkSurfaceVariant,
    surfaceContainerLow = Color(0xFF13161C),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = KhatwaColors.GreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F1DF),
    onPrimaryContainer = Color(0xFF14361F),
    secondary = Color(0xFF9A6B00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCEBC2),
    onSecondaryContainer = Color(0xFF3A2A00),
    tertiary = Color(0xFF2F5FB3),
    onTertiary = Color.White,
    error = KhatwaColors.RedDark,
    onError = Color.White,
    errorContainer = Color(0xFFFADBD8),
    onErrorContainer = Color(0xFF5B1A14),
    background = KhatwaColors.LightBg,
    onBackground = KhatwaColors.LightText,
    surface = KhatwaColors.LightSurface,
    onSurface = KhatwaColors.LightText,
    surfaceVariant = KhatwaColors.LightSurfaceVariant,
    onSurfaceVariant = KhatwaColors.LightMuted,
    outline = KhatwaColors.LightOutline,
    outlineVariant = KhatwaColors.LightOutline,
    surfaceContainer = KhatwaColors.LightSurface,
    surfaceContainerHigh = KhatwaColors.LightSurfaceVariant,
    surfaceContainerLow = Color(0xFFFAFBFC),
)

private val KhatwaTypography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 72.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
)

/** Arabic-first theme: always RTL, dark by default. */
@Composable
fun KhatwaTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = KhatwaTypography,
            content = content,
        )
    }
}

@Composable
fun rememberIsDark(): Boolean = isSystemInDarkTheme()
