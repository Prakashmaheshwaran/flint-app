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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Display state for the day-streak flame. */
enum class StreakFlameState {
    /** Full color, full "live" animation. */
    Active,
    /** Same flame cooling: reduced heat, slower motion. */
    AtRisk,
    /** Recognizable low-intensity flame; no animation. */
    Inactive,
}

internal fun StreakEmberMood.toFlameState(): StreakFlameState = when (this) {
    StreakEmberMood.Active -> StreakFlameState.Active
    StreakEmberMood.Warning -> StreakFlameState.AtRisk
    StreakEmberMood.Dormant -> StreakFlameState.Inactive
}

internal fun StreakFlameState.toEmberMood(): StreakEmberMood = when (this) {
    StreakFlameState.Active -> StreakEmberMood.Active
    StreakFlameState.AtRisk -> StreakEmberMood.Warning
    StreakFlameState.Inactive -> StreakEmberMood.Dormant
}

internal fun flameTick(elapsedSeconds: Double, fps: Double): Long =
    maxOf(0L, (elapsedSeconds * fps).toLong())

internal fun breathPhase01(elapsedSeconds: Double): Float =
    ((sin(elapsedSeconds * 2.0 * PI / 1.5) + 1.0) / 2.0).toFloat()

internal fun flameIntensity(state: StreakFlameState): Float = when (state) {
    StreakFlameState.Active -> 1f
    StreakFlameState.AtRisk -> 0.82f
    StreakFlameState.Inactive -> 0.52f
}

internal const val STATIC_FLAME_SECONDS: Double = 0.72
internal const val STATE_CROSSFADE_MS: Int = 320

internal fun fringeWidthsDp(sizeDp: Float): Pair<Float, Float> = when {
    sizeDp < 48f -> 0.7f to 1.1f
    sizeDp < 120f -> 1.0f to 1.8f
    else -> 1.4f to 2.6f
}

internal fun flickerRate(state: StreakFlameState): Float = when (state) {
    StreakFlameState.Active -> 1f
    StreakFlameState.AtRisk -> 0.6f
    StreakFlameState.Inactive -> 0f
}

internal fun reducedPhase(elapsedSeconds: Double, rate: Float, freq: Double): Float {
    val x = (elapsedSeconds / 6.0) * rate * freq
    return (((x % 1.0) + 1.0) % 1.0).toFloat()
}

internal fun sparkSeed(elapsedSeconds: Double, rate: Float, index: Int): Float {
    val base = reducedPhase(elapsedSeconds, rate, 6.0 / 1.7)
    val s = (((base + index * 0.37f) % 1f) + 1f) % 1f
    return if (s >= 1f) 0f else s
}

internal fun tipFlickerDx(elapsedSeconds: Double, rate: Float, halfW: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 2.1) * twoPi) * halfW * 0.07f +
        sin(reducedPhase(elapsedSeconds, rate, 3.7) * twoPi + 0.8f) * halfW * 0.03f
}

internal fun tiltDegrees(elapsedSeconds: Double, rate: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 0.9) * twoPi + 0.5f) * 2.0f
}

internal fun bobOffset(elapsedSeconds: Double, rate: Float, unit: Float): Float {
    val twoPi = 2f * PI.toFloat()
    return sin(reducedPhase(elapsedSeconds, rate, 2.2) * twoPi) * unit * 0.005f
}

internal fun sparkAlpha(seed: Float, intensity: Float): Float =
    sin(seed * PI.toFloat()) * 0.75f * intensity

internal fun frameElapsed(anchorSeconds: Double, startNanos: Long, nowNanos: Long): Double =
    anchorSeconds + (nowNanos - startNanos) / 1_000_000_000.0

internal fun breathScale(breath01: Float): Float = 0.97f + 0.06f * breath01

internal fun haloAlpha(breath01: Float, flash: Float): Float =
    (0.22f + 0.20f * breath01 + flash * 0.15f).coerceIn(0f, 1f)

/** Flame geometry aligned to fill the icon box like 🔥 (with small optical padding). */
internal class FlameGeometry(val s: Float) {
    // Fill ~84-90% of height with base anchored low for top-right badge readability.
    val w: Float = s * 0.78f
    val h: Float = s * 0.92f
    val cx: Float = s * 0.50f
    val baseY: Float = s * 0.965f
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

/** Active: red-orange outer flame like 🔥 (vivid orange top, deep red-orange base/edges). */
internal val ActiveBodyStops = arrayOf(
    0.00f to Color(0xFFFFA000), // near tip warmth
    0.18f to Color(0xFFFF7A00),
    0.40f to Color(0xFFFF5A1F),
    0.68f to Color(0xFFE83A00),
    1.00f to Color(0xFFC62828),
)

/** At-risk: same silhouette, cooled toward amber (still recognizably fire). */
internal val AtRiskBodyStops = arrayOf(
    0.00f to Color(0xFFE6A85A),
    0.22f to Color(0xFFD88C3A),
    0.48f to Color(0xFFC86A28),
    0.74f to Color(0xFFAE4E20),
    1.00f to Color(0xFF8E3A18),
)

/** Dormant: dim embered fire (not beige/ash), still reads as flame. */
internal val DormantBodyStops = arrayOf(
    0.00f to Color(0xFFC09058),
    0.28f to Color(0xFFA87440),
    0.58f to Color(0xFF865430),
    0.82f to Color(0xFF603820),
    1.00f to Color(0xFF4A2A18),
)

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
            0.00f to Color(0xFFFFF59D),
            0.28f to Color(0xFFFFD54F),
            0.58f to Color(0xFFFFA000),
            0.88f to Color(0xFFFF6D00),
            1.00f to Color(0x00FF6D00),
            startY = geo.baseY - geo.h * 0.10f, endY = geo.baseY - geo.h * 0.88f,
        )
        StreakFlameState.AtRisk -> Brush.verticalGradient(
            0.00f to Color(0xFFFFE8A8),
            0.30f to Color(0xFFF7C85A),
            0.60f to Color(0xFFE08A2E),
            1.00f to Color(0x00C86A20),
            startY = geo.baseY - geo.h * 0.10f, endY = geo.baseY - geo.h * 0.84f,
        )
        StreakFlameState.Inactive -> Brush.verticalGradient(
            0.00f to Color(0x99E0B070),
            0.40f to Color(0x66C88840),
            0.75f to Color(0x33906030),
            1.00f to Color(0x00604028),
            startY = geo.baseY - geo.h * 0.10f, endY = geo.baseY - geo.h * 0.80f,
        )
    }
    val core = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xFFFFFFFF),
            0.18f to Color(0xFFFFFDE7),
            0.42f to Color(0xFFFFF176),
            0.72f to Color(0xFFFFD54F),
            1.00f to Color(0x00FFB300),
            center = Offset(geo.cx + geo.w * 0.02f, geo.baseY - geo.h * 0.28f),
            radius = geo.h * 0.26f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xFFFFF8E1),
            0.30f to Color(0xFFFFE082),
            0.62f to Color(0xFFF4A840),
            1.00f to Color(0x00D07020),
            center = Offset(geo.cx + geo.w * 0.02f, geo.baseY - geo.h * 0.26f),
            radius = geo.h * 0.22f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0x66FFD8A0),
            0.45f to Color(0x30C89050),
            1.00f to Color(0x00705030),
            center = Offset(geo.cx + geo.w * 0.01f, geo.baseY - geo.h * 0.24f),
            radius = geo.h * 0.18f,
        )
    }
    val bloomCentre = Offset(geo.cx, geo.baseY - geo.h * 0.42f)
    val bloom = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x3DFF6D00); StreakFlameState.AtRisk -> Color(0x24E07A28); StreakFlameState.Inactive -> Color(0x10805030) },
        0.55f to when (state) { StreakFlameState.Active -> Color(0x14FF3D00); StreakFlameState.AtRisk -> Color(0x10C86020); StreakFlameState.Inactive -> Color(0x08604028) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 0.95f,
    )
    val bloomInner = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x2EFFC400); StreakFlameState.AtRisk -> Color(0x1FE0B060); StreakFlameState.Inactive -> Color(0x0C907050) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 0.50f,
    )
    val contact = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x22FF5722); StreakFlameState.AtRisk -> Color(0x14C87028); StreakFlameState.Inactive -> Color(0x08604028) },
        1.00f to Color(0x00000000),
        center = Offset(geo.cx, geo.baseY - geo.h * 0.02f), radius = geo.w * 0.70f,
    )
    val hotBase = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xF0FFFFFF), 0.30f to Color(0xCCFFFDE7), 0.60f to Color(0x66FFF59D), 1.00f to Color(0x00FFC107),
            center = Offset(geo.cx + geo.w * 0.02f, geo.baseY - geo.h * 0.20f), radius = geo.w * 0.26f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xD0FFF8E1), 0.42f to Color(0x88FFE082), 1.00f to Color(0x00D08030),
            center = Offset(geo.cx + geo.w * 0.02f, geo.baseY - geo.h * 0.19f), radius = geo.w * 0.22f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0x55E8C898), 0.50f to Color(0x20B08858), 1.00f to Color(0x00000000),
            center = Offset(geo.cx + geo.w * 0.01f, geo.baseY - geo.h * 0.18f), radius = geo.w * 0.18f,
        )
    }
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
    private val brushes = HashMap<StreakFlameState, FlameBrushSet>()
    private val paint = Paint()
    private var layerSize: Size = Size.Unspecified
    private var layerRect: Rect = Rect.Zero

    fun draw(scope: DrawScope, state: StreakFlameState, elapsed: Double, flashIn: Float, forceAnimate: Boolean = false) {
        val flash = flashIn.coerceIn(0f, 1f)
        val brush = brushes.getOrPut(state) { brushSet(state, geometry) }
        val geo = geometry
        val animate = state != StreakFlameState.Inactive || forceAnimate
        val t = if (animate) elapsed else STATIC_FLAME_SECONDS
        // Force-animated sheet/dormant flames should feel "alive/gif-like" but not frantic.
        val baseRate = when {
            state == StreakFlameState.Inactive && forceAnimate -> 0.72f
            state == StreakFlameState.Inactive -> 0.45f
            else -> flickerRate(state)
        }
        val rate = if (animate) baseRate else 0f
        val baseIntensity = flameIntensity(state)
        val intensity = if (forceAnimate && state == StreakFlameState.Inactive) 0.62f else baseIntensity

        val tipDx = if (animate) tipFlickerDx(t, rate, geo.w * 0.5f) * intensity else 0f
        val tilt = if (animate) tiltDegrees(t, rate) * intensity else 0f
        val bob = if (animate) bobOffset(t, rate, geo.s) * intensity else 0f
        val breath01 = if (animate) breathPhase01(t) else 0.5f
        val breath = if (animate) breathScale(breath01) else 1f
        val twoPi = twoPi()
        val sway = if (animate) (
            sin(reducedPhase(t, rate, 1.0) * twoPi) * 0.022f +
                sin(reducedPhase(t, rate, 2.4) * twoPi + 1.3f) * 0.011f
            ) * geo.w * intensity else 0f
        val lickL = if (animate) sin(reducedPhase(t, rate, 1.5) * twoPi + 1.0f) * geo.w * 0.032f * intensity else 0f
        val lickR = if (animate) sin(reducedPhase(t, rate, 2.0) * twoPi + 2.2f) * geo.w * 0.026f * intensity else 0f
        val morph = if (animate) sin(reducedPhase(t, rate, 1.15) * twoPi + 0.6f) else 0f
        val innerDance = if (animate) sin(reducedPhase(t, rate, 2.6) * twoPi + 1.9f) else 0f
        val innerPulse = if (animate) (0.98f + 0.045f * sin(reducedPhase(t, rate, 2.1) * twoPi)) else 1f
        val flicker = if (animate) (0.94f + 0.06f * ((sin(reducedPhase(t, rate, 3.4) * twoPi) + 1f) * 0.5f)) else 1f
        val heightScale = 1f + (breath - 1f) * 0.60f + (if (animate) innerDance * 0.012f else 0f)
        val widen = 1f + (breath - 1f) * 0.40f
        val coreTop = when (state) {
            StreakFlameState.Active -> 0.62f
            StreakFlameState.AtRisk -> 0.58f
            StreakFlameState.Inactive -> 0.55f
        } + innerDance * 0.02f

        val glowPulse = if (animate) 0.90f + 0.10f * ((sin(reducedPhase(t, rate, 0.9) * twoPi + 0.4f) + 1f) * 0.5f) else 1f
        val bloomA = when (state) {
            StreakFlameState.Active -> (if (compact) 0.16f else 0.24f)
            StreakFlameState.AtRisk -> (if (compact) 0.10f else 0.15f)
            StreakFlameState.Inactive -> (if (compact) 0.06f else 0.09f)
        } * glowPulse + flash * 0.06f
        scope.drawCircle(brush = brush.bloom, radius = geo.h * (0.92f + (breath - 1f) * 0.6f), center = Offset(geo.cx, geo.baseY - geo.h * 0.43f), alpha = bloomA.coerceIn(0f, 0.35f))
        scope.drawCircle(brush = brush.bloomInner, radius = geo.h * (0.48f + (breath - 1f) * 0.4f), center = Offset(geo.cx, geo.baseY - geo.h * 0.38f), alpha = (bloomA * 0.85f).coerceIn(0f, 0.30f))
        if (!compact) {
            scope.drawOval(brush = brush.contact, topLeft = Offset(geo.cx - geo.w * 0.52f, geo.baseY - geo.h * 0.03f), size = Size(geo.w * 1.04f, geo.h * 0.10f))
        }

        scope.withTransform({
            translate(left = 0f, top = bob)
            rotate(degrees = tilt, pivot = Offset(geo.cx, geo.baseY * 0.92f))
        }) {
            // Outer red-orange flame body (🔥 silhouette) with organic morph.
            buildOuter(body, geo, tipDx, sway, heightScale, widen, lickL, lickR, morph)
            scope.drawPath(body, brush.body, alpha = flicker)

            // Inner orange-yellow flame tongue (dances faster, desynced).
            val innerSway = sway * 0.78f + if (animate) sin(reducedPhase(t, rate, 2.8) * twoPi + 0.7f) * geo.w * 0.008f * intensity else 0f
            val innerTipDx = tipDx * 0.75f + if (animate) sin(reducedPhase(t, rate, 3.1) * twoPi + 1.4f) * geo.w * 0.010f * intensity else 0f
            buildCore(core, geo, innerTipDx, innerSway, heightScale * innerPulse, widen * 0.97f, coreTop, innerDance)
            scope.drawPath(core, brush.inner, alpha = (0.92f + 0.08f * ((sin(reducedPhase(t, rate, 2.9) * twoPi + 0.9f) + 1f) * 0.5f)).coerceIn(0.86f, 1f))

            // Bright white/yellow hot core near lower center.
            val hotA = when (state) {
                StreakFlameState.Active -> (if (compact) 0.52f else 0.62f)
                StreakFlameState.AtRisk -> (if (compact) 0.34f else 0.42f)
                StreakFlameState.Inactive -> (if (compact) 0.20f else 0.26f)
            } + (breath - 1f) * 1.1f + flash * 0.12f
            val coreCx = geo.cx + geo.w * 0.015f + sway * 0.12f
            val coreCy = geo.baseY - geo.h * (0.24f - innerDance * 0.015f)
            scope.drawOval(
                brush = brush.hotBase,
                alpha = hotA.coerceIn(0f, 1f),
                topLeft = Offset(coreCx - geo.w * 0.145f, coreCy - geo.h * 0.075f),
                size = Size(geo.w * 0.29f, geo.h * 0.17f),
            )
            scope.drawOval(
                brush = brush.core,
                alpha = (hotA * 0.72f).coerceIn(0f, 0.75f),
                topLeft = Offset(coreCx - geo.w * 0.095f, coreCy - geo.h * 0.11f),
                size = Size(geo.w * 0.19f, geo.h * 0.16f),
            )

            if (animate && emberCount > 0) {
                drawEmbers(scope, geo, t, rate, intensity)
            }
        }
    }

    fun drawCrossFade(scope: DrawScope, from: StreakFlameState, to: StreakFlameState, elapsed: Double, flashIn: Float, progress: Float, forceAnimate: Boolean = false) {
        val p = progress.coerceIn(0f, 1f)
        if (from == to || p >= 1f) { draw(scope, to, elapsed, flashIn, forceAnimate); return }
        withLayerAlpha(scope, 1f - p) { draw(scope, from, elapsed, flashIn, forceAnimate) }
        withLayerAlpha(scope, p) { draw(scope, to, elapsed, flashIn, forceAnimate) }
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
            val y = geo.baseY - geo.h * (0.44f + seed * 0.46f)
            val x = geo.cx + sin(seed * 5f + i * 2.1f) * geo.w * 0.10f + geo.w * 0.02f
            val r = geo.s * (0.005f + 0.002f * (i % 2))
            scope.drawCircle(color = Color(0xFFFFF176), alpha = alpha.coerceIn(0f, 0.75f), radius = r, center = Offset(x, y))
        }
    }

    private fun drawAsh(scope: DrawScope, brush: FlameBrushSet, flash: Float) {
        val geo = geometry
        buildOuter(body, geo, 0f, 0f, 1f, 1f, 0f, 0f, 0f)
        scope.drawPath(body, brush.body)
        buildCore(core, geo, 0f, 0f, 1f, 1f, 0.60f, 0f)
        scope.drawPath(core, brush.inner)
        scope.drawOval(
            brush = brush.hotBase,
            alpha = (if (compact) 0.16f else 0.20f) + flash * 0.08f,
            topLeft = Offset(geo.cx + geo.w * 0.02f - geo.w * 0.13f, geo.baseY - geo.h * 0.26f),
            size = Size(geo.w * 0.26f, geo.h * 0.18f),
        )
    }
}

private fun twoPi(): Float = 2f * PI.toFloat()

/**
 * Emoji/Snapchat-like 🔥 outer silhouette:
 * - single pointed tip slightly right of center
 * - dominant right flame mass with rounded base
 * - smaller left lobe and a clear upper-left notch/dip
 * Reads clearly at badge sizes (20-24dp+).
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
    morph: Float,
) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float, ft: Float) = cx + fx * w * widen + sway * ft * ft + tipDx * ft * ft
    fun y(ft: Float) = baseY - ft * h * heightScale

    // Subtle organic shape morph for "live/GIF-like" burning.
    val tipX = 0.055f + morph * 0.010f
    val notchIn = 0.030f * morph
    val rightBulge = 0.018f * morph
    val leftLobe = 0.012f * morph

    path.reset()
    // Tip
    path.moveTo(x(tipX, 1.00f), y(1.00f))

    // Tip -> right shoulder -> right dominant bulge
    path.cubicTo(
        x(0.19f, 0.90f), y(0.90f),
        x(0.36f + rightBulge, 0.76f) + lickR * 0.45f, y(0.76f),
        x(0.43f + rightBulge, 0.60f) + lickR * 0.85f, y(0.60f),
    )
    path.cubicTo(
        x(0.50f + rightBulge, 0.46f), y(0.46f),
        x(0.50f + rightBulge * 0.7f, 0.30f), y(0.30f),
        x(0.40f, 0.16f), y(0.16f),
    )

    // Right side -> rounded base
    path.cubicTo(
        x(0.45f, 0.09f), y(0.09f),
        x(0.34f, 0.005f), y(0.005f),
        x(0.16f, -0.012f), y(-0.012f),
    )
    path.cubicTo(
        x(0.00f, -0.018f), y(-0.018f),
        x(-0.14f, -0.010f), y(-0.010f),
        x(-0.26f, 0.015f), y(0.015f),
    )

    // Base -> left lower lobe
    path.cubicTo(
        x(-0.41f, 0.05f), y(0.05f),
        x(-0.47f - leftLobe, 0.17f), y(0.17f),
        x(-0.42f - leftLobe, 0.30f), y(0.30f),
    )
    path.cubicTo(
        x(-0.38f - leftLobe * 0.7f, 0.39f), y(0.39f),
        x(-0.33f, 0.46f) + lickL * 0.35f, y(0.46f),
        x(-0.29f, 0.52f) + lickL * 0.55f, y(0.52f),
    )

    // Clear upper-left notch/dip (signature 🔥 detail) -> up to tip
    path.cubicTo(
        x(-0.23f + notchIn, 0.47f), y(0.47f),      // notch inwards
        x(-0.14f + notchIn * 0.6f, 0.58f), y(0.58f),
        x(-0.08f, 0.70f), y(0.70f),
    )
    path.cubicTo(
        x(-0.03f, 0.82f), y(0.82f),
        x(0.01f, 0.93f), y(0.93f),
        x(tipX, 1.00f), y(1.00f),
    )
    path.close()
}

/** Inner yellow/orange flame tongue (bright, prominent), like 🔥 inner layer. */
private fun buildCore(
    path: Path,
    geo: FlameGeometry,
    tipDx: Float,
    sway: Float,
    heightScale: Float,
    widen: Float,
    top: Float,
    morph: Float,
) {
    val w = geo.w
    val h = geo.h
    val cx = geo.cx
    val baseY = geo.baseY
    fun x(fx: Float, ft: Float) = cx + fx * w * widen + sway * ft * ft + tipDx * ft * ft * 0.65f
    fun y(ft: Float) = baseY - ft * h * heightScale
    val tipY = min(top, 0.62f) + morph * 0.03f

    path.reset()
    // Inner tongue anchored low in belly, reaches ~50-60% of outer height.
    path.moveTo(x(0.02f + morph * 0.01f, tipY), y(tipY))
    path.cubicTo(
        x(0.11f, tipY - 0.10f), y(tipY - 0.10f),
        x(0.19f, 0.46f), y(0.46f),
        x(0.18f, 0.36f), y(0.36f),
    )
    path.cubicTo(
        x(0.17f, 0.27f), y(0.27f),
        x(0.11f, 0.18f), y(0.18f),
        x(0.02f, 0.13f), y(0.13f),
    )
    path.cubicTo(
        x(-0.06f, 0.11f), y(0.11f),
        x(-0.14f, 0.17f), y(0.17f),
        x(-0.16f, 0.27f), y(0.27f),
    )
    path.cubicTo(
        x(-0.17f, 0.36f), y(0.36f),
        x(-0.12f, 0.46f), y(0.46f),
        x(-0.06f, 0.54f), y(0.54f),
    )
    path.cubicTo(
        x(-0.02f, 0.58f), y(0.58f),
        x(0.00f, tipY - 0.04f), y(tipY - 0.04f),
        x(0.02f + morph * 0.01f, tipY), y(tipY),
    )
    path.close()
}

private fun buildAsh(path: Path, geo: FlameGeometry) {
    buildOuter(path, geo, 0f, 0f, 1f, 1f, 0f, 0f, 0f)
}

private fun buildFacets(a: Path, b: Path, geo: FlameGeometry) {
    val w = geo.w; val h = geo.h; val cx = geo.cx; val baseY = geo.baseY
    fun x(fx: Float) = cx + fx * w
    fun y(ft: Float) = baseY - ft * h
    a.reset(); a.moveTo(x(-0.10f), y(0.10f)); a.lineTo(x(-0.03f), y(0.20f)); a.lineTo(x(0.02f), y(0.15f)); a.lineTo(x(-0.05f), y(0.07f)); a.close()
    b.reset(); b.moveTo(x(0.05f), y(0.16f)); b.lineTo(x(0.11f), y(0.20f)); b.lineTo(x(0.13f), y(0.09f)); b.lineTo(x(0.06f), y(0.07f)); b.close()
}

private fun buildShard(path: Path, geo: FlameGeometry, side: Int) {
    val w = geo.w; val h = geo.h; val cx = geo.cx; val baseY = geo.baseY
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
    /** When true, keeps a gentle live/burning motion even for [StreakFlameState.Inactive]
     *  (e.g. streak detail sheet). Reduce-motion still disables animation. */
    forceAnimate: Boolean = false,
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
    val animate = !reduced && windowFocused && onScreen && (state != StreakFlameState.Inactive || forceAnimate)

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
                painter.drawCrossFade(this, from, shown, elapsed, milestoneFlash + crossflash, fade.value, forceAnimate)
            } else {
                painter.draw(this, shown, elapsed, milestoneFlash + crossflash, forceAnimate)
            }
        }
    }
}
