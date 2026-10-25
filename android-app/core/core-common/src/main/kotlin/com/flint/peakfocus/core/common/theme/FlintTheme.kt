package com.flint.peakfocus.core.common.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.R

/**
 * Flint's "Ember & Mist" palette. Mirror of design/tokens.json — keep in sync
 * (tokens.json is the single source; this file is its hand-maintained Compose binding).
 * One night palette for every screen; there is no light variant.
 */
object FlintPalette {
    val Night = Color(0xFF0C0E13)
    val Ground = Color(0xFF0E0D0F)
    val Sheet = Color(0xFF151417)
    val Ridge = Color(0xFF2C2522)
    val Dusk = Color(0xFF4B3322)
    val Bone = Color(0xFFEDE6DA)
    val Mist = Color(0xFFB7AE9F)
    val Stone = Color(0xFF9C9486)
    val Ember = Color(0xFFEF9F27)
    val Glow = Color(0xFFFFE7C2)
    val Flame = Color(0xFFF7BE6A)
    val SparkHighlight = Color(0xFFFAC775)
    val EmberTop = Color(0xFFF6B860)
    val OnEmber = Color(0xFF3A2003)
    val OnBone = Color(0xFF141316)
    val Knob = Color(0xFFFFF6E8)
    val Faint = Color(0xFF8F887C)
    val SoftBone = Color(0xFFD8CFC0)
    val Segment = Color(0xFF2A2826)
    val Danger = Color(0xFFE58A7C)
    val PassText = Color(0xFFF4C27E)

    // Bone at the token alphas, pre-composited over Ground so Material slots get solid colors.
    val BoneFaint = Color(0xFF151416)   // 0.03 — quiet card fill
    val BoneTrack = Color(0xFF191819)   // 0.05 — segmented track
    val BoneFill = Color(0xFF1B1A1B)    // 0.06 — chip fill
    val Hairline = Color(0xFF201E1F)    // 0.08 — row dividers
    val Divider = Color(0xFF242323)     // 0.10 — card borders
    val SwitchOff = Color(0xFF2D2B2B)   // 0.14 — switch track off
    val Border = Color(0xFF32302F)      // 0.16 — chip / ghost borders
    val EmberWash = Color(0xFF291F12)   // ember 0.12 — selected card fill
}

// surfaceTint == surface on purpose: tonal elevation must not wash amber over surfaces —
// depth comes from the explicit bone-alpha ladder, never from tint.
private val EmberMist = darkColorScheme(
    primary = FlintPalette.Ember,
    onPrimary = FlintPalette.OnEmber,
    primaryContainer = FlintPalette.EmberWash,
    onPrimaryContainer = FlintPalette.PassText,
    inversePrimary = FlintPalette.Ember,
    secondary = FlintPalette.Ember,
    onSecondary = FlintPalette.OnEmber,
    // Selected chips and segments read secondaryContainer: bone with dark text, per the design.
    secondaryContainer = FlintPalette.Bone,
    onSecondaryContainer = FlintPalette.OnBone,
    tertiary = FlintPalette.SparkHighlight,
    onTertiary = FlintPalette.OnEmber,
    background = FlintPalette.Ground,
    onBackground = FlintPalette.Bone,
    surface = FlintPalette.Ground,
    onSurface = FlintPalette.Bone,
    surfaceVariant = FlintPalette.BoneFill,
    onSurfaceVariant = FlintPalette.Mist,
    surfaceTint = FlintPalette.Ground,
    surfaceBright = FlintPalette.Segment,
    surfaceDim = FlintPalette.Night,
    surfaceContainerLowest = FlintPalette.Night,
    surfaceContainerLow = FlintPalette.BoneFaint,
    surfaceContainer = FlintPalette.Sheet,
    surfaceContainerHigh = FlintPalette.BoneFill,
    surfaceContainerHighest = FlintPalette.SwitchOff,
    inverseSurface = FlintPalette.Bone,
    inverseOnSurface = FlintPalette.OnBone,
    error = FlintPalette.Danger,
    onError = FlintPalette.OnBone,
    errorContainer = Color(0xFF3A1F1B),
    onErrorContainer = Color(0xFFF3C1BA),
    outline = FlintPalette.Border,
    outlineVariant = FlintPalette.Divider,
)

/** Shippori Mincho 400: titles, timers, numbers. Digits are fixed-width in the bundled cut. */
val FlintDisplay = FontFamily(Font(R.font.shippori_mincho_regular, FontWeight.Normal))

/** Hanken Grotesk 400/500/600: body, rows, buttons, eyebrows. */
val FlintText = FontFamily(
    Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
    Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
    Font(R.font.hanken_grotesk_semibold, FontWeight.SemiBold),
)

/**
 * tokens.json type roles on Material3 slots. Display/headline/titleLarge are the serif
 * (timers 84/68/50, titles 34/30/26, numbers 22); everything smaller is Hanken Grotesk.
 * labelMedium is the eyebrow (11/500/+2 — callers uppercase the string).
 */
private fun display(size: Int, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = FlintDisplay, fontWeight = FontWeight.Normal,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
)

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
    fontFamily = FlintText, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
)

val FlintTypography = Typography(
    displayLarge = display(84, 88, -2.0),
    displayMedium = display(68, 72, -1.5),
    displaySmall = display(50, 56, -0.8),
    headlineLarge = display(34, 41, -0.3),
    headlineMedium = display(30, 37, -0.3),
    headlineSmall = display(26, 32),
    titleLarge = display(22, 28),
    titleMedium = text(16, 22, FontWeight.Medium),
    titleSmall = text(15, 20, FontWeight.Medium),
    bodyLarge = text(16, 24),
    bodyMedium = text(15, 22),
    bodySmall = text(13, 19),
    labelLarge = text(16, 20, FontWeight.SemiBold),
    labelMedium = text(11, 16, FontWeight.Medium, tracking = 2.0),
    labelSmall = text(11, 14, FontWeight.Medium),
)

/**
 * Merge into any style that renders durations or counts (`style.merge(FlintNumerals)`):
 * tabular figures keep a live countdown from jittering as digits change.
 */
val FlintNumerals = TextStyle(fontFeatureSettings = "tnum")

/** tokens.json radius scale: tiles 10, cards 20, sheets and dialogs 32; buttons stay pills. */
val FlintShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** tokens.json spacing scale. Screen gutter 24, editor gutter 20, gap between cards 12. */
object FlintSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val gutter = 24.dp
    val gutterEditor = 20.dp
    val cardGap = 12.dp
}

/**
 * tokens.json motion vocabulary — every tween/AnimatedContent references these, never inline.
 * Timed transitions (crossfades, reveals, push/pop) use the tween tokens; physical responses
 * (press-scale, list item placement) use [SpringSettle] so they track the gesture, not a clock.
 * The ambient cycles (ember glow, breath, mist drift) stop under reduced motion.
 */
object FlintMotion {
    const val DurationShort = 150
    const val DurationMedium = 300
    const val DurationLong = 450
    const val GlowCycle = 8_000
    const val BreathCycle = 8_000
    const val MistDrift = 22_000
    val EasingEmphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EasingStandard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val SpringSettle: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
    const val PressedScale = 0.98f
}

/**
 * Flint's Material3 theme: Ember & Mist, dark only. [darkTheme] is accepted for source
 * compatibility with older call sites and ignored.
 */
@Composable
fun FlintTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = EmberMist,
        typography = FlintTypography,
        shapes = FlintShapes,
        content = content,
    )
}
