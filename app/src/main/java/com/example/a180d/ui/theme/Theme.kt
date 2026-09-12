@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.example.a180d.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.a180d.R
import com.example.a180d.ThemeMode

/** Numerals and headings — geometric, distinctive; pairs with [ManropeFamily] for body/UI text. */
val SpaceGroteskFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.space_grotesk, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.space_grotesk, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

val ManropeFamily = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val AppTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = ManropeFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = ManropeFamily),
        bodySmall = base.bodySmall.copy(fontFamily = ManropeFamily),
        labelLarge = base.labelLarge.copy(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = ManropeFamily, fontWeight = FontWeight.SemiBold),
    )
}

/** Big hero numerals (the live BPM readout) sit outside the type scale. */
val HeroNumberStyle = TextStyle(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight.Bold, fontSize = 76.sp)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1B1230),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1B1230),
    background = Color(0xFF101317),
    onBackground = Color(0xFFF3F1EC),
    surface = Color(0xFF181C22),
    onSurface = Color(0xFFF3F1EC),
    surfaceVariant = Color(0xFF1F242C),
    onSurfaceVariant = Color(0xFFF3F1EC),
    outline = Color(0x12FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFEF5350),
    onError = Color(0xFF3B0A08),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF7C3AED),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F6F3),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFFBFAF8),
    onSurfaceVariant = Color(0xFF1C1B19),
    outline = Color(0x14000000),
    outlineVariant = Color(0x1F000000),
    error = Color(0xFFD32F2F),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun AppTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (useDarkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}
