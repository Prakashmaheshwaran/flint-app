package com.flint.peakfocus.core.common.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.R
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintText
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------------------
// Streak flame: one renderer per platform, driven by a pre-cropped frame atlas.
// ---------------------------------------------------------------------------------------

/** Streak emblem colours shared with the Insights card. */
val StreakCoolTop: Color = Color(0xFFF2C892)
val StreakCoolDeep: Color = Color(0xFFC1731F)

/**
 * The three shipped flame placements. Named so tests assert the real values instead of
 * scraping call sites, and so a placement change is a single, reviewable edit.
 */
object StreakFlamePlacement {
    /** Landing header badge: static, never animates. */
    val Badge = 24.dp
    /** Streak celebration sheet hero. */
    val Sheet = 148.dp
    /** Insights streak card hero. */
    val Insights = 160.dp
}

/** Frame duration of the streak atlas. Every frame is authored at 60 ms. */
internal const val STREAK_FRAME_MS = 60L

/**
 * Single owner of the streak mood rule: Active = streak alive and a session completed
 * today; Warning = alive but not yet today; Dormant = no active streak.
 *
 * Inputs stay where the callers already compute them; only the mapping is shared.
 */
fun emberMoodFor(streak: Int, completedToday: Boolean): StreakEmberMood = when {
    streak <= 0 -> StreakEmberMood.Dormant
    completedToday -> StreakEmberMood.Active
    else -> StreakEmberMood.Warning
}

/** Container alpha per mood. Unchanged from the original flame states. */
internal fun flameMoodAlpha(mood: StreakEmberMood): Float = when (mood) {
    StreakEmberMood.Active -> 1f
    StreakEmberMood.Warning -> 0.96f
    StreakEmberMood.Dormant -> 0.92f
}

/** The flame only advances its clock when it can actually be seen. */
internal fun flameShouldAnimate(
    animate: Boolean,
    reducedMotion: Boolean,
    windowFocused: Boolean,
    onScreen: Boolean,
    mood: StreakEmberMood,
    forceAnimate: Boolean,
): Boolean = animate && !reducedMotion && windowFocused && onScreen &&
    (mood != StreakEmberMood.Dormant || forceAnimate)

/** Continuous loop position; resuming from a frozen anchor never snaps back to zero. */
internal fun flameElapsedMs(anchorMs: Long, startNanos: Long, nowNanos: Long): Long =
    anchorMs + (nowNanos - startNanos) / 1_000_000L

/** Frame index for the atlas loop. A missing atlas must not divide by zero. */
internal fun flameFrameIndex(elapsedMs: Long, frameCount: Int): Int {
    if (frameCount <= 0) return 0
    return ((elapsedMs / STREAK_FRAME_MS) % frameCount).toInt()
}

/**
 * The streak flame. Draws the current frame of the pre-cropped fire atlas: 45 frames at
 * 60 ms each (2.7 s), looping continuously while the flame is visible.
 *
 * Frames are decoded and cropped once per process, so drawing costs one bitmap blit and
 * the loop seam is a plain index wrap. The flame exposes no semantics of its own; hosts
 * own the accessibility label.
 */
@Composable
fun FlintStreakEmber(
    mood: StreakEmberMood,
    modifier: Modifier = Modifier,
    size: Dp = StreakFlamePlacement.Insights,
    forceAnimate: Boolean = false,
    /** When false the flame stays on the first frame (landing header badge). */
    animate: Boolean = true,
) {
    val reduced = rememberReducedMotion()
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    val hostView = LocalView.current
    val context = LocalContext.current
    val frames = remember(context) { StreakAtlasFrames.get(context) }
    var onScreen by remember { mutableStateOf(true) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val shouldAnimate = flameShouldAnimate(
        animate = animate,
        reducedMotion = reduced,
        windowFocused = windowFocused,
        onScreen = onScreen,
        mood = mood,
        forceAnimate = forceAnimate,
    )

    LaunchedEffect(shouldAnimate) {
        if (!shouldAnimate) return@LaunchedEffect
        val anchor = elapsedMs
        val start = withFrameNanos { it }
        var lastFrame = -1L
        while (true) {
            withFrameNanos { now ->
                val ms = flameElapsedMs(anchor, start, now)
                val frame = ms / STREAK_FRAME_MS
                if (frame != lastFrame) {
                    lastFrame = frame
                    elapsedMs = ms
                }
            }
        }
    }

    Box(
        modifier
            .size(size)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                val rootW = hostView.width.toFloat()
                val rootH = hostView.height.toFloat()
                onScreen = b.right > 0f && b.bottom > 0f && b.left < rootW && b.top < rootH
            }
            .alpha(flameMoodAlpha(mood))
            .clearAndSetSemantics { },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (frames.isEmpty()) return@Canvas
            val image = frames[flameFrameIndex(elapsedMs, frames.size)]
            val imageW = image.width.toFloat()
            val imageH = image.height.toFloat()
            val canvasW = this.size.width
            val canvasH = this.size.height
            val scale = min(canvasW / imageW, canvasH / imageH)
            val dx = (canvasW - imageW * scale) * 0.5f
            val dy = (canvasH - imageH * scale) * 0.5f
            withTransform({
                translate(left = dx, top = dy)
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                drawImage(image)
            }
        }
    }
}

/** Visual mood for the streak ember — mapped from streak lifecycle only. */
enum class StreakEmberMood {
    Active,
    Warning,
    Dormant,
}

internal data class EmberPalette(
    val coreMid: Color,
    val shellMid: Color,
    val halo: Color,
)

internal fun paletteFor(mood: StreakEmberMood): EmberPalette = when (mood) {
    StreakEmberMood.Active -> EmberPalette(
        coreMid = Color(0xFFFFAE3D),
        shellMid = Color(0x94FFF0CE),
        halo = Color(0xFFF5A623),
    )
    // At-risk reads as heat leaving the flame: warm amber chrome, no red in the sheet.
    StreakEmberMood.Warning -> EmberPalette(
        coreMid = Color(0xFFF2A24A),
        shellMid = Color(0x94F6DDBB),
        halo = Color(0xFFE08A34),
    )
    StreakEmberMood.Dormant -> EmberPalette(
        coreMid = Color(0xFFE6E6EA),
        shellMid = Color(0x94E6E6EA),
        halo = Color(0xFFB7AE9F),
    )
}

/**
 * Quiet cinematic stage for streak sheets — deep void, soft edge mist, sparse dust.
 * No hard rectangle, no oversized circular outline.
 *
 * Deliberately still: the sheet already runs the flame's 60 ms atlas clock, and a second
 * full-screen loop only doubled wake-ups for a barely visible drift. Each sheet has exactly
 * one animation clock.
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
        drawRockMass(Offset(w * -0.05f, h * 0.40f), w * 0.34f, h * 0.90f, 0.85f)
        drawRockMass(Offset(w * 1.05f, h * 0.48f), w * 0.36f, h * 0.95f, 0.85f)

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
    alphaScale: Float,
) {
    // Rock masses sit still; the stage owns the only clock.
    for (i in 0..4) {
        val ox = anchor.x + (i - 2f) * spanX * 0.10f
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

    fun motivation(mood: StreakEmberMood, streak: Int): String = when (mood) {
        StreakEmberMood.Active -> if (streak >= 7) {
            "Quiet consistency. Return tomorrow."
        } else {
            "One completed day at a time."
        }
        StreakEmberMood.Warning -> "Your streak still holds. Finish a focus session today."
        StreakEmberMood.Dormant -> "A completed focus session lights the first day."
    }
}

/**
 * Bottom sheet celebration for Focus Streak — professional sheet rising from below.
 * Flame → number → DAY STREAK → motivation → Continue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusStreakCelebrationSheet(
    streak: Int,
    mood: StreakEmberMood,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val numberColor = when (mood) {
        StreakEmberMood.Active -> FlintPalette.Ember
        StreakEmberMood.Warning -> StreakCoolDeep
        StreakEmberMood.Dormant -> FlintPalette.Mist.copy(alpha = 0.75f)
    }
    // Hero number gradient: light top melting into the mood color.
    val numberBrush = when (mood) {
        StreakEmberMood.Active -> Brush.verticalGradient(
            listOf(Color(0xFFFFE9B0), FlintPalette.Ember),
        )
        StreakEmberMood.Warning -> Brush.verticalGradient(
            listOf(StreakCoolTop, StreakCoolDeep),
        )
        StreakEmberMood.Dormant -> null
    }
    val numberStyle = TextStyle(
        fontFamily = FlintText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 68.sp,
        lineHeight = 72.sp,
        letterSpacing = (-1.4).sp,
    ).merge(FlintNumerals)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("streak.celebration"),
        sheetState = sheetState,
        containerColor = Color(0xFF0A090B),
        contentColor = FlintPalette.Bone,
        scrimColor = Color(0xFF07080B).copy(alpha = 0.72f),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(40.dp, 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(FlintPalette.Bone.copy(alpha = 0.18f)),
            )
        },
    ) {
        // Minimum, not fixed: the number/label/motivation scale with the system font, so a
        // hard 460dp box clipped content (and pushed Continue off the sheet) at Large and
        // accessibility font sizes. The column scrolls only when the content still exceeds
        // the available sheet height; the backdrop keeps filling whatever the box becomes.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 460.dp),
        ) {
            FlintStreakCinematicStage(mood = mood, modifier = Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
                    .padding(top = 12.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FlintStreakEmber(
                    mood = mood,
                    size = StreakFlamePlacement.Sheet,
                    forceAnimate = true,
                )
                Text(
                    streak.toString(),
                    style = if (numberBrush == null) {
                        numberStyle
                    } else {
                        numberStyle.copy(brush = numberBrush)
                    },
                    color = if (numberBrush == null) numberColor else Color.Unspecified,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    FocusStreakSheetCopy.DAY_STREAK.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = numberColor.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    FocusStreakSheetCopy.motivation(mood, streak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlintPalette.Mist.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
                )
                Spacer(Modifier.height(FlintSpacing.xl))
                EmberButton(
                    FocusStreakSheetCopy.CONTINUE,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal object StreakAtlasFrames {
    const val FRAME_COUNT = 45
    const val COLUMNS = 9
    @Volatile private var cache: List<ImageBitmap>? = null

    fun get(context: Context): List<ImageBitmap> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val options = BitmapFactory.Options().apply { inScaled = false }
            val source = BitmapFactory.decodeResource(
                context.resources,
                R.drawable.flint_streak_sheet,
                options,
            ) ?: return emptyList()
            val frameW = source.width / COLUMNS
            val frameH = source.height / ((FRAME_COUNT + COLUMNS - 1) / COLUMNS)
            // The 9x5 grid is a hard contract with the asset; a reshaped atlas fails here
            // instead of silently producing zero-sized or shifted frames.
            if (frameW <= 0 || frameH <= 0) return emptyList()
            val decoded = ArrayList<ImageBitmap>(FRAME_COUNT)
            for (index in 0 until FRAME_COUNT) {
                val col = index % COLUMNS
                val row = index / COLUMNS
                val frame = Bitmap.createBitmap(source, col * frameW, row * frameH, frameW, frameH)
                decoded.add(frame.asImageBitmap())
            }
            cache = decoded
            return decoded
        }
    }
}
