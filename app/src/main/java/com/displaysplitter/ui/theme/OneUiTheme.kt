package com.displaysplitter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One UI 6/7 palette. Samsung's system font isn't redistributable, so metrics and
 * weights are tuned to read like One UI while rendering in Roboto.
 */
object OneUiPalette {
    val Blue = Color(0xFF0381FE)
    val BlueDark = Color(0xFF3E91FF)

    val BackgroundLight = Color(0xFFF6F6F6)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceVariantLight = Color(0xFFF0F0F0)
    val OnSurfaceLight = Color(0xFF010101)
    val OnSurfaceVariantLight = Color(0xFF8C8C8C)
    val TrackInactiveLight = Color(0xFFD9D9D9)
    val DividerLight = Color(0xFFEBEBEB)

    val BackgroundDark = Color(0xFF010101)
    val SurfaceDark = Color(0xFF171719)
    val SurfaceVariantDark = Color(0xFF252528)
    val OnSurfaceDark = Color(0xFFFAFAFA)
    val OnSurfaceVariantDark = Color(0xFF9B9B9F)
    val TrackInactiveDark = Color(0xFF3A3A3E)
    val DividerDark = Color(0xFF2A2A2E)

    val Warning = Color(0xFFF57C00)
}

/** Resolved per-theme colors used across screens and the overlay panel. */
data class OneUiColorSet(
    val Primary: Color,
    val Background: Color,
    val Surface: Color,
    val SurfaceVariant: Color,
    val OnSurface: Color,
    val OnSurfaceVariant: Color,
    val TrackInactive: Color,
    val Divider: Color,
    val PanelBackground: Color,
    val Warning: Color,
)

val LightOneUiColors = OneUiColorSet(
    Primary = OneUiPalette.Blue,
    Background = OneUiPalette.BackgroundLight,
    Surface = OneUiPalette.SurfaceLight,
    SurfaceVariant = OneUiPalette.SurfaceVariantLight,
    OnSurface = OneUiPalette.OnSurfaceLight,
    OnSurfaceVariant = OneUiPalette.OnSurfaceVariantLight,
    TrackInactive = OneUiPalette.TrackInactiveLight,
    Divider = OneUiPalette.DividerLight,
    PanelBackground = OneUiPalette.SurfaceLight,
    Warning = OneUiPalette.Warning,
)

val DarkOneUiColors = OneUiColorSet(
    Primary = OneUiPalette.BlueDark,
    Background = OneUiPalette.BackgroundDark,
    Surface = OneUiPalette.SurfaceDark,
    SurfaceVariant = OneUiPalette.SurfaceVariantDark,
    OnSurface = OneUiPalette.OnSurfaceDark,
    OnSurfaceVariant = OneUiPalette.OnSurfaceVariantDark,
    TrackInactive = OneUiPalette.TrackInactiveDark,
    Divider = OneUiPalette.DividerDark,
    PanelBackground = OneUiPalette.SurfaceDark,
    Warning = OneUiPalette.Warning,
)

private val LocalOneUiColors = staticCompositionLocalOf { LightOneUiColors }

private fun oneUiColorScheme(dark: Boolean): ColorScheme {
    val c = if (dark) DarkOneUiColors else LightOneUiColors
    return if (dark) {
        darkColorScheme(
            primary = c.Primary,
            background = c.Background,
            surface = c.Surface,
            surfaceVariant = c.SurfaceVariant,
            onSurface = c.OnSurface,
            onSurfaceVariant = c.OnSurfaceVariant,
            onPrimary = Color.White,
        )
    } else {
        lightColorScheme(
            primary = c.Primary,
            background = c.Background,
            surface = c.Surface,
            surfaceVariant = c.SurfaceVariant,
            onSurface = c.OnSurface,
            onSurfaceVariant = c.OnSurfaceVariant,
            onPrimary = Color.White,
        )
    }
}

private val OneUiTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
)

/** Works in any window's composition, including the overlay's own ComposeView:
 *  isSystemInDarkTheme() reads that window's configuration. */
@Composable
fun OneUiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkOneUiColors else LightOneUiColors
    CompositionLocalProvider(LocalOneUiColors provides colors) {
        MaterialTheme(
            colorScheme = oneUiColorScheme(dark),
            typography = OneUiTypography,
            content = content,
        )
    }
}

val MaterialTheme.oneUi: OneUiColorSet
    @Composable get() = LocalOneUiColors.current
