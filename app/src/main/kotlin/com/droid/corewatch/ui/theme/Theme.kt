// Copyright 2026 The Corewatch Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.corewatch.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

val LocalIsDarkTheme = compositionLocalOf { true }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF005AC1), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF006B58), onSecondary = Color.White,
    secondaryContainer = Color(0xFF7BF8D8), onSecondaryContainer = Color(0xFF002019),
    tertiary = Color(0xFF8B5000), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBE), onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF7F9FF), onBackground = Color(0xFF181C22),
    surface = Color(0xFFF7F9FF), onSurface = Color(0xFF181C22),
    surfaceVariant = Color(0xFFDFE2EB), onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4FA),
    surfaceContainer = Color(0xFFEBEEF4),
    surfaceContainerHigh = Color(0xFFE5E8EF),
    surfaceContainerHighest = Color(0xFFDFE3E9),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7CC8FF), onPrimary = Color(0xFF003354),
    primaryContainer = Color(0xFF004A77), onPrimaryContainer = Color(0xFFCFE5FF),
    secondary = Color(0xFF56DBC0), onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF005142), onSecondaryContainer = Color(0xFF76F8DC),
    tertiary = Color(0xFFFFB870), onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00), onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0E131A), onBackground = Color(0xFFE1E6EE),
    surface = Color(0xFF0E131A), onSurface = Color(0xFFE1E6EE),
    surfaceVariant = Color(0xFF414852), onSurfaceVariant = Color(0xFFC1C7D2),
    surfaceContainerLowest = Color(0xFF090D13),
    surfaceContainerLow = Color(0xFF151B23),
    surfaceContainer = Color(0xFF19202A),
    surfaceContainerHigh = Color(0xFF232B36),
    surfaceContainerHighest = Color(0xFF2E3642),
    outline = Color(0xFF8B919C),
    outlineVariant = Color(0xFF414852),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

fun corewatchColorScheme(dark: Boolean): ColorScheme = if (dark) DarkScheme else LightScheme

/** Crisp tabular monospace styles (`tnum`) so live telemetry digits never jitter horizontally. */
object CorewatchMonoTypography {
    val hudValue = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    )
    val hudSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = "tnum",
    )
    val metricLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontFeatureSettings = "tnum",
    )
}

private val AppTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun CorewatchTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes) {
        CompositionLocalProvider(
            LocalContentColor provides scheme.onSurface,
            LocalIsDarkTheme provides dark,
            content = content,
        )
    }
}
