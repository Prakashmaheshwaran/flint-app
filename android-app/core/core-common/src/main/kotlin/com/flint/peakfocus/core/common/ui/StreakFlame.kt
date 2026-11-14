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

    /** Recognizable low-intensity flame; no animation. */
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

/** Flame brightness per state; inactive is dim but still recognizably a flame. */
internal fun flameIntensity(state: StreakFlameState): Float = when (state) {
    StreakFlameState.Active -> 1f
    StreakFlameState.AtRisk -> 0.88f
    StreakFlameState.Inactive -> 0.45f
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
    val core: Brush,
    val bloom: Brush,
    val bloomInner: Brush,
    val contact: Brush,
    val hotBase: Brush,
    val ash: Brush,
)

/** Active: rich red/orange outer flame. */
internal val ActiveBodyStops = arrayOf(
    0.00f to Color(0xFFFFD24A),
    0.18f to Color(0xFFFFA02A),
    0.45f to Color(0xFFFF6A1A),
    0.72f to Color(0xFFE63A10),
    1.00f to Color(0xFFC41E08),
)

/** At-risk: same silhouette, cooling amber tones. */
internal val AtRiskBodyStops = arrayOf(
    0.00f to Color(0xFFFFC86A),
    0.22f to Color(0xFFF2A23A),
    0.46f to Color(0xFFD97828),
    0.72f to Color(0xFFB85A24),
    0.90f to Color(0xFF8C431C),
    1.00f to Color(0xFF6A3318),
)

/** Dormant: recognizable low-intensity flame silhouette. */
internal val DormantBodyStops = arrayOf(
    0.00f to Color(0xFFB8A898),
    0.25f to Color(0xFF9C8C82),
    0.55f to Color(0xFF7A6E68),
    0.80f to Color(0xFF5C524E),
    1.00f to Color(0xFF433C3A),
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
    val core = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xFFFFFFFF),
            0.20f to Color(0xFFFFF6C8),
            0.48f to Color(0xFFFFE08A),
            0.78f to Color(0xFFFFB020),
            1.00f to Color(0x00FF8A00),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.20f),
            radius = geo.h * 0.42f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xFFFFF0D0),
            0.30f to Color(0xFFFFD08A),
            0.65f to Color(0xFFF0A040),
            1.00f to Color(0x00D07020),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.18f),
            radius = geo.h * 0.36f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0x66FFD8A8),
            0.45f to Color(0x33C8A078),
            1.00f to Color(0x00807060),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.16f),
            radius = geo.h * 0.30f,
        )
    }
    val bloomCentre = Offset(geo.cx, geo.baseY - geo.h * 0.38f)
    val bloom = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x40FF7A1A); StreakFlameState.AtRisk -> Color(0x28E08A30); StreakFlameState.Inactive -> Color(0x10807060) },
        0.55f to when (state) { StreakFlameState.Active -> Color(0x18FF6A10); StreakFlameState.AtRisk -> Color(0x10C07028); StreakFlameState.Inactive -> Color(0x08606058) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 1.00f,
    )
    val bloomInner = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x33FFD060); StreakFlameState.AtRisk -> Color(0x22E0B060); StreakFlameState.Inactive -> Color(0x0C908070) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 0.55f,
    )
    val contact = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x22FF8A20); StreakFlameState.AtRisk -> Color(0x14C07028); StreakFlameState.Inactive -> Color(0x08606058) },
        1.00f to Color(0x00000000),
        center = Offset(geo.cx, geo.baseY - geo.h * 0.02f), radius = geo.w * 0.70f,
    )
    val hotBase = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xF2FFFFFF), 0.35f to Color(0xCCFFF8D8), 0.70f to Color(0x66FFE8A0), 1.00f to Color(0x00FFB040),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.12f), radius = geo.w * 0.28f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xCCFFF0D0), 0.45f to Color(0x88FFD89A), 1.00f to Color(0x00D08030),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.10f), radius = geo.w * 0.24f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0x44E8D8C8), 0.50f to Color(0x18B0A090), 1.00f to Color(0x00000000),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.10f), radius = geo.w * 0.22f,
        )
    }
    val ash = Brush.linearGradient(
        0.00f to Color(0xFF655E56), 0.40f to Color(0xFF3A3530), 0.72f to Color(0xFF262220), 1.00f to Color(0xFF151312),
        start = Offset(geo.cx, geo.baseY - geo.h * 0.32f), end = Offset(geo.cx, geo.baseY),
    )
    return FlameBrushSet(body, core, bloom, bloomInner, contact, hotBase, ash)
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
            StreakFlameState.Active -> 0.62f
            StreakFlameState.AtRisk -> 0.54f
            StreakFlameState.Inactive -> 0.50f
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
                StreakFlameState.Active -> 0.14f
                StreakFlameState.AtRisk -> 0.10f
                StreakFlameState.Inactive -> 0.06f
            }
            scope.drawPath(body, Color(0xFFFF5A18), alpha = fringeA, style = fringeInner)
            scope.drawPath(body, Color(0xFFFFC040), alpha = fringeA * 0.5f, style = fringeOuter)

            buildCore(core, geo, tipDx, sway, heightScale, widen, coreTop)
            scope.drawPath(core, brush.core)

            val hotA = when (state) {
                StreakFlameState.Active -> (if (compact) 0.34f else 0.42f)
                StreakFlameState.AtRisk -> (if (compact) 0.22f else 0.28f)
                StreakFlameState.Inactive -> (if (compact) 0.12f else 0.16f)
            } + (breath - 1f) * 0.8f + flash * 0.12f
            scope.drawOval(
                brush = brush.hotBase,
                alpha = hotA.coerceIn(0f, 1f),
                topLeft = Offset(geo.cx - geo.w * 0.14f, geo.baseY - geo.h * 0.13f),
                size = Size(geo.w * 0.28f, geo.h * 0.12f),
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
            scope.drawCircle(color = Color(0xFFFFD070), alpha = alpha.coerceIn(0f, 0.85f), radius = r, center = Offset(x, y))
            scope.drawLine(color = Color(0xFFFFA040), alpha = alpha.coerceIn(0f, 0.35f), start = Offset(x, y + r * 2.0f), end = Offset(x, y - r * 0.8f), strokeWidth = r * 0.9f)
        }
    }

    /** Retained for API compatibility; dormant renders as a low-intensity flame silhouette. */
    private fun drawAsh(scope: DrawScope, brush: FlameBrushSet, flash: Float) {
        val geo = geometry
        buildOuter(body, geo, 0f, 0f, 1f, 1f, 0f, 0f)
        scope.drawPath(body, brush.body)
        scope.drawPath(body, Color(0xFF8A7A70), alpha = 0.10f, style = fringeInner)
        buildCore(core, geo, 0f, 0f, 1f, 1f, 0.50f)
        scope.drawPath(core, brush.core)
        scope.drawOval(
            brush = brush.hotBase,
            alpha = (if (compact) 0.12f else 0.16f) + flash * 0.08f,
            topLeft = Offset(geo.cx - geo.w * 0.14f, geo.baseY - geo.h * 0.13f),
            size = Size(geo.w * 0.28f, geo.h * 0.12f),
        )
    }
}

private fun twoPi(): Float = 2f * PI.toFloat()

/** Iconic fire silhouette: broad base, two side lobes, upward-pointing tip (reads as 🔥 at 40dp). */
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
    path.cubicTo(x(-0.22f, 0f), y(0f), x(-0.44f, 0.06f), y(0.06f), x(-0.46f, 0.20f), y(0.20f))
    path.cubicTo(x(-0.48f, 0.32f), y(0.32f), x(-0.38f, 0.42f), y(0.42f), x(-0.28f, 0.50f), y(0.50f))
    path.cubicTo(x(-0.22f, 0.58f) + lickL * 0.5f, y(0.58f), x(-0.14f, 0.76f) + lickL, y(0.76f), x(-0.02f, 0.92f), y(0.92f))
    path.cubicTo(x(0.00f, 0.97f), y(0.97f), x(0.01f, 0.99f), y(0.99f), x(0.02f, 1.00f), y(1.00f))
    path.cubicTo(x(0.06f, 0.94f), y(0.94f), x(0.14f, 0.78f) + lickR, y(0.78f), x(0.22f, 0.64f) + lickR * 0.5f, y(0.64f))
    path.cubicTo(x(0.34f, 0.54f), y(0.54f), x(0.48f, 0.40f), y(0.40f), x(0.46f, 0.24f), y(0.24f))
    path.cubicTo(x(0.44f, 0.10f), y(0.10f), x(0.30f, 0.02f), y(0.02f), x(0f, 0f), y(0f))
    path.close()
}

/** Inner flame: orange-to-yellow core rising from base center. */
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

    path.reset()
    path.moveTo(x(0f, 0.02f), y(0.02f))
    path.cubicTo(x(-0.10f, 0.03f), y(0.03f), x(-0.18f, 0.12f), y(0.12f), x(-0.16f, 0.26f), y(0.26f))
    path.cubicTo(x(-0.14f, 0.38f), y(0.38f), x(-0.08f, mid), y(mid), x(-0.03f, top - 0.04f), y(top - 0.04f))
    path.cubicTo(x(-0.01f, top - 0.01f), y(top - 0.01f), x(0f, top), y(top), x(0.01f, top - 0.01f), y(top - 0.01f))
    path.cubicTo(x(0.03f, top - 0.04f), y(top - 0.04f), x(0.08f, mid), y(mid), x(0.16f, 0.26f), y(0.26f))
    path.cubicTo(x(0.18f, 0.12f), y(0.12f), x(0.10f, 0.03f), y(0.03f), x(0f, 0.02f), y(0.02f))
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
    a.reset(); a.moveTo(x(-0.12f), y(0.08f)); a.lineTo(x(-0.04f), y(0.18f)); a.lineTo(x(0.02f), y(0.14f)); a.lineTo(x(-0.06f), y(0.06f)); a.close()
    b.reset(); b.moveTo(x(0.06f), y(0.14f)); b.lineTo(x(0.12f), y(0.18f)); b.lineTo(x(0.14f), y(0.08f)); b.lineTo(x(0.06f), y(0.06f)); b.close()
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
    val fringeInner = with(density) { fringeInnerDp.dp.toPx() }
    val fringeOuter = with(density) { fringeOuterDp.dp.toPx() }

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
    LaunchedEffect(state, reduced) {
        if (reduced) { shown = state; outgoing = null; fade.snapTo(1f); return@LaunchedEffect }
        if (state == shown) return@LaunchedEffect
        val from = shown
        shown = state
        outgoing = from
        fade.snapTo(0f)
        fade.animateTo(1f, tween(STATE_CROSSFADE_MS, easing = LinearEasing))
        outgoing = null
    }

    val painter = remember(px, compact, emberCount, fringeInner, fringeOuter) {
        StreakFlamePainter(FlameGeometry(px), compact, emberCount, Stroke(fringeInner), Stroke(fringeOuter))
    }
    val label = contentDescription
    val semanticsMod = if (label == null) Modifier.clearAndSetSemantics { } else
        Modifier.semantics(mergeDescendants = true) { this.contentDescription = label }

    Box(
        modifier
            .size(size)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                if (b.width <= 0f || b.height <= 0f) return@onGloballyPositioned
                if (hostView.width <= 0 || hostView.height <= 0) return@onGloballyPositioned
                val win = Rect(0f, 0f, hostView.width.toFloat(), hostView.height.toFloat())
                val visible = b.intersect(win)
                val fraction = if (visible.width <= 0f || visible.height <= 0f) 0f else (visible.width * visible.height) / (b.width * b.height)
                onScreen = fraction > 0.05f
            }
            .then(semanticsMod),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val from = outgoing
            val progress = fade.value
            if (from == null || progress >= 1f) painter.draw(this, shown, elapsed, milestoneFlash)
            else painter.drawCrossFade(this, from, shown, elapsed, milestoneFlash, progress)
        }
    }
}

internal fun StreakFlameState.toEmberMood(): StreakEmberMood = when (this) {
    StreakFlameState.Active -> StreakEmberMood.Active
    StreakFlameState.AtRisk -> StreakEmberMood.Warning
    StreakFlameState.Inactive -> StreakEmberMood.Dormant
}

@Preview(name = "Streak flame — active", showBackground = true, backgroundColor = 0xFF0C0E13)
@Composable
private fun StreakFlameActivePreview() { StreakFlame(state = StreakFlameState.Active, streakCount = 1, size = 148.dp) }

@Preview(name = "Streak flame — at risk", showBackground = true, backgroundColor = 0xFF0C0E13)
@Composable
private fun StreakFlameAtRiskPreview() { StreakFlame(state = StreakFlameState.AtRisk, streakCount = 1, size = 148.dp) }

@Preview(name = "Streak flame — dormant", showBackground = true, backgroundColor = 0xFF0C0E13)
@Composable
private fun StreakFlameInactivePreview() { StreakFlame(state = StreakFlameState.Inactive, streakCount = 0, size = 148.dp) }
