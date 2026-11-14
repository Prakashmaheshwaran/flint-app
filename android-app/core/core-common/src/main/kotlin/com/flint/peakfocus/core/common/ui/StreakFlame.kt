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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Display state for the day-streak flame. */
enum class StreakFlameState {
    /** Full color, full animation. */
    Active,

    /** Same flame cooling: reduced heat, slower motion. */
    AtRisk,

    /** Recognizable low-intensity flame; no animation. */
    Inactive,
}

/** Maps the streak lifecycle mood onto the flame's display state. */
internal fun StreakEmberMood.toFlameState(): StreakFlameState = when (this) {
    StreakEmberMood.Active -> StreakFlameState.Active
    StreakEmberMood.Warning -> StreakFlameState.AtRisk
    StreakEmberMood.Dormant -> StreakFlameState.Inactive
}

/** Reverse mapping for wrappers that still speak in mood terms. */
internal fun StreakFlameState.toEmberMood(): StreakEmberMood = when (this) {
    StreakFlameState.Active -> StreakEmberMood.Active
    StreakFlameState.AtRisk -> StreakEmberMood.Warning
    StreakFlameState.Inactive -> StreakEmberMood.Dormant
}

/** Animation ticks never run before zero; out-of-domain time maps to tick 0. */
internal fun flameTick(elapsedSeconds: Double, fps: Double): Long =
    maxOf(0L, (elapsedSeconds * fps).toLong())

/** Breathing phase: elapsed seconds map onto 0..1 over a ~1.5s cycle. */
internal fun breathPhase01(elapsedSeconds: Double): Float =
    ((sin(elapsedSeconds * 2.0 * PI / 1.5) + 1.0) / 2.0).toFloat()

/** Flame brightness per state; inactive is dim but still recognizably a flame. */
internal fun flameIntensity(state: StreakFlameState): Float = when (state) {
    StreakFlameState.Active -> 1f
    StreakFlameState.AtRisk -> 0.82f
    StreakFlameState.Inactive -> 0.52f
}

/** Pose used for Reduce Motion and host-rendered captures. */
internal const val STATIC_FLAME_SECONDS: Double = 0.72

/** Cross-fade between streak states; one dissolve, no loop restart. */
internal const val STATE_CROSSFADE_MS: Int = 320

/** Fringe stroke widths in dp, keyed to the rendered size. */
internal fun fringeWidthsDp(sizeDp: Float): Pair<Float, Float> = when {
    sizeDp < 48f -> 0.7f to 1.1f
    sizeDp < 120f -> 1.0f to 1.8f
    else -> 1.4f to 2.6f
}

/** Flicker rate per state. At-risk runs slower; inactive is static. */
internal fun flickerRate(state: StreakFlameState): Float = when (state) {
    StreakFlameState.Active -> 1f
    StreakFlameState.AtRisk -> 0.6f
    StreakFlameState.Inactive -> 0f
}

/** Cycle fraction in [0, 1). */
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

/** Dancing tip offset. */
internal fun tipFlickerDx(elapsedSeconds: Double, rate: Float, halfW: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 2.1) * twoPi) * halfW * 0.06f +
        sin(reducedPhase(elapsedSeconds, rate, 3.7) * twoPi + 0.8f) * halfW * 0.025f
}

/** Slow whole-flame tilt in degrees. */
internal fun tiltDegrees(elapsedSeconds: Double, rate: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 0.9) * twoPi + 0.5f) * 1.4f
}

/** Slow vertical bob in pixels. */
internal fun bobOffset(elapsedSeconds: Double, rate: Float, unit: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 2.2) * twoPi) * unit * 0.004f
}

/** Ember brightness envelope. */
internal fun sparkAlpha(seed: Float, intensity: Float): Float =
    sin(seed * PI.toFloat()) * 0.75f * intensity

/** Loop time for one frame. */
internal fun frameElapsed(anchorSeconds: Double, startNanos: Long, nowNanos: Long): Double =
    anchorSeconds + (nowNanos - startNanos) / 1_000_000_000.0

/** Breathing scale: 0.96..1.04. */
internal fun breathScale(breath01: Float): Float = 0.96f + 0.08f * breath01

/** Outer glow alpha. */
internal fun haloAlpha(breath01: Float, flash: Float): Float =
    (0.25f + 0.20f * breath01 + flash * 0.15f).coerceIn(0f, 1f)

/** Flame geometry for an iconic upward fire silhouette. */
internal class FlameGeometry(val s: Float) {
    val w: Float = s * 0.76f
    val h: Float = s * 0.90f
    val cx: Float = s * 0.5f
    val baseY: Float = s * 0.95f
}

internal class FlameBrushSet(
    val body: Brush,
    val inner: Brush,
    val core: Brush,
    val bloom: Brush,
    val bloomInner: Brush,
    val contact: Brush,
    val hotBase: Brush,
    val ash: Brush,
)

/** Active: strong red-to-orange outer flame (reads as fire at badge size). */
internal val ActiveBodyStops = arrayOf(
    0.00f to Color(0xFFFFD24A),
    0.20f to Color(0xFFFF9A1F),
    0.45f to Color(0xFFFF5A12),
    0.72f to Color(0xFFE1260B),
    1.00f to Color(0xFFB81405),
)

/** At-risk: same flame identity, cooled toward amber (not grey). */
internal val AtRiskBodyStops = arrayOf(
    0.00f to Color(0xFFFFD07A),
    0.22f to Color(0xFFF2A43D),
    0.46f to Color(0xFFDE7728),
    0.72f to Color(0xFFB9581D),
    0.90f to Color(0xFF8F4218),
    1.00f to Color(0xFF6E3214),
)

/** Dormant: recognizable low-intensity fire (dim embered flame, not beige/ash mound). */
internal val DormantBodyStops = arrayOf(
    0.00f to Color(0xFFD8A45A),
    0.25f to Color(0xFFB8743A),
    0.55f to Color(0xFF8A4A28),
    0.80f to Color(0xFF62341E),
    1.00f to Color(0xFF4A2718),
)

/** Warm cooling chrome for at-risk streak text. */
val StreakCoolTop: Color = Color(0xFFF2C892)
val StreakCoolDeep: Color = Color(0xFFC1731F)

internal fun brushSet(state: StreakFlameState, geo: FlameGeometry): FlameBrushSet {
    val bodyStops = when (state) {
        StreakFlameState.Active -> ActiveBodyStops
        StreakFlameState.AtRisk -> AtRiskBodyStops
        StreakFlameState.Inactive -> DormantBodyStops
    }
    val body = Brush.verticalGradient(*bodyStops, startY = geo.baseY, endY = geo.baseY - geo.h)
    val inner = when (state) {
        StreakFlameState.Active -> Brush.verticalGradient(
            0.00f to Color(0xFFFFF3A8),
            0.35f to Color(0xFFFFD24A),
            0.70f to Color(0xFFFF9A24),
            1.00f to Color(0x00FF7A18),
            startY = geo.baseY - geo.h * 0.06f, endY = geo.baseY - geo.h * 0.86f,
        )
        StreakFlameState.AtRisk -> Brush.verticalGradient(
            0.00f to Color(0xFFFFE8B8),
            0.35f to Color(0xFFF7C85A),
            0.70f to Color(0xFFE08A2E),
            1.00f to Color(0x00C86A20),
            startY = geo.baseY - geo.h * 0.06f, endY = geo.baseY - geo.h * 0.82f,
        )
        StreakFlameState.Inactive -> Brush.verticalGradient(
            0.00f to Color(0x99E0B070),
            0.40f to Color(0x66C88840),
            0.75f to Color(0x33906030),
            1.00f to Color(0x00604028),
            startY = geo.baseY - geo.h * 0.08f, endY = geo.baseY - geo.h * 0.78f,
        )
    }
    val core = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xFFFFFFFF),
            0.22f to Color(0xFFFFF9D8),
            0.48f to Color(0xFFFFE88A),
            0.78f to Color(0xFFFFC030),
            1.00f to Color(0x00FF9A10),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.20f),
            radius = geo.h * 0.40f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xFFFFF4DE),
            0.30f to Color(0xFFFFD89A),
            0.65f to Color(0xFFF2A548),
            1.00f to Color(0x00D07020),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.19f),
            radius = geo.h * 0.35f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0x66FFD8A0),
            0.45f to Color(0x30C89050),
            1.00f to Color(0x00705030),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.17f),
            radius = geo.h * 0.28f,
        )
    }
    val bloomCentre = Offset(geo.cx, geo.baseY - geo.h * 0.38f)
    val bloom = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x44FF6A10); StreakFlameState.AtRisk -> Color(0x28E07A28); StreakFlameState.Inactive -> Color(0x10805030) },
        0.55f to when (state) { StreakFlameState.Active -> Color(0x18FF4A08); StreakFlameState.AtRisk -> Color(0x10C86020); StreakFlameState.Inactive -> Color(0x08604028) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 1.00f,
    )
    val bloomInner = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x33FFD050); StreakFlameState.AtRisk -> Color(0x22E0B060); StreakFlameState.Inactive -> Color(0x0C907050) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 0.55f,
    )
    val contact = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x22FF7A18); StreakFlameState.AtRisk -> Color(0x14C87028); StreakFlameState.Inactive -> Color(0x08604028) },
        1.00f to Color(0x00000000),
        center = Offset(geo.cx, geo.baseY - geo.h * 0.02f), radius = geo.w * 0.70f,
    )
    val hotBase = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xF5FFFFFF), 0.30f to Color(0xD8FFF8D0), 0.62f to Color(0x70FFE890), 1.00f to Color(0x00FFB020),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.13f), radius = geo.w * 0.30f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xD0FFF2D8), 0.42f to Color(0x90FFD898), 1.00f to Color(0x00D08030),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.12f), radius = geo.w * 0.26f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0x55E8C898), 0.50f to Color(0x20B08858), 1.00f to Color(0x00000000),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.11f), radius = geo.w * 0.22f,
        )
    }
    // Kept for compatibility with tests/callers; dormant now renders as low-intensity fire.
    val ash = Brush.linearGradient(
        0.00f to Color(0xFF6A5644), 0.40f to Color(0xFF4A3A30), 0.72f to Color(0xFF332822), 1.00f to Color(0xFF201816),
        start = Offset(geo.cx, geo.baseY - geo.h * 0.32f), end = Offset(geo.cx, geo.baseY),
    )
    return FlameBrushSet(body, inner, core, bloom, bloomInner, contact, hotBase, ash)
}

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
    private var layerSize: Size = Size.Unspecified
    private var layerRect: Rect = Rect.Zero

    fun draw(scope: DrawScope, state: StreakFlameState, elapsed: Double, flashIn: Float) {
        val flash = flashIn.coerceIn(0f, 1f)
        val brush = brushes.getOrPut(state) { brushSet(state, geometry) }
        val geo = geometry
        val animate = state != StreakFlameState.Inactive
        val t = if (animate) elapsed else STATIC_FLAME_SECONDS
        val rate = if (animate) flickerRate(state) else 0f
        val intensity = flameIntensity(state)
        val tipDx = if (animate) tipFlickerDx(t, rate, geo.w * 0.5f) * intensity else 0f
        val tilt = if (animate) tiltDegrees(t, rate) * intensity else 0f
        val bob = if (animate) bobOffset(t, rate, geo.s) * intensity else 0f
        val breath01 = if (animate) breathPhase01(t) else 0.5f
        val breath = if (animate) breathScale(breath01) else 1f
        val sway = if (animate) (sin(reducedPhase(t, rate, 1.0) * twoPi()) * 0.022f +
            sin(reducedPhase(t, rate, 2.3) * twoPi() + 1.3f) * 0.012f) * geo.w * intensity else 0f
        val lickL = if (animate) sin(reducedPhase(t, rate, 1.3) * twoPi() + 1f) * geo.w * 0.032f * intensity else 0f
        val lickR = if (animate) sin(reducedPhase(t, rate, 1.9) * twoPi() + 2.2f) * geo.w * 0.028f * intensity else 0f
        val heightScale = 1f + (breath - 1f) * 0.45f
        val widen = 1f + (breath - 1f) * 0.55f
        val coreTop = when (state) {
            StreakFlameState.Active -> 0.64f
            StreakFlameState.AtRisk -> 0.58f
            StreakFlameState.Inactive -> 0.54f
        }

        val bloomA = when (state) {
            StreakFlameState.Active -> (if (compact) 0.14f else 0.22f)
            StreakFlameState.AtRisk -> (if (compact) 0.09f else 0.13f)
            StreakFlameState.Inactive -> (if (compact) 0.05f else 0.07f)
        } + flash * 0.06f
        scope.drawCircle(brush = brush.bloom, radius = geo.h * 1.00f, center = Offset(geo.cx, geo.baseY - geo.h * 0.38f), alpha = bloomA)
        scope.drawCircle(brush = brush.bloomInner, radius = geo.h * 0.55f, center = Offset(geo.cx, geo.baseY - geo.h * 0.34f), alpha = bloomA * 0.8f)
        if (!compact) {
            scope.drawOval(brush = brush.contact, topLeft = Offset(geo.cx - geo.w * 0.50f, geo.baseY - geo.h * 0.03f), size = Size(geo.w * 1.00f, geo.h * 0.10f))
        }

        scope.withTransform({
            translate(left = 0f, top = bob)
            rotate(degrees = tilt, pivot = Offset(geo.cx, geo.baseY))
        }) {
            buildOuter(body, geo, tipDx, sway, heightScale, widen, lickL, lickR)
            scope.drawPath(body, brush.body)
            val fringeA = when (state) {
                StreakFlameState.Active -> 0.16f
                StreakFlameState.AtRisk -> 0.11f
                StreakFlameState.Inactive -> 0.07f
            }
            scope.drawPath(body, Color(0xFFFF3A10), alpha = fringeA, style = fringeInner)
            scope.drawPath(body, Color(0xFFFFC040), alpha = fringeA * 0.45f, style = fringeOuter)

            // Clearly visible inner flame (orange-to-yellow) inside the outer red-orange shell.
            buildCore(core, geo, tipDx * 0.9f, sway * 0.85f, heightScale, widen * 0.96f, coreTop)
            scope.drawPath(core, brush.inner)

            // Bright yellow/white-hot core near lower center.
            val hotA = when (state) {
                StreakFlameState.Active -> (if (compact) 0.40f else 0.50f)
                StreakFlameState.AtRisk -> (if (compact) 0.26f else 0.34f)
                StreakFlameState.Inactive -> (if (compact) 0.16f else 0.20f)
            } + (breath - 1f) * 0.8f + flash * 0.12f
            scope.drawOval(
                brush = brush.hotBase,
                alpha = hotA.coerceIn(0f, 1f),
                topLeft = Offset(geo.cx - geo.w * 0.15f, geo.baseY - geo.h * 0.14f),
                size = Size(geo.w * 0.30f, geo.h * 0.13f),
            )
            scope.drawOval(
                brush = brush.core,
                alpha = (hotA * 0.55f).coerceIn(0f, 0.45f),
                topLeft = Offset(geo.cx - geo.w * 0.11f, geo.baseY - geo.h * 0.18f),
                size = Size(geo.w * 0.22f, geo.h * 0.16f),
            )

            if (animate && emberCount > 0) {
                drawEmbers(scope, geo, t, rate, intensity)
            }
        }
    }

    fun drawCrossFade(
        scope: DrawScope,
        from: StreakFlameState,
        to: StreakFlameState,
        elapsed: Double,
        flashIn: Float,
        progress: Float,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (from == to || p >= 1f) { draw(scope, to, elapsed, flashIn); return }
        withLayerAlpha(scope, 1f - p) { draw(scope, from, elapsed, flashIn) }
        withLayerAlpha(scope, p) { draw(scope, to, elapsed, flashIn) }
    }

    private inline fun withLayerAlpha(scope: DrawScope, alpha: Float, block: () -> Unit) {
        val a = alpha.coerceIn(0f, 1f)
        if (a >= 0.999f) { block(); return }
        if (a <= 0.001f) return
        paint.alpha = a
        val canvas = scope.drawContext.canvas
        canvas.saveLayer(layerBounds(scope.size), paint)
        try { block() } finally { canvas.restore() }
    }

    private fun layerBounds(size: Size): Rect {
        if (size != layerSize) { layerSize = size; layerRect = Rect(0f, 0f, size.width, size.height) }
        return layerRect
    }

    private fun drawEmbers(scope: DrawScope, geo: FlameGeometry, t: Double, rate: Float, intensity: Float) {
        for (i in 0 until emberCount) {
            val seed = sparkSeed(t, rate, i)
            val alpha = sparkAlpha(seed, intensity)
            if (alpha <= 0.03f) continue
            val y = geo.baseY - geo.h * (0.42f + seed * 0.50f)
            val x = geo.cx + sin(seed * 5f + i * 2.1f) * geo.w * 0.14f
            val r = geo.s * (0.004f + 0.0015f * (i % 2))
            scope.drawCircle(color = Color(0xFFFFE070), alpha = alpha.coerceIn(0f, 0.85f), radius = r, center = Offset(x, y))
            scope.drawLine(color = Color(0xFFFFA040), alpha = alpha.coerceIn(0f, 0.35f), start = Offset(x, y + r * 2.0f), end = Offset(x, y - r * 0.8f), strokeWidth = r * 0.9f)
        }
    }

    /** Retained for API/test compatibility; dormant is a low-intensity flame, not ash. */
    private fun drawAsh(scope: DrawScope, brush: FlameBrushSet, flash: Float) {
        val geo = geometry
        buildOuter(body, geo, 0f, 0f, 1f, 1f, 0f, 0f)
        scope.drawPath(body, brush.body)
        scope.drawPath(body, Color(0xFFB86A30), alpha = 0.10f, style = fringeInner)
        buildCore(core, geo, 0f, 0f, 1f, 1f, 0.54f)
        scope.drawPath(core, brush.inner)
        scope.drawOval(
            brush = brush.hotBase,
            alpha = (if (compact) 0.16f else 0.20f) + flash * 0.08f,
            topLeft = Offset(geo.cx - geo.w * 0.15f, geo.baseY - geo.h * 0.14f),
            size = Size(geo.w * 0.30f, geo.h * 0.13f),
        )
    }
}

private fun twoPi(): Float = 2f * PI.toFloat()

/**
 * Recognizable fire silhouette (Snapstreak-style): wide heat base, clearly defined outer
 * flame lobes, and an irregular pointed top. Reads as 🔥 at 40dp (silhouette/contrast first).
 */
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
    // Base center -> right base
    path.moveTo(x(0f, 0f), y(0f))
    path.cubicTo(x(0.20f, 0f), y(0f), x(0.44f, 0.05f), y(0.05f), x(0.46f, 0.20f), y(0.20f))
    // Right outer lobe (defined shoulder)
    path.cubicTo(x(0.49f, 0.32f), y(0.32f), x(0.43f, 0.40f), y(0.40f), x(0.34f, 0.48f), y(0.48f))
    path.cubicTo(x(0.29f, 0.54f) + lickR * 0.45f, y(0.54f), x(0.26f, 0.63f) + lickR * 0.75f, y(0.63f), x(0.20f, 0.71f) + lickR * 0.35f, y(0.71f))
    // Valley into main pointed tip (irregular/asymmetric crown)
    path.cubicTo(x(0.16f, 0.78f), y(0.78f), x(0.10f, 0.86f), y(0.86f), x(0.05f, 0.92f), y(0.92f))
    path.cubicTo(x(0.03f, 0.96f), y(0.96f), x(0.015f, 0.985f), y(0.985f), x(0.01f, 1.00f), y(1.00f))
    // Sharp tip down left side (main flame tongue)
    path.cubicTo(x(-0.01f, 0.96f), y(0.96f), x(-0.04f, 0.90f), y(0.90f), x(-0.08f, 0.84f) + lickL * 0.25f, y(0.84f))
    path.cubicTo(x(-0.13f, 0.76f) + lickL * 0.70f, y(0.76f), x(-0.20f, 0.66f) + lickL * 0.55f, y(0.66f), x(-0.25f, 0.58f) + lickL * 0.30f, y(0.58f))
    // Left outer lobe
    path.cubicTo(x(-0.34f, 0.50f), y(0.50f), x(-0.47f, 0.40f), y(0.40f), x(-0.46f, 0.26f), y(0.26f))
    path.cubicTo(x(-0.45f, 0.10f), y(0.10f), x(-0.30f, 0.02f), y(0.02f), x(-0.12f, 0.0f), y(0.0f))
    path.cubicTo(x(-0.06f, 0.0f), y(0.0f), x(-0.02f, 0.0f), y(0.0f), x(0f, 0f), y(0f))
    path.close()
}

/** Inner flame (orange-to-yellow), nested inside outer shell with its own pointed tongue. */
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
    fun x(fx: Float, ft: Float) = cx + fx * w * widen * 0.92f + sway * ft * ft + tipDx * ft * ft * 0.75f
    fun y(ft: Float) = baseY - ft * h * heightScale
    val mid = top * 0.78f
    val tipY = min(top, 0.92f)

    path.reset()
    path.moveTo(x(0f, 0.03f), y(0.03f))
    path.cubicTo(x(0.14f, 0.04f), y(0.04f), x(0.24f, 0.14f), y(0.14f), x(0.22f, 0.30f), y(0.30f))
    path.cubicTo(x(0.20f, 0.42f), y(0.42f), x(0.14f, 0.54f), y(0.54f), x(0.10f, 0.64f), y(0.64f))
    path.cubicTo(x(0.07f, 0.74f), y(0.74f), x(0.04f, tipY - 0.08f), y(tipY - 0.08f), x(0.015f, tipY), y(tipY))
    path.cubicTo(x(0.0f, tipY - 0.03f), y(tipY - 0.03f), x(-0.03f, 0.80f), y(0.80f), x(-0.07f, 0.70f), y(0.70f))
    path.cubicTo(x(-0.12f, 0.58f), y(0.58f), x(-0.21f, 0.44f), y(0.44f), x(-0.22f, 0.30f), y(0.30f))
    path.cubicTo(x(-0.23f, 0.14f), y(0.14f), x(-0.14f, 0.04f), y(0.04f), x(0f, 0.03f), y(0.03f))
    path.close()
}

/** Retained for API/test compatibility: dormant flame silhouette path. */
private fun buildAsh(path: Path, geo: FlameGeometry) {
    buildOuter(path, geo, 0f, 0f, 1f, 1f, 0f, 0f)
}

/** Retained for API compatibility. */
private fun buildFacets(a: Path, b: Path, geo: FlameGeometry) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float) = cx + fx * w
    fun y(ft: Float) = baseY - ft * h
    a.reset(); a.moveTo(x(-0.10f), y(0.10f)); a.lineTo(x(-0.03f), y(0.20f)); a.lineTo(x(0.02f), y(0.15f)); a.lineTo(x(-0.05f), y(0.07f)); a.close()
    b.reset(); b.moveTo(x(0.05f), y(0.16f)); b.lineTo(x(0.11f), y(0.20f)); b.lineTo(x(0.13f), y(0.09f)); b.lineTo(x(0.06f), y(0.07f)); b.close()
}

/** Retained for API compatibility. */
private fun buildShard(path: Path, geo: FlameGeometry, side: Int) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float) = cx + fx * w
    fun y(ft: Float) = baseY - ft * h
    path.reset()
    if (side < 0) { path.moveTo(x(-0.18f), y(0.06f)); path.lineTo(x(-0.10f), y(0.16f)); path.lineTo(x(-0.06f), y(0.08f)) }
    else { path.moveTo(x(0.06f), y(0.08f)); path.lineTo(x(0.10f), y(0.16f)); path.lineTo(x(0.18f), y(0.06f)) }
    path.close()
}

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
    val fringeInner = with(density) { Stroke(fringeInnerDp.dp.toPx()) }
    val fringeOuter = with(density) { Stroke(fringeOuterDp.dp.toPx()) }

    var onScreen by remember { mutableStateOf(true) }
    var elapsed by remember { mutableDoubleStateOf(STATIC_FLAME_SECONDS) }
    val animate = !reduced && state != StreakFlameState.Inactive && windowFocused && onScreen

    LaunchedEffect(animate) {
        if (!animate) { if (reduced) elapsed = STATIC_FLAME_SECONDS; return@LaunchedEffect }
        val anchor = elapsed
        val start = withFrameNanos { it }
        var lastTick = -1L
        while (true) {
            withFrameNanos { now ->
                val t = frameElapsed(anchor, start, now)
                val tick = flameTick(t, 30.0)
                if (tick != lastTick) { lastTick = tick; elapsed = t }
            }
        }
    }

    var shown by remember { mutableStateOf(state) }
    var outgoing by remember { mutableStateOf<StreakFlameState?>(null) }
    val fade = remember { Animatable(1f) }
    var crossflash by remember { mutableStateOf(0f) }

    LaunchedEffect(state) {
        if (state == shown && outgoing == null) return@LaunchedEffect
        if (reduced) {
            outgoing = null
            shown = state
            fade.snapTo(1f)
            crossflash = 0f
            return@LaunchedEffect
        }
        outgoing = shown
        shown = state
        crossflash = 0.12f
        fade.snapTo(0f)
        fade.animateTo(1f, tween(STATE_CROSSFADE_MS, easing = LinearEasing))
        outgoing = null
        crossflash = 0f
    }

    val painter = remember(px, compact, emberCount, fringeInner, fringeOuter) {
        StreakFlamePainter(FlameGeometry(px), compact, emberCount, fringeInner, fringeOuter)
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
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clearAndSetSemantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val from = outgoing
            if (from != null) {
                painter.drawCrossFade(this, from, shown, elapsed, milestoneFlash + crossflash, fade.value)
            } else {
                painter.draw(this, shown, elapsed, milestoneFlash + crossflash)
            }
        }
    }
}

@Preview(name = "Active 148dp", backgroundColor = 0xFF12110F, showBackground = true)
@Composable
private fun ActivePreview() {
    StreakFlame(state = StreakFlameState.Active, streakCount = 7, size = 148.dp)
}

@Preview(name = "At-risk 148dp", backgroundColor = 0xFF12110F, showBackground = true)
@Composable
private fun AtRiskPreview() {
    StreakFlame(state = StreakFlameState.AtRisk, streakCount = 7, size = 148.dp)
}

@Preview(name = "Inactive 148dp", backgroundColor = 0xFF12110F, showBackground = true)
@Composable
private fun InactivePreview() {
    StreakFlame(state = StreakFlameState.Inactive, streakCount = 0, size = 148.dp)
}

@Preview(name = "Badge 40dp", backgroundColor = 0xFF12110F, showBackground = true)
@Composable
private fun BadgePreview() {
    StreakFlame(state = StreakFlameState.Active, streakCount = 7, size = 40.dp)
}
