package com.flint.peakfocus.core.common.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Flint brand palette + semantic values. Mirror of design/tokens.json — keep in sync
 * (tokens.json is the single source; this file is its hand-maintained Compose binding).
 */
object FlintPalette {
    // Brand palette
    val Flint = Color(0xFF2C2C2A)
    val Graphite = Color(0xFF5F5E5A)
    val Spark = Color(0xFFEF9F27)
    val Ember = Color(0xFFFAC775)
    val Bronze = Color(0xFFBA7517)
    val Stone = Color(0xFFF1EFE8)
    val Ash = Color(0xFFB4B2A9)
    val OnAccent = Color(0xFF412402)

    // Semantic — light
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceVariantLight = Color(0xFFE7E5DC)
    val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
    val SurfaceContainerLowLight = Color(0xFFFAF9F4)
    val SurfaceContainerLight = Color(0xFFF5F3EC)
    val SurfaceContainerHighLight = Color(0xFFE7E5DC)
    val SurfaceContainerHighestLight = Color(0xFFDDDBD0)
    val BorderLight = Color(0xFFD3D1C7)
    val BorderVariantLight = Color(0xFFE0DED3)
    val DangerLight = Color(0xFFC0392B)
    val DangerContainerLight = Color(0xFFF6DAD5)
    val OnDangerContainerLight = Color(0xFF6E1F16)

    // Semantic — dark
    val SurfaceDark = Color(0xFF3A3A37)
    val SurfaceVariantDark = Color(0xFF46453F)
    val SurfaceContainerLowestDark = Color(0xFF262624)
    val SurfaceContainerLowDark = Color(0xFF323230)
    val SurfaceContainerDark = Color(0xFF3A3A37)
    val SurfaceContainerHighDark = Color(0xFF42423E)
    val SurfaceContainerHighestDark = Color(0xFF4A4A44)
    val BorderDark = Color(0xFF5F5E5A)
    val BorderVariantDark = Color(0xFF514F49)
    val DangerDark = Color(0xFFE07A6E)
    val DangerContainerDark = Color(0xFF5C2A23)
    val OnDangerContainerDark = Color(0xFFF3C1BA)
}

// surfaceTint == surface on purpose (both schemes): tonal elevation must not wash amber over
// surfaces — Flint's depth comes from the explicit surfaceContainer ladder, never from tint.
private val DarkColors = darkColorScheme(
    primary = FlintPalette.Spark,
    onPrimary = FlintPalette.OnAccent,
    primaryContainer = FlintPalette.Ember,
    onPrimaryContainer = FlintPalette.OnAccent,
    inversePrimary = FlintPalette.Bronze,
    secondary = FlintPalette.Ember,
    onSecondary = FlintPalette.OnAccent,
    // tokens.json selectedContainer — FilterChip/Segmented selected states read these; left
    // unmapped they render M3's baseline lavender (caught live on the emulator).
    secondaryContainer = FlintPalette.Ember,
    onSecondaryContainer = FlintPalette.OnAccent,
    tertiary = FlintPalette.Bronze,
    onTertiary = FlintPalette.Stone,
    background = FlintPalette.Flint,
    onBackground = FlintPalette.Stone,
    surface = FlintPalette.SurfaceDark,
    onSurface = FlintPalette.Stone,
    surfaceVariant = FlintPalette.SurfaceVariantDark,
    onSurfaceVariant = FlintPalette.Ash,
    surfaceTint = FlintPalette.SurfaceDark,
    surfaceContainerLowest = FlintPalette.SurfaceContainerLowestDark,
    surfaceContainerLow = FlintPalette.SurfaceContainerLowDark,
    surfaceContainer = FlintPalette.SurfaceContainerDark,
    surfaceContainerHigh = FlintPalette.SurfaceContainerHighDark,
    surfaceContainerHighest = FlintPalette.SurfaceContainerHighestDark,
    inverseSurface = FlintPalette.Stone,
    inverseOnSurface = FlintPalette.Flint,
    error = FlintPalette.DangerDark,
    onError = FlintPalette.Flint,
    errorContainer = FlintPalette.DangerContainerDark,
    onErrorContainer = FlintPalette.OnDangerContainerDark,
    outline = FlintPalette.BorderDark,
    outlineVariant = FlintPalette.BorderVariantDark,
)

private val LightColors = lightColorScheme(
    primary = FlintPalette.Spark,
    onPrimary = FlintPalette.OnAccent,
    primaryContainer = FlintPalette.Ember,
    onPrimaryContainer = FlintPalette.OnAccent,
    inversePrimary = FlintPalette.Ember,
    secondary = FlintPalette.Bronze,
    onSecondary = FlintPalette.Stone,
    secondaryContainer = FlintPalette.Ember,
    onSecondaryContainer = FlintPalette.OnAccent,
    tertiary = FlintPalette.Bronze,
    onTertiary = FlintPalette.Stone,
    background = FlintPalette.Stone,
    onBackground = FlintPalette.Flint,
    surface = FlintPalette.SurfaceLight,
    onSurface = FlintPalette.Flint,
    surfaceVariant = FlintPalette.SurfaceVariantLight,
    onSurfaceVariant = FlintPalette.Graphite,
    surfaceTint = FlintPalette.SurfaceLight,
    surfaceContainerLowest = FlintPalette.SurfaceContainerLowestLight,
    surfaceContainerLow = FlintPalette.SurfaceContainerLowLight,
    surfaceContainer = FlintPalette.SurfaceContainerLight,
    surfaceContainerHigh = FlintPalette.SurfaceContainerHighLight,
    surfaceContainerHighest = FlintPalette.SurfaceContainerHighestLight,
    inverseSurface = FlintPalette.Flint,
    inverseOnSurface = FlintPalette.Stone,
    error = FlintPalette.DangerLight,
    onError = FlintPalette.SurfaceLight,
    errorContainer = FlintPalette.DangerContainerLight,
    onErrorContainer = FlintPalette.OnDangerContainerLight,
    outline = FlintPalette.BorderLight,
    outlineVariant = FlintPalette.BorderVariantLight,
)

/**
 * tokens.json's five-role type ramp bound onto Material3 slots (remaining slots keep M3
 * defaults): title→headlineMedium 28/600, heading→titleLarge 20/600, body→bodyLarge 16/400,
 * caption→bodySmall 13/400, label→labelMedium 11/500 +0.7 tracking (the uppercase eyebrow —
 * callers uppercase the string; Compose has no text-transform).
 */
private val BaseType = Typography()
val FlintTypography = BaseType.copy(
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = BaseType.bodyLarge.copy(fontSize = 16.sp),
    bodySmall = BaseType.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
    labelMedium = BaseType.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.7.sp,
        lineHeight = 16.sp,
    ),
)

/**
 * Merge into any style that renders durations or counts (`style.merge(FlintNumerals)`):
 * tabular figures keep a live countdown from jittering as digits change.
 */
val FlintNumerals = TextStyle(fontFeatureSettings = "tnum")

/** tokens.json radius scale. Cards use [Shapes.large]; pills stay RoundedCornerShape(percent = 50). */
val FlintShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
)

/** tokens.json spacing scale. Screen gutter 20, card padding md, gap between cards 12. */
object FlintSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val gutter = 20.dp
    val cardGap = 12.dp
}

/**
 * tokens.json motion vocabulary — every tween/AnimatedContent references these, never inline.
 * Timed transitions (crossfades, reveals, push/pop) use the tween tokens; physical responses
 * (press-scale, list item placement) use [SpringSettle] so they track the gesture, not a clock.
 */
object FlintMotion {
    const val DurationShort = 150
    const val DurationMedium = 300
    const val DurationLong = 450
    val EasingEmphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EasingStandard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val SpringSettle: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    const val PressedScale = 0.98f
}

/** Flint's Material3 theme. Dark is the brand-primary look. */
@Composable
fun FlintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = FlintTypography,
        shapes = FlintShapes,
        content = content,
    )
}
