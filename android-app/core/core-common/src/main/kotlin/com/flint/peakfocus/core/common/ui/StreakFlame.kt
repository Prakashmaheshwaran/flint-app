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
import androidx.compose.ui.graphics.Matrix
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
    val w: Float = s * 0.76f
    val h: Float = s * 0.90f
    val cx: Float = s * 0.50f
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

/** Active: vivid fire-emoji outer (orange tip into deep red-orange base). */
internal val ActiveBodyStops = arrayOf(
    0.00f to Color(0xFFFFA000),
    0.16f to Color(0xFFFF7A00),
    0.40f to Color(0xFFFF5A1F),
    0.68f to Color(0xFFE83A00),
    1.00f to Color(0xFFC62828),
)

/** At-risk: same silhouette, slightly cooled toward amber (still vivid fire). */
internal val AtRiskBodyStops = arrayOf(
    0.00f to Color(0xFFFF9A3A),
    0.22f to Color(0xFFF07A22),
    0.48f to Color(0xFFDE5A16),
    0.74f to Color(0xFFC43E10),
    1.00f to Color(0xFFA02808),
)

/** Dormant: dimmer fire, but still vivid red-orange/yellow — never a brown blob. */
internal val DormantBodyStops = arrayOf(
    0.00f to Color(0xFFFF8A3A),
    0.25f to Color(0xFFF06A20),
    0.55f to Color(0xFFD84A14),
    0.80f to Color(0xFFB03010),
    1.00f to Color(0xFF8A240C),
)

val StreakCoolTop: Color = Color(0xFFF2C892)
val StreakCoolDeep: Color = Color(0xFFC1731F)

internal fun brushSet(state: StreakFlameState, geo: FlameGeometry): FlameBrushSet {
    val bodyStops = when (state) {
        StreakFlameState.Active -> ActiveBodyStops
        StreakFlameState.AtRisk -> AtRiskBodyStops
        StreakFlameState.Inactive -> DormantBodyStops
    }
    val body = Brush.verticalGradient(*bodyStops, startY = geo.baseY - geo.h, endY = geo.baseY)
    val inner = when (state) {
        StreakFlameState.Active -> Brush.verticalGradient(
            0.00f to Color(0xFFFFF59D),
            0.30f to Color(0xFFFFD54F),
            0.62f to Color(0xFFFFC107),
            1.00f to Color(0xFFFF9D00),
            startY = geo.baseY - geo.h * 0.62f, endY = geo.baseY - geo.h * 0.04f,
        )
        StreakFlameState.AtRisk -> Brush.verticalGradient(
            0.00f to Color(0xFFFFE8A8),
            0.35f to Color(0xFFFFD060),
            0.70f to Color(0xFFFFA020),
            1.00f to Color(0xFFE07810),
            startY = geo.baseY - geo.h * 0.58f, endY = geo.baseY - geo.h * 0.04f,
        )
        StreakFlameState.Inactive -> Brush.verticalGradient(
            0.00f to Color(0xFFFFE08A),
            0.40f to Color(0xFFFFC24A),
            0.75f to Color(0xFFFFA020),
            1.00f to Color(0xFFE07010),
            startY = geo.baseY - geo.h * 0.55f, endY = geo.baseY - geo.h * 0.04f,
        )
    }
    val core = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xFFFFFFFF),
            0.28f to Color(0xFFFFFDE7),
            0.60f to Color(0xFFFFF176),
            1.00f to Color(0x00FFB300),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.26f),
            radius = geo.w * 0.26f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xFFFFF8E1),
            0.35f to Color(0xFFFFE082),
            1.00f to Color(0x00D07020),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.24f),
            radius = geo.w * 0.22f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0xCCFFFFFF),
            0.28f to Color(0xAAFFF9C4),
            0.62f to Color(0x66FFE082),
            1.00f to Color(0x00FFB300),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.22f),
            radius = geo.w * 0.20f,
        )
    }
    val bloomCentre = Offset(geo.cx, geo.baseY - geo.h * 0.40f)
    val bloom = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x55FF6D00); StreakFlameState.AtRisk -> Color(0x30E07A28); StreakFlameState.Inactive -> Color(0x10805030) },
        0.55f to when (state) { StreakFlameState.Active -> Color(0x22FF3D00); StreakFlameState.AtRisk -> Color(0x14C86020); StreakFlameState.Inactive -> Color(0x08604028) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 0.90f,
    )
    val bloomInner = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x44FFC400); StreakFlameState.AtRisk -> Color(0x28E0B060); StreakFlameState.Inactive -> Color(0x0C907050) },
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
            0.00f to Color(0xF2FFFFFF), 0.30f to Color(0xCCFFFDE7), 0.60f to Color(0x66FFF59D), 1.00f to Color(0x00FFC107),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.22f), radius = geo.w * 0.26f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xD0FFF8E1), 0.42f to Color(0x88FFE082), 1.00f to Color(0x00D08030),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.21f), radius = geo.w * 0.22f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0xE6FFFFFF), 0.30f to Color(0xBBFFF9C4), 0.60f to Color(0x55FFE082), 1.00f to Color(0x00FFB300),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.20f), radius = geo.w * 0.22f,
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

    // Exact Twemoji fire-emoji path geometry (36x36 viewBox), scaled to this icon size.
    private val scale: Float = geometry.s / 36f
    private val outerPath: Path = twemojiOuter(scale)
    private val innerPath: Path = twemojiInner(scale)

    fun draw(scope: DrawScope, state: StreakFlameState, elapsed: Double, flashIn: Float, forceAnimate: Boolean = false) {
        val flash = flashIn.coerceIn(0f, 1f)
        val brush = brushes.getOrPut(state) { brushSet(state, geometry) }
        val geo = geometry
        val animate = state != StreakFlameState.Inactive || forceAnimate
        val t = if (animate) elapsed else STATIC_FLAME_SECONDS
        val baseRate = when {
            state == StreakFlameState.Inactive && forceAnimate -> 0.72f
            state == StreakFlameState.Inactive -> 0.45f
            else -> flickerRate(state)
        }
        val rate = if (animate) baseRate else 0f
        val intensity = if (forceAnimate && state == StreakFlameState.Inactive) 0.62f else flameIntensity(state)
        val twoPi = 2f * PI.toFloat()

        // Base-anchored "living flame" motion: gentle sway + breath + flicker.
        val breath01 = if (animate) breathPhase01(t) else 0.5f
        val breath = if (animate) breathScale(breath01) else 1f
        // Base-anchored sway: the bottom stays planted and the upper body leans.
        val swayRad = if (animate) (tiltDegrees(t, rate) * 1.35f +
            sin(reducedPhase(t, rate, 2.6) * twoPi) * 1.05f) * intensity * (PI.toFloat() / 180f) * 0.55f else 0f
        val bob = 0f
        // Keep the silhouette stable; life comes from sway, inner dance and flicker.
        val scaleY = 1f
        val scaleX = 1f
        val flicker = if (animate) (0.93f + 0.07f * ((sin(reducedPhase(t, rate, 3.3) * twoPi) + 1f) * 0.5f)) else 1f

        // Inner tongue leads the outer slightly (faster, desynced).
        val innerScale = if (animate) (1f + 0.018f * sin(reducedPhase(t, rate, 2.2) * twoPi + 1.6f)) else 1f
        val innerShift = if (animate) sin(reducedPhase(t, rate, 1.7) * twoPi + 0.9f) * geo.s * 0.003f * intensity else 0f
        val innerAlpha = if (animate) (0.90f + 0.10f * ((sin(reducedPhase(t, rate, 2.8) * twoPi + 0.4f) + 1f) * 0.5f)) else 1f

        val glowPulse = if (animate) 0.90f + 0.10f * ((sin(reducedPhase(t, rate, 0.9) * twoPi + 0.4f) + 1f) * 0.5f) else 1f
        val bloomA = when (state) {
            StreakFlameState.Active -> (if (compact) 0.11f else 0.16f)
            StreakFlameState.AtRisk -> (if (compact) 0.07f else 0.10f)
            StreakFlameState.Inactive -> (if (compact) 0.04f else 0.06f)
        } * glowPulse + flash * 0.05f
        scope.drawCircle(brush = brush.bloom, radius = geo.h * 0.80f, center = Offset(geo.cx, geo.baseY - geo.h * 0.42f), alpha = bloomA.coerceIn(0f, 0.26f))
        scope.drawCircle(brush = brush.bloomInner, radius = geo.h * 0.44f, center = Offset(geo.cx, geo.baseY - geo.h * 0.36f), alpha = (bloomA * 0.85f).coerceIn(0f, 0.22f))
        if (!compact) {
            scope.drawOval(
                brush = brush.contact,
                topLeft = Offset(geo.cx - geo.w * 0.46f, geo.baseY - geo.h * 0.02f),
                size = Size(geo.w * 0.92f, geo.h * 0.09f),
            )
        }

        scope.withTransform({
            translate(left = 0f, top = bob)
            if (swayRad != 0f) {
                // x' = x + swayRad * (s - y): the base stays planted, the upper body sways.
                transform(Matrix().apply {
                    set(1, 0, -swayRad)
                    set(3, 0, swayRad * geo.s)
                })
            }
            // Slightly narrower than tall so it reads upright, like the fire emoji.
            scale(scaleX = scaleX * 0.95f, scaleY = scaleY, pivot = Offset(geo.cx, geo.s))
        }) {
            // Outer flame: exact fire-emoji silhouette. Keep the outer alpha stable so the
            // silhouette does not shimmer at the edges; the inner tongue carries the flicker.
            scope.drawPath(outerPath, brush.body)

            // Inner yellow-orange tongue: independent breathing/dance.
            scope.withTransform({
                translate(left = 0f, top = innerShift)
                scale(scaleX = innerScale, scaleY = innerScale, pivot = Offset(geo.cx, geo.s))
            }) {
                scope.drawPath(innerPath, brush.inner, alpha = innerAlpha.coerceIn(0.80f, 1f))
            }

            // White-hot core low in the belly.
            val hotA = when (state) {
                StreakFlameState.Active -> (if (compact) 0.54f else 0.66f)
                StreakFlameState.AtRisk -> (if (compact) 0.40f else 0.50f)
                StreakFlameState.Inactive -> (if (compact) 0.30f else 0.38f)
            } + (breath - 1f) * 1.1f + flash * 0.14f
            scope.drawOval(
                brush = brush.hotBase,
                alpha = hotA.coerceIn(0f, 1f),
                topLeft = Offset(geo.cx - geo.w * 0.13f, geo.baseY - geo.h * 0.34f),
                size = Size(geo.w * 0.26f, geo.h * 0.17f),
            )
            scope.drawOval(
                brush = brush.core,
                alpha = (hotA * 0.68f).coerceIn(0f, 0.70f),
                topLeft = Offset(geo.cx - geo.w * 0.08f, geo.baseY - geo.h * 0.36f),
                size = Size(geo.w * 0.16f, geo.h * 0.14f),
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
        scope.drawPath(outerPath, brush.body)
        scope.drawPath(innerPath, brush.inner)
        scope.drawOval(
            brush = brush.hotBase,
            alpha = (if (compact) 0.16f else 0.20f) + flash * 0.08f,
            topLeft = Offset(geo.cx - geo.w * 0.13f, geo.baseY - geo.h * 0.34f),
            size = Size(geo.w * 0.26f, geo.h * 0.17f),
        )
    }
}

private fun twoPi(): Float = 2f * PI.toFloat()

/** Exact Twemoji 1f525 outer flame path, scaled from its 36x36 viewBox. */
private fun twemojiOuter(scale: Float): Path {
    val p = Path()
    p.moveTo(35.0000f * scale, 19.0000f * scale)
    p.cubicTo(35.0000f * scale, 16.9380f * scale, 34.6330f * scale, 14.9610f * scale, 33.9600f * scale, 13.1320f * scale)
    p.cubicTo(33.5000f * scale, 18.5210f * scale, 30.6270f * scale, 21.2890f * scale, 27.6250f * scale, 20.0000f * scale)
    p.cubicTo(24.8130f * scale, 18.7920f * scale, 26.7080f * scale, 14.0830f * scale, 26.8480f * scale, 11.8360f * scale)
    p.cubicTo(27.0840f * scale, 8.0270f * scale, 26.8360f * scale, 3.6670f * scale, 19.9170f * scale, 0.0420f * scale)
    p.cubicTo(22.7920f * scale, 5.5420f * scale, 20.2500f * scale, 8.9590f * scale, 17.5840f * scale, 9.1670f * scale)
    p.cubicTo(14.6260f * scale, 9.3980f * scale, 11.9170f * scale, 6.6250f * scale, 12.9170f * scale, 2.1250f * scale)
    p.cubicTo(9.6790f * scale, 4.5110f * scale, 9.5850f * scale, 8.5270f * scale, 10.5840f * scale, 11.1250f * scale)
    p.cubicTo(11.6260f * scale, 13.8330f * scale, 10.5420f * scale, 16.0830f * scale, 8.0010f * scale, 16.3330f * scale)
    p.cubicTo(5.1610f * scale, 16.6130f * scale, 3.5830f * scale, 13.2920f * scale, 5.0380f * scale, 8.0000f * scale)
    p.cubicTo(2.5200f * scale, 10.9650f * scale, 1.0000f * scale, 14.8050f * scale, 1.0000f * scale, 19.0000f * scale)
    p.cubicTo(1.0000f * scale, 28.3890f * scale, 8.6110f * scale, 36.0000f * scale, 18.0000f * scale, 36.0000f * scale)
    p.cubicTo(27.3890f * scale, 36.0000f * scale, 35.0000f * scale, 28.3890f * scale, 35.0000f * scale, 19.0000f * scale)
    p.close()
    return p
}

/** Exact Twemoji 1f525 inner flame path, scaled from its 36x36 viewBox. */
private fun twemojiInner(scale: Float): Path {
    val p = Path()
    p.moveTo(28.3940f * scale, 23.9990f * scale)
    p.cubicTo(28.5420f * scale, 27.0830f * scale, 25.8330f * scale, 28.2920f * scale, 24.3750f * scale, 27.7080f * scale)
    p.cubicTo(22.2690f * scale, 26.8650f * scale, 22.8340f * scale, 25.4170f * scale, 22.2920f * scale, 22.4170f * scale)
    p.cubicTo(21.7500f * scale, 19.4170f * scale, 19.6670f * scale, 17.3340f * scale, 16.5840f * scale, 16.4170f * scale)
    p.cubicTo(18.8340f * scale, 22.7500f * scale, 15.3370f * scale, 25.0840f * scale, 13.5040f * scale, 25.5010f * scale)
    p.cubicTo(11.6320f * scale, 25.9270f * scale, 9.7510f * scale, 25.5000f * scale, 9.5360f * scale, 21.4940f * scale)
    p.cubicTo(7.3520f * scale, 23.6680f * scale, 6.0000f * scale, 26.6760f * scale, 6.0000f * scale, 30.0000f * scale)
    p.cubicTo(6.0000f * scale, 30.3680f * scale, 6.0230f * scale, 30.7300f * scale, 6.0550f * scale, 31.0900f * scale)
    p.cubicTo(9.1250f * scale, 34.1240f * scale, 13.3420f * scale, 36.0000f * scale, 18.0000f * scale, 36.0000f * scale)
    p.cubicTo(22.6580f * scale, 36.0000f * scale, 26.8750f * scale, 34.1240f * scale, 29.9450f * scale, 31.0900f * scale)
    p.cubicTo(29.9770f * scale, 30.7300f * scale, 30.0000f * scale, 30.3680f * scale, 30.0000f * scale, 30.0000f * scale)
    p.cubicTo(30.0000f * scale, 27.8130f * scale, 29.4160f * scale, 25.7640f * scale, 28.3940f * scale, 23.9990f * scale)
    p.close()
    return p
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
