package com.flint.peakfocus.core.common.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Flint brand palette. Mirror of design/tokens.json — keep in sync. */
object FlintPalette {
    val Flint = Color(0xFF2C2C2A)
    val Graphite = Color(0xFF5F5E5A)
    val Spark = Color(0xFFEF9F27)
    val Ember = Color(0xFFFAC775)
    val Bronze = Color(0xFFBA7517)
    val Stone = Color(0xFFF1EFE8)
    val Ash = Color(0xFFB4B2A9)
    val OnAccent = Color(0xFF412402)
    val SurfaceDark = Color(0xFF3A3A37)
    val SurfaceVariantDark = Color(0xFF46453F)
    val SurfaceVariantLight = Color(0xFFE7E5DC)
}

private val DarkColors = darkColorScheme(
    primary = FlintPalette.Spark,
    onPrimary = FlintPalette.OnAccent,
    secondary = FlintPalette.Ember,
    onSecondary = FlintPalette.OnAccent,
    background = FlintPalette.Flint,
    onBackground = FlintPalette.Stone,
    surface = FlintPalette.SurfaceDark,
    onSurface = FlintPalette.Stone,
    surfaceVariant = FlintPalette.SurfaceVariantDark,
    onSurfaceVariant = FlintPalette.Ash,
)

private val LightColors = lightColorScheme(
    primary = FlintPalette.Spark,
    onPrimary = FlintPalette.OnAccent,
    secondary = FlintPalette.Bronze,
    onSecondary = FlintPalette.Stone,
    background = FlintPalette.Stone,
    onBackground = FlintPalette.Flint,
    surface = Color(0xFFFFFFFF),
    onSurface = FlintPalette.Flint,
    surfaceVariant = FlintPalette.SurfaceVariantLight,
    onSurfaceVariant = FlintPalette.Graphite,
)

/** Flint's Material3 theme. Dark is the brand-primary look. */
@Composable
fun FlintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
