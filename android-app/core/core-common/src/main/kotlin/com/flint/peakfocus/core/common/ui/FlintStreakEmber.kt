package com.flint.peakfocus.core.common.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintPalette
import kotlin.math.PI
import kotlin.math.sin

/** Visual mood for the streak ember — mapped from streak lifecycle only. */
enum class StreakEmberMood {
    Active,
    Warning,
    Dormant,
}

internal data class EmberPalette(
    val coreHot: Color,
    val coreMid: Color,
    val coreDeep: Color,
    val shellTop: Color,
    val shellMid: Color,
    val shellBase: Color,
    val rim: Color,
    val halo: Color,
    val hasFire: Boolean,
)

internal fun paletteFor(mood: StreakEmberMood): EmberPalette = when (mood) {
    // Glass-drop flame — milky shell, glowing rim, fire core.
    StreakEmberMood.Active -> EmberPalette(
        coreHot = Color(0xFFFFF6D6),
        coreMid = Color(0xFFFFAE3D),
        coreDeep = Color(0xFFF06414),
        shellTop = Color(0x4DFFF3DC),
        shellMid = Color(0x94FFF0CE),
        shellBase = Color(0xF2FFE7C2),
        rim = Color(0xFFFFFDF6),
        halo = Color(0xFFF5A623),
        hasFire = true,
    )
    StreakEmberMood.Warning -> EmberPalette(
        // Earlier this was a red/rose warning tint. At-risk now reads as heat leaving
        // the flame: warm amber chrome, no red anywhere in the sheet.
        coreHot = Color(0xFFFFE3B8),
        coreMid = Color(0xFFF2A24A),
        coreDeep = Color(0xFFB2652A),
        shellTop = Color(0x4DFFE8C8),
        shellMid = Color(0x94F6DDBB),
        shellBase = Color(0xF2EBCB9E),
        rim = Color(0xFFFFF3DF),
        halo = Color(0xFFE08A34),
        hasFire = true,
    )
    StreakEmberMood.Dormant -> EmberPalette(
        coreHot = Color(0xFFF2F2F4),
        coreMid = Color(0xFFE6E6EA),
        coreDeep = Color(0xFFDBDBDE),
        shellTop = Color(0x4DF2F2F4),
        shellMid = Color(0x94E6E6EA),
        shellBase = Color(0xE6DBDBDE),
        rim = Color(0xFFFFFFFF),
        halo = Color(0xFFB7AE9F),
        hasFire = false,
    )
}

/**
 * Glass-drop streak flame — plump droplet with licked edges, milky shell,
 * glowing rim and fire core. Canvas only; no emoji or art.
 *
 * Performance: animation state updates at 30fps (glow breathing needs no more),
 * gradient stop arrays are remembered per mood, and the frame paths are
 * reused — per-frame work is path rebuilds plus draws, with no recomposition
 * (state is read in draw).
 */
@Composable
fun FlintStreakEmber(
    mood: StreakEmberMood,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    milestoneFlash: Float = 0f,
) {
    // One renderer for every placement: the reference silhouette lives in StreakFlame.
    // This wrapper only keeps the existing mood-based call sites and silent semantics.
    StreakFlame(
        state = mood.toFlameState(),
        streakCount = 0,
        modifier = modifier,
        size = size,
        contentDescription = null,
        milestoneFlash = milestoneFlash,
    )
}

/**
 * Quiet cinematic stage for streak sheets — deep void, soft edge mist, sparse dust.
 * No hard rectangle, no oversized circular outline.
 *
 * Deliberately still. The sheet already runs the flame's 30 fps clock, and a second
 * 12 fps full-screen loop only doubled wake-ups for a barely visible drift. The stage
 * is now a static backdrop, so each sheet has exactly one animation clock.
 */
@Composable
fun FlintStreakCinematicStage(
    mood: StreakEmberMood,
    modifier: Modifier = Modifier,
) {
    val colors = remember(mood) { paletteFor(mood) }
    // Fixed layout phase: the dust field is composed once and never redrawn.
    val phase = 0.35f

    val dust = remember {
        List(12) { i ->
            DustSeed(
                x = ((i * 73) % 100) / 100f,
                y = ((i * 47) % 100) / 100f,
                r = 0.6f + (i % 3) * 0.35f,
                speed = 0.06f + (i % 4) * 0.015f,
                lag = i * 0.12f,
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize().clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val void = Color(0xFF050506)
        val edge = FlintPalette.BoneFaint
        drawRect(void)
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to edge,
                    0.12f to edge.copy(alpha = 0.45f),
                    0.28f to void.copy(alpha = 0f),
                    0.72f to void.copy(alpha = 0f),
                    0.88f to edge.copy(alpha = 0.45f),
                    1.00f to edge,
                ),
            ),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to edge.copy(alpha = 0.30f),
                    0.20f to void.copy(alpha = 0f),
                    0.80f to void.copy(alpha = 0f),
                    1.00f to edge.copy(alpha = 0.35f),
                ),
            ),
        )

        // Soft stone masses — restrained, out-of-focus.
        drawRockMass(Offset(w * -0.05f, h * 0.40f), w * 0.34f, h * 0.90f, phase, 0.85f)
        drawRockMass(Offset(w * 1.05f, h * 0.48f), w * 0.36f, h * 0.95f, phase + 0.35f, 0.85f)

        val glowA = when (mood) {
            StreakEmberMood.Active -> 0.11f
            StreakEmberMood.Warning -> 0.09f
            StreakEmberMood.Dormant -> 0.04f
        }
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to colors.halo.copy(alpha = glowA),
                    0.45f to colors.coreMid.copy(alpha = glowA * 0.30f),
                    1.00f to colors.halo.copy(alpha = 0f),
                ),
                center = Offset(w * 0.50f, h * 0.36f),
                radius = minOf(w, h) * 0.55f,
            ),
            radius = minOf(w, h) * 0.55f,
            center = Offset(w * 0.50f, h * 0.36f),
        )

        dust.forEach { d ->
            val t = ((phase * d.speed) + d.lag) % 1f
            val x = d.x * w + sin((phase + d.lag) * 2f * PI.toFloat()) * w * 0.008f
            val y = ((d.y + t * 0.18f) % 1f) * h
            val a = when {
                t < 0.2f -> t / 0.2f
                t > 0.8f -> (1f - t) / 0.2f
                else -> 1f
            } * 0.22f * if (mood == StreakEmberMood.Dormant) 0.4f else 0.65f
            if (a > 0.02f) {
                drawCircle(color = colors.shellMid.copy(alpha = a), radius = d.r, center = Offset(x, y))
            }
        }
    }
}

private data class DustSeed(val x: Float, val y: Float, val r: Float, val speed: Float, val lag: Float)

private fun DrawScope.drawRockMass(
    anchor: Offset,
    spanX: Float,
    spanY: Float,
    phase: Float,
    alphaScale: Float,
) {
    // Rock masses sit still — their shimmer came free with the static palette.
    val wobble = 0f
    for (i in 0..4) {
        val ox = anchor.x + (i - 2f) * spanX * 0.10f + wobble
        val oy = anchor.y + (i % 3 - 1) * spanY * 0.09f
        val rx = spanX * (0.30f + i * 0.07f)
        val ry = spanY * (0.24f + (4 - i) * 0.05f)
        val a = (0.32f - i * 0.04f) * alphaScale
        drawOval(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.00f to FlintPalette.Ridge.copy(alpha = a),
                    0.55f to FlintPalette.Mist.copy(alpha = 0.06f * alphaScale),
                    1.00f to FlintPalette.Mist.copy(alpha = 0f),
                ),
                center = Offset(ox, oy),
                radius = rx,
            ),
            topLeft = Offset(ox - rx, oy - ry),
            size = Size(rx * 2f, ry * 2f),
        )
    }
}

object FocusStreakSheetCopy {
    const val DAY_STREAK = "Day streak"
    const val CONTINUE = "Continue"
    const val SUBTITLE = "Days in a row you focused with Flint"

    fun motivation(mood: StreakEmberMood, streak: Int): String = when (mood) {
        StreakEmberMood.Active -> if (streak >= 7) {
            "Quiet consistency. Return tomorrow."
        } else {
            "One completed day at a time."
        }
        StreakEmberMood.Warning -> "Your streak still holds. Finish a focus session today."
        StreakEmberMood.Dormant -> "A completed focus session lights the first day."
    }

    fun moodFrom(streak: Int, completedToday: Boolean): StreakEmberMood = when {
        streak <= 0 -> StreakEmberMood.Dormant
        completedToday -> StreakEmberMood.Active
        else -> StreakEmberMood.Warning
    }
}
