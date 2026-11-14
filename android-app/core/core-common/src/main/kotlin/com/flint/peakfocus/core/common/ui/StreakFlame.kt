package com.flint.peakfocus.core.common.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Display state for the day-streak flame. */
enum class StreakFlameState {
    /** Full color, full animation. */
    Active,

    /** Same flame cooling: reduced heat, slower motion. */
    AtRisk,

    /** Cooled ash with a faint ember; no animation. */
    Inactive,
}

/** Maps the streak lifecycle mood onto the flame's display state. */
internal fun StreakEmberMood.toFlameState(): StreakFlameState = when (this) {
    StreakEmberMood.Active -> StreakFlameState.Active
    StreakEmberMood.Warning -> StreakFlameState.AtRisk
    StreakEmberMood.Dormant -> StreakFlameState.Inactive
}

/** Animation ticks never run before zero; out-of-domain time maps to tick 0. */
internal fun flameTick(elapsedSeconds: Double, fps: Double): Long =
    maxOf(0L, (elapsedSeconds * fps).toLong())

/** Breathing phase: elapsed seconds map onto 0..1 over a ~1.5s cycle. */
internal fun breathPhase01(elapsedSeconds: Double): Float =
    ((sin(elapsedSeconds * 2.0 * PI / 1.5) + 1.0) / 2.0).toFloat()

/** Flame brightness per state; inactive emits no light. */
internal fun flameIntensity(state: StreakFlameState): Float = when (state) {
    StreakFlameState.Active -> 1f
    StreakFlameState.AtRisk -> 0.9f
    StreakFlameState.Inactive -> 0f
}

/**
 * Pose used for Reduce Motion and host-rendered captures (slightly into the cycle).
 */
internal const val STATIC_FLAME_SECONDS: Double = 0.72

/** Cross-fade between streak states; one dissolve, no loop restart. */
internal const val STATE_CROSSFADE_MS: Int = 320

/**
 * Fringe stroke widths in dp, keyed to the rendered size.
 *
 * The fringe is the optical halo outside the silhouette. At badge sizes it must stay
 * near 1 dp or it swallows the contour (a constant 2.4 dp was ~10% of a 24 dp icon).
 * Hero sizes keep the fuller two-pass treatment.
 */
internal fun fringeWidthsDp(sizeDp: Float): Pair<Float, Float> = when {
    sizeDp < 48f -> 0.6f to 1.0f
    sizeDp < 120f -> 1.0f to 1.8f
    else -> 1.4f to 2.8f
}

/**
 * Flicker rate per state. At-risk runs the same motion at 0.6x so the flame reads
 * heavier; inactive never animates.
 */
internal fun flickerRate(state: StreakFlameState): Float = when (state) {
    StreakFlameState.Active -> 1f
    StreakFlameState.AtRisk -> 0.6f
    StreakFlameState.Inactive -> 0f
}

/**
 * Cycle fraction in [0, 1): the oscillator argument reduced in double precision first,
 * then converted to Float, so unbounded inputs stay exact in the reduction.
 */
internal fun reducedPhase(elapsedSeconds: Double, rate: Float, freq: Double): Float {
    val x = (elapsedSeconds / 6.0) * rate * freq
    return (((x % 1.0) + 1.0) % 1.0).toFloat()
}

/** Looping 0..1 progress of one rising ember, staggered per index. */
internal fun sparkSeed(elapsedSeconds: Double, rate: Float, index: Int): Float {
    val base = reducedPhase(elapsedSeconds, rate, 6.0 / 1.7)
    val s = (((base + index * 0.37f) % 1f) + 1f) % 1f
    return if (s >= 1f) 0f else s
}

/** Dancing tip offset: two incommensurate frequencies, never visibly loops. */
internal fun tipFlickerDx(elapsedSeconds: Double, rate: Float, halfW: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 2.1) * twoPi) * halfW * 0.05f +
        sin(reducedPhase(elapsedSeconds, rate, 3.7) * twoPi + 0.8f) * halfW * 0.02f
}

/** Slow whole-flame tilt in degrees. */
internal fun tiltDegrees(elapsedSeconds: Double, rate: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 0.9) * twoPi + 0.5f) * 1.2f
}

/** Slow vertical bob in pixels. */
internal fun bobOffset(elapsedSeconds: Double, rate: Float, unit: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 2.2) * twoPi) * unit * 0.004f
}

/** Ember brightness envelope: fades in from the base, out at the tip. */
internal fun sparkAlpha(seed: Float, intensity: Float): Float =
    sin(seed * PI.toFloat()) * 0.75f * intensity

/**
 * Loop time for one frame. The anchor carries the phase a pause froze, so resuming
 * (re-focus, scroll back, window return) continues the pose instead of snapping to
 * [STATIC_FLAME_SECONDS].
 */
internal fun frameElapsed(anchorSeconds: Double, startNanos: Long, nowNanos: Long): Double =
    anchorSeconds + (nowNanos - startNanos) / 1_000_000_000.0

/** Breathing scale: breath01 0..1 maps to 0.96..1.04 (±4% around 1.0). */
internal fun breathScale(breath01: Float): Float = 0.96f + 0.08f * breath01

/** Outer glow alpha: breath01 0..1 maps to 0.25..0.45, plus milestone flash. */
internal fun haloAlpha(breath01: Float, flash: Float): Float =
    (0.25f + 0.20f * breath01 + flash * 0.15f).coerceIn(0f, 1f)

/**
 * Reference silhouette: rendered H:W ≈ 1.32 (Pinterest reference measured 1.288) with a
 * wide fuel base and a dominant central blade.
 *
 * The contour spans ±0.45 w, so the *visible* silhouette is 0.90 w wide against a
 * 0.90 s tall body: the visible aspect is s / w, not h / w. Sizing w from h alone
 * (the old 0.693 s) drew a 1.45 flame — measurably narrower than the approved
 * 1.30–1.35 silhouette. Every other proportion (core, hot base, contact light,
 * embers) is expressed in multiples of w, so widening the scale unit keeps them intact.
 */
internal class FlameGeometry(val s: Float) {
    val w: Float = s * 0.76f
    val h: Float = s * 0.90f
    val cx: Float = s * 0.5f
    val baseY: Float = s * 0.95f
}

internal class FlameBrushSet(
    val body: Brush,
    val core: Brush,
    val bloom: Brush,
    val bloomInner: Brush,
    val contact: Brush,
    val hotBase: Brush,
    val ash: Brush,
)

/**
 * Active ramp: the reference's red-orange body (top) melting to saturated orange at
 * the fuel base. The pale yellow lives in the core, not the shell.
 */
internal val ActiveBodyStops = arrayOf(
    0.00f to Color(0xFFFB922F),
    0.34f to Color(0xFFFB7A22),
    0.70f to Color(0xFFF34415),
    1.00f to Color(0xFFC7300B),
)

/**
 * At-risk ramp: still-real amber at the base, cooling progressively upward.
 *
 * A cooling flame loses heat from the top first, so the lower body keeps saturated
 * orange while only the upper body darkens toward charcoal. This is deliberately not
 * a uniform tan wash, and contains no red warning colour.
 */
internal val AtRiskBodyStops = arrayOf(
    0.00f to Color(0xFFF6A23C),
    0.22f to Color(0xFFEA8A2B),
    0.46f to Color(0xFFCF6E1E),
    0.72f to Color(0xFFA6551C),
    0.90f to Color(0xFF7E4017),
    1.00f to Color(0xFF5C3014),
)

/**
 * Warm cooling chrome for at-risk streak text (hero number, status line), derived from
 * [AtRiskBodyStops] so the surrounding UI cannot drift back to the legacy red/rose
 * warning colour. Public because the feature modules style their own warning text.
 */
val StreakCoolTop: Color = Color(0xFFF2C892)
val StreakCoolDeep: Color = Color(0xFFC1731F)

internal fun brushSet(state: StreakFlameState, geo: FlameGeometry): FlameBrushSet {
    val body = if (state == StreakFlameState.Active) {
        Brush.verticalGradient(
            *ActiveBodyStops,
            startY = geo.baseY,
            endY = geo.baseY - geo.h,
        )
    } else {
        Brush.verticalGradient(
            *AtRiskBodyStops,
            startY = geo.baseY,
            endY = geo.baseY - geo.h,
        )
    }
    val core = if (state == StreakFlameState.Active) {
        Brush.radialGradient(
            0.00f to Color(0xFFFFF6C8),
            0.35f to Color(0xFFFFE08A),
            0.72f to Color(0xFFFDCC3B),
            1.00f to Color(0x00FB922F),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.14f),
            radius = geo.h * 0.46f,
        )
    } else {
        // Residual heat: a compact amber pocket low in the body, not a full-length core.
        Brush.radialGradient(
            0.00f to Color(0xE6FFD27E),
            0.42f to Color(0x99FFB35A),
            1.00f to Color(0x00E08A2E),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.10f),
            radius = geo.h * 0.34f,
        )
    }
    val bloomCentre = Offset(geo.cx, geo.baseY - geo.h * 0.34f)
    val bloom = Brush.radialGradient(
        0.00f to Color(0x38FF9A3C),
        0.55f to Color(0x14FF9A3C),
        1.00f to Color(0x00FF9A3C),
        center = bloomCentre,
        radius = geo.h * 1.05f,
    )
    val bloomInner = Brush.radialGradient(
        0.00f to Color(0x2EFFD27A),
        1.00f to Color(0x00FFD27A),
        center = bloomCentre,
        radius = geo.h * 0.58f,
    )
    val contact = Brush.radialGradient(
        0.00f to Color(0x1FFB922F),
        1.00f to Color(0x00FB922F),
        center = Offset(geo.cx, geo.baseY - geo.h * 0.02f),
        radius = geo.w * 0.70f,
    )
    val hotBase = if (state == StreakFlameState.Active) {
        Brush.radialGradient(
            0.00f to Color(0xFFFFFFFF),
            0.45f to Color(0xA6FFFDE6),
            1.00f to Color(0x00FFFDE6),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.08f),
            radius = geo.w * 0.24f,
        )
    } else {
        // At-risk keeps residual heat, but it is amber, never white-hot.
        Brush.radialGradient(
            0.00f to Color(0xE6FFD9A0),
            0.45f to Color(0x99FFB35A),
            1.00f to Color(0x00E08A2E),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.08f),
            radius = geo.w * 0.24f,
        )
    }
    val ash = Brush.linearGradient(
        0.00f to Color(0xFF655E56),
        0.40f to Color(0xFF3A3530),
        0.72f to Color(0xFF262220),
        1.00f to Color(0xFF151312),
        start = Offset(geo.cx, geo.baseY - geo.h * 0.32f),
        end = Offset(geo.cx, geo.baseY),
    )
    return FlameBrushSet(body, core, bloom, bloomInner, contact, hotBase, ash)
}

/**
 * Pure draw pipeline behind [StreakFlame]. Kept separate so host tests can render the exact
 * production pixels without a device or Compose UI test framework.
 */
internal class StreakFlamePainter(
    val geometry: FlameGeometry,
    private val compact: Boolean,
    private val emberCount: Int,
    private val fringeInner: Stroke,
    private val fringeOuter: Stroke,
) {
    private val body = Path()
    private val core = Path()
    private val ash = Path()
    private val crack = Path()
    private val facetA = Path()
    private val facetB = Path()
    private val emberStroke = Stroke(width = (geometry.s * 0.007f).coerceAtLeast(1f))
    private val brushes = HashMap<StreakFlameState, FlameBrushSet>()

    private val paint = Paint()

    /** Cached layer bounds for the state dissolve; rebuilt only when the canvas resizes. */
    private var layerSize: Size = Size.Unspecified
    private var layerRect: Rect = Rect.Zero

    fun draw(scope: DrawScope, state: StreakFlameState, elapsed: Double, flashIn: Float) {
        val flash = flashIn.coerceIn(0f, 1f)
        val brush = brushes.getOrPut(state) { brushSet(state, geometry) }
        if (state == StreakFlameState.Inactive) {
            drawAsh(scope, brush, flash)
            return
        }
        val geo = geometry
        val t = elapsed
        val rate = flickerRate(state)
        val intensity = if (state == StreakFlameState.Active) 1f else 0.85f
        val tipDx = tipFlickerDx(t, rate, geo.w * 0.5f) * intensity
        val tilt = tiltDegrees(t, rate) * intensity
        val bob = bobOffset(t, rate, geo.s) * intensity
        val breath = breathScale(breathPhase01(t))
        val sway = (sin(reducedPhase(t, rate, 1.0) * twoPi()) * 0.020f +
            sin(reducedPhase(t, rate, 2.3) * twoPi() + 1.3f) * 0.010f) * geo.w * intensity
        val lickL = sin(reducedPhase(t, rate, 1.3) * twoPi() + 1f) * geo.w * 0.030f * intensity
        val lickR = sin(reducedPhase(t, rate, 1.9) * twoPi() + 2.2f) * geo.w * 0.026f * intensity
        val heightScale = 1f + (breath - 1f) * 0.45f
        val widen = 1f + (breath - 1f) * 0.55f
        val coreTop = if (state == StreakFlameState.Active) 0.56f else 0.44f

        scope.drawCircle(
            brush = brush.bloom,
            radius = geo.h * 1.05f,
            center = Offset(geo.cx, geo.baseY - geo.h * 0.34f),
            alpha = (if (compact) 0.10f else if (state == StreakFlameState.Active) 0.20f else 0.11f) + flash * 0.06f,
        )
        scope.drawCircle(
            brush = brush.bloomInner,
            radius = geo.h * 0.58f,
            center = Offset(geo.cx, geo.baseY - geo.h * 0.30f),
            alpha = (if (compact) 0.06f else if (state == StreakFlameState.Active) 0.16f else 0.09f) + flash * 0.05f,
        )
        if (!compact) {
            scope.drawOval(
                brush = brush.contact,
                topLeft = Offset(geo.cx - geo.w * 0.52f, geo.baseY - geo.h * 0.03f),
                size = Size(geo.w * 1.04f, geo.h * 0.10f),
            )
        }

        scope.withTransform({
            translate(left = 0f, top = bob)
            rotate(degrees = tilt, pivot = Offset(geo.cx, geo.baseY))
        }) {
            buildOuter(body, geo, tipDx, sway, heightScale, widen, lickL, lickR)
            scope.drawPath(body, brush.body)
            scope.drawPath(body, Color(0xFFFF6A2A), alpha = 0.10f, style = fringeInner)
            scope.drawPath(body, Color(0xFFFFC24A), alpha = 0.04f, style = fringeOuter)
            buildCore(core, geo, tipDx, sway, heightScale, widen, coreTop)
            scope.drawPath(core, brush.core)
            scope.drawOval(
                brush = brush.hotBase,
                alpha = (if (compact) 0.24f else 0.32f) + (breath - 1f) * 0.8f + flash * 0.14f,
                topLeft = Offset(geo.cx - geo.w * 0.12f, geo.baseY - geo.h * 0.12f),
                size = Size(geo.w * 0.24f, geo.h * 0.11f),
            )
            drawEmbers(scope, geo, t, rate, intensity)
        }
    }

    /**
     * One-shot state dissolve: the outgoing state keeps its current phase, the incoming
     * state fades in over it. Progress is driven by a single [Animatable] in the
     * composable, never by a second animation loop.
     */
    fun drawCrossFade(
        scope: DrawScope,
        from: StreakFlameState,
        to: StreakFlameState,
        elapsed: Double,
        flashIn: Float,
        progress: Float,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (from == to || p >= 1f) {
            draw(scope, to, elapsed, flashIn)
            return
        }
        withLayerAlpha(scope, 1f - p) { draw(scope, from, elapsed, flashIn) }
        withLayerAlpha(scope, p) { draw(scope, to, elapsed, flashIn) }
    }

    /** Applies a group alpha without allocating per frame; alpha 1 draws directly. */
    private inline fun withLayerAlpha(scope: DrawScope, alpha: Float, block: () -> Unit) {
        val a = alpha.coerceIn(0f, 1f)
        if (a >= 0.999f) {
            block()
            return
        }
        if (a <= 0.001f) return
        paint.alpha = a
        val canvas = scope.drawContext.canvas
        canvas.saveLayer(layerBounds(scope.size), paint)
        try {
            block()
        } finally {
            canvas.restore()
        }
    }

    /** Reuses one layer rect so a dissolve frame allocates nothing in the draw path. */
    private fun layerBounds(size: Size): Rect {
        if (size != layerSize) {
            layerSize = size
            layerRect = Rect(0f, 0f, size.width, size.height)
        }
        return layerRect
    }

    private fun drawEmbers(scope: DrawScope, geo: FlameGeometry, t: Double, rate: Float, intensity: Float) {
        for (i in 0 until emberCount) {
            val seed = sparkSeed(t, rate, i)
            val alpha = sparkAlpha(seed, intensity)
            if (alpha <= 0.03f) continue
            val y = geo.baseY - geo.h * (0.38f + seed * 0.52f)
            val x = geo.cx + sin(seed * 5f + i * 2.1f) * geo.w * 0.16f
            val r = geo.s * (0.004f + 0.0015f * (i % 2))
            scope.drawCircle(
                color = Color(0xFFFFB86B),
                alpha = alpha.coerceIn(0f, 0.8f),
                radius = r,
                center = Offset(x, y),
            )
            scope.drawLine(
                color = Color(0xFFFF8A3C),
                alpha = alpha.coerceIn(0f, 0.35f),
                start = Offset(x, y + r * 2.2f),
                end = Offset(x, y - r * 0.8f),
                strokeWidth = r * 0.9f,
            )
        }
    }

    private fun drawAsh(scope: DrawScope, brush: FlameBrushSet, flash: Float) {
        val geo = geometry
        buildAsh(ash, geo)

        // Residual ember: a warm coal sitting in the ash notch. It scales up at badge
        // sizes so it stays the recognisable focal point when detail is dropped.
        val emberX = geo.cx - geo.w * 0.06f
        val emberY = geo.baseY - geo.h * 0.20f
        val emberR = geo.w * if (compact) 0.11f else 0.085f

        scope.drawCircle(
            brush = brush.bloomInner,
            radius = geo.h * 0.30f,
            center = Offset(emberX, emberY),
            alpha = (if (compact) 0.20f else 0.12f) + flash * 0.05f,
        )
        scope.drawPath(ash, brush.ash)

        // A charcoal shard always breaks the silhouette: this is what stops the mound
        // reading as a smooth pebble at 24-40 dp. Extra chips/planes are hero-only.
        buildShard(crack, geo, -1)
        scope.drawPath(crack, Color(0xFF2C2521))
        if (!compact) {
            buildShard(crack, geo, 1)
            scope.drawPath(crack, Color(0xFF352D26))
            // Soft shading planes, kept very low contrast so it stays ash, not rock face.
            buildFacets(facetA, facetB, geo)
            scope.drawPath(facetA, Color(0x0DFFFFFF))
            scope.drawPath(facetB, Color(0x1A000000))
        }
        // Ash rim: a cool grey highlight, not a stone outline.
        scope.drawPath(ash, Color(0x24B3A99A), style = emberStroke)

        // Coal: charred rim, warm body, hotter centre. No white.
        scope.drawCircle(color = Color(0xFF17120F), alpha = 0.92f, radius = emberR * 1.12f,
            center = Offset(emberX, emberY))
        scope.drawCircle(color = Color(0xFFD45E1D), alpha = 0.95f, radius = emberR,
            center = Offset(emberX, emberY))
        scope.drawCircle(color = Color(0xFFFFB066), alpha = 0.85f + flash * 0.15f,
            radius = emberR * 0.58f, center = Offset(emberX, emberY))
        scope.drawCircle(color = Color(0xFFFFD9A0), alpha = 0.55f + flash * 0.2f,
            radius = emberR * 0.28f, center = Offset(emberX, emberY))
        if (!compact) {
            buildCrack(crack, geo, emberX, emberY)
            scope.drawPath(crack, Color(0x995A2C12), style = emberStroke)
        }
    }
}

private fun twoPi(): Float = 2f * PI.toFloat()

private fun buildOuter(
    path: Path,
    geo: FlameGeometry,
    tipDx: Float,
    sway: Float,
    heightScale: Float,
    widen: Float,
    lickL: Float,
    lickR: Float,
) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float, ft: Float) = cx + fx * w * widen + sway * ft * ft + tipDx * ft * ft
    fun y(ft: Float) = baseY - ft * h * heightScale

    path.reset()
    path.moveTo(x(0f, 0f), y(0f))
    path.cubicTo(x(-0.24f, 0f), y(0f), x(-0.40f, 0.02f), y(0.02f), x(-0.40f, 0.10f), y(0.10f))
    path.cubicTo(x(-0.42f, 0.16f), y(0.16f), x(-0.45f, 0.21f), y(0.21f), x(-0.44f, 0.24f), y(0.24f))
    path.cubicTo(x(-0.40f, 0.29f), y(0.29f), x(-0.33f, 0.33f), y(0.33f), x(-0.30f, 0.36f), y(0.36f))
    path.cubicTo(x(-0.31f, 0.42f), y(0.42f), x(-0.34f, 0.47f), y(0.47f), x(-0.34f, 0.52f), y(0.52f))
    path.cubicTo(
        x(-0.33f, 0.55f), y(0.55f),
        x(-0.27f, 0.58f) + lickL, y(0.58f),
        x(-0.24f, 0.60f) + lickL * 0.6f, y(0.60f),
    )
    path.cubicTo(x(-0.15f, 0.74f), y(0.74f), x(-0.01f, 0.90f), y(0.90f), x(0.05f, 1.00f), y(1.00f))
    path.cubicTo(x(0.08f, 0.88f), y(0.88f), x(0.18f, 0.74f), y(0.74f), x(0.30f, 0.66f), y(0.66f))
    path.cubicTo(
        x(0.29f, 0.62f), y(0.62f),
        x(0.26f, 0.60f) + lickR, y(0.60f),
        x(0.24f, 0.58f) + lickR * 0.6f, y(0.58f),
    )
    path.cubicTo(x(0.33f, 0.50f), y(0.50f), x(0.42f, 0.44f), y(0.44f), x(0.44f, 0.38f), y(0.38f))
    path.cubicTo(x(0.45f, 0.30f), y(0.30f), x(0.42f, 0.28f), y(0.28f), x(0.38f, 0.12f), y(0.12f))
    path.cubicTo(x(0.33f, 0.04f), y(0.04f), x(0.18f, 0f), y(0f), x(0f, 0f), y(0f))
    path.close()
}

private fun buildCore(
    path: Path,
    geo: FlameGeometry,
    tipDx: Float,
    sway: Float,
    heightScale: Float,
    widen: Float,
    top: Float,
) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float, ft: Float) = cx + fx * w * widen * 0.92f + sway * ft * ft + tipDx * ft * ft * 0.8f
    fun y(ft: Float) = baseY - ft * h * heightScale
    val mid = top * 0.80f
    path.reset()
    path.moveTo(x(0f, 0.02f), y(0.02f))
    path.cubicTo(x(-0.07f, 0.03f), y(0.03f), x(-0.13f, 0.10f), y(0.10f), x(-0.13f, 0.20f), y(0.20f))
    path.cubicTo(x(-0.13f, 0.34f), y(0.34f), x(-0.11f, mid - 0.06f), y(mid - 0.06f), x(-0.055f, mid), y(mid))
    path.cubicTo(
        x(-0.025f, top - 0.02f), y(top - 0.02f),
        x(-0.012f, top), y(top),
        x(0f, top), y(top),
    )
    path.cubicTo(
        x(0.012f, top), y(top),
        x(0.025f, top - 0.02f), y(top - 0.02f),
        x(0.055f, mid), y(mid),
    )
    path.cubicTo(x(0.11f, mid - 0.06f), y(mid - 0.06f), x(0.13f, 0.34f), y(0.34f), x(0.13f, 0.20f), y(0.20f))
    path.cubicTo(x(0.13f, 0.10f), y(0.10f), x(0.07f, 0.03f), y(0.03f), x(0f, 0.02f), y(0.02f))
    path.close()
}

private fun buildAsh(path: Path, geo: FlameGeometry) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float, ft: Float) = cx + fx * w
    fun y(ft: Float) = baseY - ft * h
    // Deliberately jagged crown: broken peaks and notches. A smooth dome reads as a
    // pebble at badge sizes; chipped edges read as burnt debris at every size.
    path.reset()
    path.moveTo(x(0f, 0f), y(0f))
    path.cubicTo(x(-0.24f, 0f), y(0f), x(-0.38f, 0.01f), y(0.01f), x(-0.42f, 0.06f), y(0.06f))
    path.lineTo(x(-0.36f, 0.17f), y(0.17f))
    path.lineTo(x(-0.32f, 0.145f), y(0.145f))
    path.lineTo(x(-0.26f, 0.29f), y(0.29f))
    path.lineTo(x(-0.20f, 0.215f), y(0.215f))
    path.lineTo(x(-0.12f, 0.245f), y(0.245f))
    path.lineTo(x(-0.04f, 0.20f), y(0.20f))
    path.lineTo(x(0.02f, 0.315f), y(0.315f))
    path.lineTo(x(0.09f, 0.30f), y(0.30f))
    path.lineTo(x(0.15f, 0.225f), y(0.225f))
    path.lineTo(x(0.25f, 0.285f), y(0.285f))
    path.lineTo(x(0.33f, 0.175f), y(0.175f))
    path.cubicTo(x(0.40f, 0.12f), y(0.12f), x(0.44f, 0.06f), y(0.06f), x(0.40f, 0.03f), y(0.03f))
    path.cubicTo(x(0.32f, 0.01f), y(0.01f), x(0.14f, 0f), y(0f), x(0f, 0f), y(0f))
    path.close()
}

private fun buildFacets(a: Path, b: Path, geo: FlameGeometry) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float) = cx + fx * w
    fun y(ft: Float) = baseY - ft * h
    a.reset()
    a.moveTo(x(-0.30f), y(0.05f))
    a.lineTo(x(-0.12f), y(0.21f))
    a.lineTo(x(0.00f), y(0.20f))
    a.lineTo(x(-0.10f), y(0.07f))
    a.close()
    b.reset()
    b.moveTo(x(0.10f), y(0.20f))
    b.lineTo(x(0.30f), y(0.16f))
    b.lineTo(x(0.36f), y(0.06f))
    b.lineTo(x(0.14f), y(0.03f))
    b.close()
}

/** A chipped charcoal shard resting on the mound; left is -1, right is +1. */
private fun buildShard(path: Path, geo: FlameGeometry, side: Int) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float) = cx + fx * w
    fun y(ft: Float) = baseY - ft * h
    path.reset()
    if (side < 0) {
        path.moveTo(x(-0.40f), y(0.04f))
        path.lineTo(x(-0.30f), y(0.21f))
        path.lineTo(x(-0.16f), y(0.09f))
        path.lineTo(x(-0.24f), y(0.03f))
    } else {
        path.moveTo(x(0.14f), y(0.06f))
        path.lineTo(x(0.24f), y(0.20f))
        path.lineTo(x(0.36f), y(0.08f))
        path.lineTo(x(0.28f), y(0.03f))
    }
    path.close()
}

private fun buildCrack(path: Path, geo: FlameGeometry, emberX: Float, emberY: Float) {
    val w = geo.w
    val h = geo.h
    path.reset()
    path.moveTo(emberX - w * 0.02f, emberY - w * 0.015f)
    path.lineTo(emberX - w * 0.10f, emberY + h * 0.045f)
    path.lineTo(emberX - w * 0.16f, emberY + h * 0.035f)
}

/**
 * The single Flint streak flame renderer.
 *
 * Canvas paths only; no bitmap, no blur effect. One 30 fps `withFrameNanos` source drives
 * every layer. Contours deform laterally (upper points move more than the base), the core
 * breathes, embers rise, and the pose stops cleanly when the surface is off-screen,
 * unfocused, inactive, or Reduce Motion is on.
 */
@Composable
fun StreakFlame(
    state: StreakFlameState,
    streakCount: Int,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    contentDescription: String? = "Day streak: $streakCount",
    milestoneFlash: Float = 0f,
) {
    val reduced = rememberReducedMotion()
    val windowInfo = LocalWindowInfo.current
    val windowFocused = windowInfo.isWindowFocused
    val hostView = LocalView.current
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }
    val dpSize = size.value
    val compact = dpSize < 48f
    val emberCount = when {
        dpSize >= 128f -> 3
        dpSize >= 64f -> 2
        dpSize >= 36f -> 1
        else -> 0
    }
    val (fringeInnerDp, fringeOuterDp) = fringeWidthsDp(dpSize)
    val fringeInner = with(density) { fringeInnerDp.dp.toPx() }
    val fringeOuter = with(density) { fringeOuterDp.dp.toPx() }

    var onScreen by remember { mutableStateOf(true) }
    var elapsed by remember { mutableDoubleStateOf(STATIC_FLAME_SECONDS) }
    // Keyed on animation eligibility, never on the state itself: an Active -> AtRisk
    // change must not restart the clock or jump the phase.
    val animate = !reduced && state != StreakFlameState.Inactive && windowFocused && onScreen

    LaunchedEffect(animate) {
        if (!animate) {
            // Reduce Motion lands on the deterministic neutral pose. An off-screen or
            // dormant pause keeps the last phase, so returning does not jump.
            if (reduced) elapsed = STATIC_FLAME_SECONDS
            return@LaunchedEffect
        }
        // Resume from the phase the flame paused at: the loop is a pure function of
        // elapsed, so continuing the anchor (instead of snapping back to the static
        // pose) is what keeps a re-entry from jumping.
        val anchor = elapsed
        val start = withFrameNanos { it }
        var lastTick = -1L
        while (true) {
            withFrameNanos { now ->
                val t = frameElapsed(anchor, start, now)
                val tick = flameTick(t, 30.0)
                if (tick != lastTick) {
                    lastTick = tick
                    elapsed = t
                }
            }
        }
    }

    // One-shot dissolve between states. It reuses the single phase above and is
    // skipped entirely under Reduce Motion.
    var shown by remember { mutableStateOf(state) }
    var outgoing by remember { mutableStateOf<StreakFlameState?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(state, reduced) {
        if (reduced) {
            // Reduce Motion swaps instantly, including when it is switched on in the
            // middle of a dissolve, so a half-faded pair can never be left on screen.
            shown = state
            outgoing = null
            fade.snapTo(1f)
            return@LaunchedEffect
        }
        if (state == shown) return@LaunchedEffect
        val from = shown
        shown = state
        outgoing = from
        fade.snapTo(0f)
        fade.animateTo(1f, tween(STATE_CROSSFADE_MS, easing = LinearEasing))
        outgoing = null
    }

    val painter = remember(px, compact, emberCount, fringeInner, fringeOuter) {
        StreakFlamePainter(
            geometry = FlameGeometry(px),
            compact = compact,
            emberCount = emberCount,
            fringeInner = Stroke(fringeInner),
            fringeOuter = Stroke(fringeOuter),
        )
    }
    val label = contentDescription
    val semanticsMod = if (label == null) {
        Modifier.clearAndSetSemantics { }
    } else {
        Modifier.semantics(mergeDescendants = true) { this.contentDescription = label }
    }

    Box(
        modifier
            .size(size)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                if (b.width <= 0f || b.height <= 0f) return@onGloballyPositioned
                if (hostView.width <= 0 || hostView.height <= 0) return@onGloballyPositioned
                val win = Rect(0f, 0f, hostView.width.toFloat(), hostView.height.toFloat())
                val visible = b.intersect(win)
                val fraction = if (visible.width <= 0f || visible.height <= 0f) 0f
                else (visible.width * visible.height) / (b.width * b.height)
                onScreen = fraction > 0.05f
            }
            .then(semanticsMod),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val from = outgoing
            val progress = fade.value
            if (from == null || progress >= 1f) {
                painter.draw(this, shown, elapsed, milestoneFlash)
            } else {
                painter.drawCrossFade(this, from, shown, elapsed, milestoneFlash, progress)
            }
        }
    }
}

/** Maps the flame state back onto the lifecycle mood for the shared palette. */
internal fun StreakFlameState.toEmberMood(): StreakEmberMood = when (this) {
    StreakFlameState.Active -> StreakEmberMood.Active
    StreakFlameState.AtRisk -> StreakEmberMood.Warning
    StreakFlameState.Inactive -> StreakEmberMood.Dormant
}

@Preview(name = "Streak flame — active", showBackground = true, backgroundColor = 0xFF0C0E13)
@Composable
private fun StreakFlameActivePreview() {
    StreakFlame(state = StreakFlameState.Active, streakCount = 1, size = 148.dp)
}

@Preview(name = "Streak flame — at risk", showBackground = true, backgroundColor = 0xFF0C0E13)
@Composable
private fun StreakFlameAtRiskPreview() {
    StreakFlame(state = StreakFlameState.AtRisk, streakCount = 1, size = 148.dp)
}

@Preview(name = "Streak flame — dormant ash", showBackground = true, backgroundColor = 0xFF0C0E13)
@Composable
private fun StreakFlameInactivePreview() {
    StreakFlame(state = StreakFlameState.Inactive, streakCount = 0, size = 148.dp)
}
