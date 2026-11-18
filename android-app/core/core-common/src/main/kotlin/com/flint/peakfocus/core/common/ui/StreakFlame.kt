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
import androidx.compose.ui.graphics.PathMeasure
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
    StreakFlameState.AtRisk -> 0.84f
    StreakFlameState.Inactive -> 0.58f
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
    val orange: Brush,
    val yellow: Brush,
    val core: Brush,
    val bloom: Brush,
    val bloomInner: Brush,
    val contact: Brush,
    val hotBase: Brush,
    val ash: Brush,
)

/** Active: coral-red outer, like the Apple fire emoji. */
internal val ActiveBodyStops = arrayOf(
    0.00f to Color(0xFFF4513A),
    0.22f to Color(0xFFF15E3A),
    0.50f to Color(0xFFEC4A2A),
    0.78f to Color(0xFFE23A22),
    1.00f to Color(0xFFD32E1C),
)

/** At-risk: warm amber-red, still unmistakably fire. */
internal val AtRiskBodyStops = arrayOf(
    0.00f to Color(0xFFF2B060),
    0.25f to Color(0xFFE09040),
    0.55f to Color(0xFFD0702A),
    0.80f to Color(0xFFB0501E),
    1.00f to Color(0xFF8E3A14),
)

/** Dormant: dimmer coral-red fire — never a brown blob. */
internal val DormantBodyStops = arrayOf(
    0.00f to Color(0xFFE07050),
    0.28f to Color(0xFFD05A38),
    0.58f to Color(0xFFB84A2A),
    0.82f to Color(0xFF9C3A20),
    1.00f to Color(0xFF7E2E18),
)

val StreakCoolTop: Color = Color(0xFFF2C892)
val StreakCoolDeep: Color = Color(0xFFC1731F)

internal fun brushSet(state: StreakFlameState, geo: FlameGeometry): FlameBrushSet {
    val bodyStops = when (state) {
        StreakFlameState.Active -> ActiveBodyStops
        StreakFlameState.AtRisk -> AtRiskBodyStops
        // Even at 0 days the streak sheet/badge should read as the vivid fire emoji,
        // not a dim brown blob. Keep the identity; only motion slows down.
        StreakFlameState.Inactive -> ActiveBodyStops
    }
    val body = Brush.verticalGradient(*bodyStops, startY = geo.baseY - geo.h, endY = geo.baseY)
    val orange = when (state) {
        StreakFlameState.Active -> Brush.verticalGradient(
            0.00f to Color(0xFFF79B3A),
            0.45f to Color(0xFFF7B53A),
            1.00f to Color(0xFFFFC84A),
            startY = geo.baseY - geo.h * 0.82f, endY = geo.baseY,
        )
        StreakFlameState.AtRisk -> Brush.verticalGradient(
            0.00f to Color(0xFFE89A50),
            0.55f to Color(0xFFE8B060),
            1.00f to Color(0xFFF7C870),
            startY = geo.baseY - geo.h * 0.78f, endY = geo.baseY,
        )
        StreakFlameState.Inactive -> Brush.verticalGradient(
            0.00f to Color(0xFFF79B3A),
            0.45f to Color(0xFFF7B53A),
            1.00f to Color(0xFFFFC84A),
            startY = geo.baseY - geo.h * 0.82f, endY = geo.baseY,
        )
    }
    val yellow = when (state) {
        StreakFlameState.Active -> Brush.verticalGradient(
            0.00f to Color(0xFFFFD24A),
            0.55f to Color(0xFFFFE274),
            1.00f to Color(0xFFFFF1A8),
            startY = geo.baseY - geo.h * 0.62f, endY = geo.baseY,
        )
        StreakFlameState.AtRisk -> Brush.verticalGradient(
            0.00f to Color(0xFFF7C86A),
            1.00f to Color(0xFFF7E0A0),
            startY = geo.baseY - geo.h * 0.58f, endY = geo.baseY,
        )
        StreakFlameState.Inactive -> Brush.verticalGradient(
            0.00f to Color(0xFFFFD24A),
            0.55f to Color(0xFFFFE274),
            1.00f to Color(0xFFFFF1A8),
            startY = geo.baseY - geo.h * 0.62f, endY = geo.baseY,
        )
    }
    val core = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xFFFFFFFF),
            0.30f to Color(0xFFFFF6C8),
            0.68f to Color(0xFFFFE082),
            1.00f to Color(0x00FFD24A),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.20f),
            radius = geo.w * 0.30f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xFFFFF8E1),
            0.40f to Color(0xFFFFE082),
            1.00f to Color(0x00E8A040),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.19f),
            radius = geo.w * 0.26f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0xFFFFFFFF),
            0.30f to Color(0xFFFFF6C8),
            0.68f to Color(0xFFFFE082),
            1.00f to Color(0x00FFD24A),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.20f),
            radius = geo.w * 0.30f,
        )
    }
    val bloomCentre = Offset(geo.cx, geo.baseY - geo.h * 0.40f)
    val bloom = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x55F26A3A); StreakFlameState.AtRisk -> Color(0x30E09040); StreakFlameState.Inactive -> Color(0x12C05A38) },
        0.55f to when (state) { StreakFlameState.Active -> Color(0x22E8472A); StreakFlameState.AtRisk -> Color(0x14C0702A); StreakFlameState.Inactive -> Color(0x08A04A2A) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 0.92f,
    )
    val bloomInner = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x44FFD24A); StreakFlameState.AtRisk -> Color(0x28F0C070); StreakFlameState.Inactive -> Color(0x10D0A060) },
        1.00f to Color(0x00000000),
        center = bloomCentre, radius = geo.h * 0.52f,
    )
    val contact = Brush.radialGradient(
        0.00f to when (state) { StreakFlameState.Active -> Color(0x22E8472A); StreakFlameState.AtRisk -> Color(0x14C0702A); StreakFlameState.Inactive -> Color(0x08A04A2A) },
        1.00f to Color(0x00000000),
        center = Offset(geo.cx, geo.baseY - geo.h * 0.02f), radius = geo.w * 0.72f,
    )
    val hotBase = when (state) {
        StreakFlameState.Active -> Brush.radialGradient(
            0.00f to Color(0xF2FFFFFF), 0.28f to Color(0xCCFFF6C8), 0.62f to Color(0x66FFE082), 1.00f to Color(0x00FFC107),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.20f), radius = geo.w * 0.28f,
        )
        StreakFlameState.AtRisk -> Brush.radialGradient(
            0.00f to Color(0xD0FFF8E1), 0.42f to Color(0x88FFE082), 1.00f to Color(0x00D08030),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.19f), radius = geo.w * 0.24f,
        )
        StreakFlameState.Inactive -> Brush.radialGradient(
            0.00f to Color(0xF2FFFFFF), 0.28f to Color(0xCCFFF6C8), 0.62f to Color(0x66FFE082), 1.00f to Color(0x00FFC107),
            center = Offset(geo.cx, geo.baseY - geo.h * 0.20f), radius = geo.w * 0.28f,
        )
    }
    val ash = Brush.linearGradient(
        0.00f to Color(0xFF6A5644), 0.40f to Color(0xFF4A3A30), 0.72f to Color(0xFF332822), 1.00f to Color(0xFF201816),
        start = Offset(geo.cx, geo.baseY - geo.h * 0.32f), end = Offset(geo.cx, geo.baseY),
    )
    return FlameBrushSet(body, orange, yellow, core, bloom, bloomInner, contact, hotBase, ash)
}

internal class StreakFlamePainter(
    val geometry: FlameGeometry,
    private val compact: Boolean,
    private val emberCount: Int,
    private val fringeInner: Stroke,
    private val fringeOuter: Stroke,
) {
    private val brushes = HashMap<StreakFlameState, FlameBrushSet>()
    private val paint = Paint()
    private var layerSize: Size = Size.Unspecified
    private var layerRect: Rect = Rect.Zero

    // The reference fire emoji is tall: ~1.27 height / width.
    private val flameW: Float = geometry.s * 0.76f
    private val flameH: Float = geometry.s * 0.96f
    private val ox: Float = (geometry.s - flameW) * 0.5f
    private val oy: Float = geometry.s - flameH

    private val outerPath: Path = appleMainPath(flameW, flameH, ox, oy)
    private val lickPath: Path = appleLickPath(flameW, flameH, ox, oy)
    // Two inner layers traced from the reference and aligned on the same center.
    // The orange layer fills the interior; the yellow tongue sits inside it.
    private val orangePath: Path = appleInnerPath(flameW, flameH, ox, oy)
    private val yellowPath: Path = appleYellowTonguePath(flameW, flameH, ox, oy)

    fun draw(
        scope: DrawScope,
        state: StreakFlameState,
        elapsed: Double,
        flashIn: Float,
        forceAnimate: Boolean = false,
        sideSparks: Boolean = false,
    ) {
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
        val intensity = if (forceAnimate && state == StreakFlameState.Inactive) 0.72f else flameIntensity(state)
        val twoPi = 2f * PI.toFloat()

        val breath01 = if (animate) breathPhase01(t) else 0.5f
        val breath = if (animate) breathScale(breath01) else 1f
        // Base-anchored horizontal shear: the bottom stays planted, the upper body sways.
        val swayRad = if (animate) (tiltDegrees(t, rate) * 1.15f +
            sin(reducedPhase(t, rate, 2.6) * twoPi) * 0.8f) * intensity * (PI.toFloat() / 180f) * 0.64f else 0f
        // The reference GIF pulses at ~2.25 Hz (444 ms): visible squash-and-stretch
        // anchored at the base, with a slightly larger width change than height.
        val pulse = if (animate) sin(t * twoPi.toDouble() / 0.444).toFloat() else 0f
        val scaleY = 1f + 0.072f * pulse * intensity
        val scaleX = 1f - 0.095f * pulse * intensity
        val lickBob = if (animate) sin(reducedPhase(t, rate, 2.4) * twoPi + 1.1f) * geo.s * 0.007f * intensity else 0f
        val lickScale = if (animate) 1f + 0.035f * sin(reducedPhase(t, rate, 1.9) * twoPi + 0.4f) else 1f
        val innerPulse = if (animate) 1f + 0.030f * sin(reducedPhase(t, rate, 2.2) * twoPi + 1.6f) else 1f
        val yellowPulse = if (animate) 1f + 0.022f * sin(reducedPhase(t, rate, 2.8) * twoPi + 0.8f) else 1f
        val innerShift = if (animate) sin(reducedPhase(t, rate, 1.7) * twoPi + 0.9f) * geo.s * 0.004f * intensity else 0f
        val innerAlpha = if (animate) (0.94f + 0.06f * ((sin(reducedPhase(t, rate, 3.0) * twoPi) + 1f) * 0.5f)) else 1f

        val glowPulse = if (animate) 0.90f + 0.10f * ((sin(reducedPhase(t, rate, 0.9) * twoPi + 0.4f) + 1f) * 0.5f) else 1f
        val bloomA = when (state) {
            StreakFlameState.Active -> (if (compact) 0.12f else 0.18f)
            StreakFlameState.AtRisk -> (if (compact) 0.08f else 0.12f)
            StreakFlameState.Inactive -> (if (compact) 0.05f else 0.08f)
        } * glowPulse + flash * 0.05f
        scope.drawCircle(brush = brush.bloom, radius = geo.h * 0.86f, center = Offset(geo.cx, geo.baseY - geo.h * 0.43f), alpha = bloomA.coerceIn(0f, 0.30f))
        scope.drawCircle(brush = brush.bloomInner, radius = geo.h * 0.48f, center = Offset(geo.cx, geo.baseY - geo.h * 0.37f), alpha = (bloomA * 0.85f).coerceIn(0f, 0.26f))
        if (!compact) {
            scope.drawOval(
                brush = brush.contact,
                topLeft = Offset(geo.cx - geo.w * 0.46f, geo.baseY - geo.h * 0.02f),
                size = Size(geo.w * 0.92f, geo.h * 0.09f),
            )
        }

        scope.withTransform({
            translate(left = 0f, top = 0f)
            if (swayRad != 0f) {
                // x' = x + swayRad * (s - y): base planted, upper body sways.
                transform(Matrix().apply {
                    set(1, 0, -swayRad)
                    set(3, 0, swayRad * geo.s)
                })
            }
            scale(scaleX = scaleX, scaleY = scaleY, pivot = Offset(geo.cx, geo.s))
        }) {
            // Outer coral-red silhouette traced from the reference fire emoji.
            scope.drawPath(outerPath, brush.body)

            // Detached upper-left flame lick.
            scope.withTransform({
                translate(left = 0f, top = lickBob)
                scale(scaleX = lickScale, scaleY = lickScale, pivot = Offset(ox + flameW * 0.12f, oy + flameH * 0.32f))
            }) {
                scope.drawPath(lickPath, brush.body)
            }

            // The reference periodically grows a detached lick on the upper right too.
            val rightLickAlpha = if (animate) {
                (0.35f + 0.65f * ((sin(reducedPhase(t, rate, 1.4) * twoPi + 2.2f) + 1f) * 0.5f)) * 0.80f
            } else 0f
            if (rightLickAlpha > 0.03f) {
                scope.withTransform({
                    scale(scaleX = -1f, scaleY = 1f, pivot = Offset(geo.cx, oy + flameH * 0.5f))
                    translate(left = 0f, top = lickBob * 0.8f)
                }) {
                    scope.drawPath(lickPath, brush.body, alpha = rightLickAlpha.coerceIn(0f, 0.7f))
                }
            }

            // Centered rounded orange tongue.
            scope.withTransform({
                translate(left = 0f, top = innerShift)
                scale(scaleX = innerPulse, scaleY = innerPulse, pivot = Offset(geo.cx, geo.s))
            }) {
                scope.drawPath(orangePath, brush.orange, alpha = innerAlpha.coerceIn(0.82f, 1f))
            }
            // Smaller golden-yellow tongue, vertically aligned with the orange one.
            scope.withTransform({
                translate(left = 0f, top = innerShift * 0.55f)
                scale(scaleX = yellowPulse, scaleY = yellowPulse, pivot = Offset(geo.cx, geo.s))
            }) {
                scope.drawPath(yellowPath, brush.yellow, alpha = innerAlpha.coerceIn(0.82f, 1f))
            }

            // White-hot core low in the belly.
            val hotA = when (state) {
                StreakFlameState.Active -> (if (compact) 0.62f else 0.74f)
                StreakFlameState.AtRisk -> (if (compact) 0.44f else 0.54f)
                StreakFlameState.Inactive -> (if (compact) 0.32f else 0.40f)
            } + (breath - 1f) * 1.0f + flash * 0.12f
            val coreCx = geo.cx
            val coreCy = oy + flameH * (0.80f - 0.008f * (innerAlpha - 1f) * 10f)
            scope.drawOval(
                brush = brush.hotBase,
                alpha = hotA.coerceIn(0f, 1f),
                topLeft = Offset(coreCx - flameW * 0.17f, coreCy - flameH * 0.14f),
                size = Size(flameW * 0.34f, flameH * 0.28f),
            )
            scope.drawOval(
                brush = brush.core,
                alpha = (hotA * 0.80f).coerceIn(0f, 0.82f),
                topLeft = Offset(coreCx - flameW * 0.115f, coreCy - flameH * 0.105f),
                size = Size(flameW * 0.23f, flameH * 0.21f),
            )

            if (animate && emberCount > 0) {
                drawEmbers(scope, geo, t, rate, intensity)
            }
            // Optional celebration sparkle beside the silhouette. Extra droplets only:
            // the outer path above is never modified, so the flame stays bit-identical
            // to previous renders when sideSparks is off.
            if (animate && sideSparks) {
                drawSideSparks(scope, t, rate, intensity)
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
        forceAnimate: Boolean = false,
        sideSparks: Boolean = false,
    ) {
        val p = progress.coerceIn(0f, 1f)
        if (from == to || p >= 1f) { draw(scope, to, elapsed, flashIn, forceAnimate, sideSparks); return }
        withLayerAlpha(scope, 1f - p) { draw(scope, from, elapsed, flashIn, forceAnimate, sideSparks) }
        withLayerAlpha(scope, p) { draw(scope, to, elapsed, flashIn, forceAnimate, sideSparks) }
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
            val y = oy + flameH * (0.44f + seed * 0.46f)
            val x = geo.cx + sin(seed * 5f + i * 2.1f) * flameW * 0.10f + flameW * 0.02f
            val r = geo.s * (0.005f + 0.002f * (i % 2))
            scope.drawCircle(color = Color(0xFFFFF176), alpha = alpha.coerceIn(0f, 0.75f), radius = r, center = Offset(x, y))
        }
    }

    /**
     * Celebration sparkle: 2-4 small fire droplets per side, outside the silhouette.
     *
     * Positions are seeded from [sparkSeed]/[sparkAlpha] so each droplet spawns, rises and
     * fades on its own phase. Nothing here touches the outer path, its alpha, or the
     * ember budget, so the flame body is pixel-identical when [draw] is called with
     * `sideSparks = false`.
     */
    private fun drawSideSparks(scope: DrawScope, t: Double, rate: Float, intensity: Float) {
        val perSide = sideSparkCountFor(emberCount)
        val centreX = ox + flameW * 0.5f
        for (side in SIDE_SPARK_SIDES) {
            for (i in 0 until perSide) {
                val phaseIndex = i + if (side < 0) 0 else SIDE_SPARK_PHASE_OFFSET
                val seed = sparkSeed(t, rate, phaseIndex)
                // Keep each dash bright for most of its life, easing only at birth/death.
                val alpha = (sideSparkAlpha(seed) * SIDE_SPARK_ALPHA * (0.72f + 0.28f * intensity)).coerceIn(0f, 0.95f)
                if (alpha <= 0.03f) continue
                val slot = if (perSide <= 1) 0f else i.toFloat() / (perSide - 1).toFloat()
                val startY = SIDE_SPARK_START_BOTTOM - SIDE_SPARK_START_SPAN * slot
                val yFrac = startY - seed * SIDE_SPARK_RISE
                // Always measure against the wider of the start/current height, then push
                // outward, so a rising droplet never travels back inside the silhouette.
                val edge = maxOf(sideEdgeHalfWidth(startY), sideEdgeHalfWidth(yFrac))
                val drift = SIDE_SPARK_DRIFT_MIN + seed * SIDE_SPARK_DRIFT_SPAN
                val wobble = sin(seed * 5f + i * 1.7f) * 0.012f
                val x = centreX + side * (edge + drift + wobble) * flameW
                val y = oy + flameH * yFrac
                val r = geometry.s * (SIDE_SPARK_RADIUS + SIDE_SPARK_RADIUS_SPREAD * (i % 2))
                // Subtle halo, then the droplet body, then a hot pinpoint highlight.
                scope.drawCircle(color = SideSparkGlow, alpha = alpha * 0.10f, radius = r * 1.8f, center = Offset(x, y))
                // Bright red-orange rim, golden body, white-hot tip — a miniature flame,
                // not a dark ring. The rim is only ~0.10r wider than the body.
                scope.drawOval(
                    color = SideSparkRim,
                    alpha = alpha * 0.90f,
                    topLeft = Offset(x - r * 0.725f, y - r * 1.40f),
                    size = Size(r * 1.45f, r * 2.80f),
                )
                scope.drawOval(
                    color = SideSparkBody,
                    alpha = alpha,
                    topLeft = Offset(x - r * 0.625f, y - r * 1.25f),
                    size = Size(r * 1.25f, r * 2.50f),
                )
                scope.drawCircle(color = SideSparkHot, alpha = alpha * 0.85f, radius = r * 0.40f, center = Offset(x, y - r * 0.50f))
            }
        }
    }

    private fun drawAsh(scope: DrawScope, brush: FlameBrushSet, flash: Float) {
        scope.drawPath(outerPath, brush.body)
        scope.drawPath(orangePath, brush.orange)
        scope.drawPath(yellowPath, brush.yellow)
        scope.drawOval(
            brush = brush.hotBase,
            alpha = (if (compact) 0.20f else 0.26f) + flash * 0.08f,
            topLeft = Offset((ox + flameW * 0.5f) - flameW * 0.17f, oy + flameH * 0.66f),
            size = Size(flameW * 0.34f, flameH * 0.28f),
        )
    }
}

private fun twoPi(): Float = 2f * PI.toFloat()

/**
 * Erode a closed flame silhouette by a constant [inset] and keep only the lower arc
 * (below [topY]), closing it with a pointed cap at [spineX]. This produces the even
 * inner frame of a real fire emoji instead of a uniformly scaled copy.
 */
private fun erodedInnerPath(
    outer: Path,
    inset: Float,
    topY: Float,
    spineX: Float,
): Path {
    val measure = PathMeasure()
    measure.setPath(outer, true)
    val length = measure.length
    if (length <= 0f) return Path()
    val samples = 140
    val cx = spineX
    val cy = topY + (outer.getBounds().height * 0.28f)
    val points = ArrayList<Offset>(samples)
    for (i in 0 until samples) {
        val distance = length * i / samples
        val p = measure.getPosition(distance)
        val tangent = measure.getTangent(distance)
        var nx = -tangent.y
        var ny = tangent.x
        // Point the normal toward the flame's lower-center.
        val toCenterX = cx - p.x
        val toCenterY = cy - p.y
        if (nx * toCenterX + ny * toCenterY < 0f) {
            nx = -nx
            ny = -ny
        }
        points.add(Offset(p.x + nx * inset, p.y + ny * inset))
    }
    // The closed contour starts at the tip; keep the lower arc only.
    val lower = points.filter { it.y >= topY }
    if (lower.size < 6) return Path()
    val path = Path()
    val first = lower.first()
    val last = lower.last()
    path.moveTo(first.x, first.y)
    for (i in 1 until lower.size) {
        val p = lower[i]
        path.lineTo(p.x, p.y)
    }
    // Close with a pointed cap at the upper spine, then back to the first lower point.
    path.lineTo(spineX, topY)
    path.lineTo(first.x, first.y)
    path.close()
    return path
}

/** Left (-1) and right (+1) sides for the optional celebration droplets. */
private val SIDE_SPARK_SIDES = intArrayOf(-1, 1)

/** Keeps the two sides on different phases of the [sparkSeed] cycle. */
private const val SIDE_SPARK_PHASE_OFFSET = 4

/** Droplets per side track the rising-ember size tiers: 2 (>=64dp), 3 (>=128dp), 4 (hero). */
private fun sideSparkCountFor(emberCount: Int): Int = when {
    emberCount >= 3 -> 4
    emberCount >= 2 -> 3
    else -> 2
}

/** Smooth on/off envelope: bright for most of the dash's life, fading at both ends. */
private fun sideSparkAlpha(seed: Float): Float {
    val edge = minOf(seed / 0.15f, (1f - seed) / 0.15f).coerceIn(0f, 1f)
    return edge * edge * (3f - 2f * edge)
}

/** Droplets spawn between these fractions of the flame height (lower sides). */
private const val SIDE_SPARK_START_BOTTOM = 0.92f
private const val SIDE_SPARK_START_SPAN = 0.30f

/** Upward travel across a droplet's life, as a fraction of the flame height. */
private const val SIDE_SPARK_RISE = 0.16f

/** Outward travel across a droplet's life, as a fraction of the flame width. */
private const val SIDE_SPARK_DRIFT_MIN = 0.020f
private const val SIDE_SPARK_DRIFT_SPAN = 0.075f

/** Peak droplet alpha stays under the rising embers so it reads as a sparkle, not debris. */
private const val SIDE_SPARK_ALPHA = 1.0f

private const val SIDE_SPARK_RADIUS = 0.018f
private const val SIDE_SPARK_RADIUS_SPREAD = 0.0025f

private val SideSparkGlow = Color(0xFFFFA43A)
private val SideSparkRim = Color(0xFFFF6A3A)
private val SideSparkBody = Color(0xFFFFD24A)
private val SideSparkHot = Color(0xFFFFF6C8)

/**
 * Distance from the flame centre to its outer edge, as a fraction of the flame width,
 * sampled from [appleMainPath] between 0.55 and 0.95 of the flame height.
 */
private val SIDE_EDGE_Y = floatArrayOf(0.55f, 0.64f, 0.72f, 0.80f, 0.87f, 0.95f)
private val SIDE_EDGE_HALF_W = floatArrayOf(0.478f, 0.500f, 0.478f, 0.440f, 0.398f, 0.300f)

/** Linearly interpolated silhouette half-width, clamped to the sampled band. */
private fun sideEdgeHalfWidth(yFrac: Float): Float {
    val y = yFrac.coerceIn(SIDE_EDGE_Y.first(), SIDE_EDGE_Y.last())
    var i = 0
    while (i < SIDE_EDGE_Y.size - 2 && y > SIDE_EDGE_Y[i + 1]) i++
    val y0 = SIDE_EDGE_Y[i]
    val y1 = SIDE_EDGE_Y[i + 1]
    val f = if (y1 > y0) (y - y0) / (y1 - y0) else 0f
    return SIDE_EDGE_HALF_W[i] + (SIDE_EDGE_HALF_W[i + 1] - SIDE_EDGE_HALF_W[i]) * f
}

/** Smooth inset flame tongue, like the orange/yellow layers of the reference emoji. */
/**
 * A centered, rounded flame tongue for the inner layers.
 * Unlike the earlier teardrop it has a soft cap rather than a sharp triangular point,
 * so the inner frame stays aligned and reads as a real flame tongue.
 */
private fun appleInnerPath(w: Float, h: Float, ox: Float, oy: Float): Path {
    val p = Path()
    p.moveTo((ox + 0.52046f * w), (oy + 0.17505f * h))
    p.cubicTo((ox + 0.51961f * w), (oy + 0.18176f * h), (ox + 0.52451f * w), (oy + 0.20188f * h), (ox + 0.52302f * w), (oy + 0.21529f * h))
    p.cubicTo((ox + 0.52153f * w), (oy + 0.22871f * h), (ox + 0.51854f * w), (oy + 0.24212f * h), (ox + 0.51151f * w), (oy + 0.25553f * h))
    p.cubicTo((ox + 0.50448f * w), (oy + 0.26895f * h), (ox + 0.49275f * w), (oy + 0.28236f * h), (ox + 0.48082f * w), (oy + 0.29577f * h))
    p.cubicTo((ox + 0.46888f * w), (oy + 0.30919f * h), (ox + 0.45311f * w), (oy + 0.32260f * h), (ox + 0.43990f * w), (oy + 0.33602f * h))
    p.cubicTo((ox + 0.42668f * w), (oy + 0.34943f * h), (ox + 0.41262f * w), (oy + 0.36284f * h), (ox + 0.40153f * w), (oy + 0.37626f * h))
    p.cubicTo((ox + 0.39045f * w), (oy + 0.38967f * h), (ox + 0.38214f * w), (oy + 0.40309f * h), (ox + 0.37340f * w), (oy + 0.41650f * h))
    p.cubicTo((ox + 0.36466f * w), (oy + 0.42991f * h), (ox + 0.35571f * w), (oy + 0.44333f * h), (ox + 0.34910f * w), (oy + 0.45674f * h))
    p.cubicTo((ox + 0.34250f * w), (oy + 0.47015f * h), (ox + 0.33653f * w), (oy + 0.48357f * h), (ox + 0.33376f * w), (oy + 0.49698f * h))
    p.cubicTo((ox + 0.33099f * w), (oy + 0.51040f * h), (ox + 0.33397f * w), (oy + 0.52381f * h), (ox + 0.33248f * w), (oy + 0.53722f * h))
    p.cubicTo((ox + 0.33099f * w), (oy + 0.55064f * h), (ox + 0.35934f * w), (oy + 0.56405f * h), (ox + 0.32481f * w), (oy + 0.57746f * h))
    p.cubicTo((ox + 0.29028f * w), (oy + 0.59088f * h), (ox + 0.15878f * w), (oy + 0.60429f * h), (ox + 0.12532f * w), (oy + 0.61771f * h))
    p.cubicTo((ox + 0.09186f * w), (oy + 0.63112f * h), (ox + 0.12361f * w), (oy + 0.64453f * h), (ox + 0.12404f * w), (oy + 0.65795f * h))
    p.cubicTo((ox + 0.12447f * w), (oy + 0.67136f * h), (ox + 0.12511f * w), (oy + 0.68478f * h), (ox + 0.12788f * w), (oy + 0.69819f * h))
    p.cubicTo((ox + 0.13065f * w), (oy + 0.71160f * h), (ox + 0.13598f * w), (oy + 0.72502f * h), (ox + 0.14066f * w), (oy + 0.73843f * h))
    p.cubicTo((ox + 0.14535f * w), (oy + 0.75184f * h), (ox + 0.14940f * w), (oy + 0.76526f * h), (ox + 0.15601f * w), (oy + 0.77867f * h))
    p.cubicTo((ox + 0.16262f * w), (oy + 0.79209f * h), (ox + 0.17306f * w), (oy + 0.80550f * h), (ox + 0.18031f * w), (oy + 0.81891f * h))
    p.cubicTo((ox + 0.18755f * w), (oy + 0.83233f * h), (ox + 0.19800f * w), (oy + 0.84574f * h), (ox + 0.19949f * w), (oy + 0.85915f * h))
    p.cubicTo((ox + 0.20098f * w), (oy + 0.87257f * h), (ox + 0.18713f * w), (oy + 0.88598f * h), (ox + 0.18926f * w), (oy + 0.89940f * h))
    p.cubicTo((ox + 0.19139f * w), (oy + 0.91281f * h), (ox + 0.19416f * w), (oy + 0.92622f * h), (ox + 0.21228f * w), (oy + 0.93964f * h))
    p.cubicTo((ox + 0.23039f * w), (oy + 0.95305f * h), (ox + 0.27664f * w), (oy + 0.97150f * h), (ox + 0.29795f * w), (oy + 0.97988f * h))
    p.cubicTo((ox + 0.31927f * w), (oy + 0.98826f * h), (ox + 0.29113f * w), (oy + 0.98826f * h), (ox + 0.34015f * w), (oy + 0.98994f * h))
    p.cubicTo((ox + 0.38917f * w), (oy + 0.99162f * h), (ox + 0.54092f * w), (oy + 0.99162f * h), (ox + 0.59207f * w), (oy + 0.98994f * h))
    p.cubicTo((ox + 0.64322f * w), (oy + 0.98826f * h), (ox + 0.61722f * w), (oy + 0.98826f * h), (ox + 0.64706f * w), (oy + 0.97988f * h))
    p.cubicTo((ox + 0.67690f * w), (oy + 0.97150f * h), (ox + 0.74339f * w), (oy + 0.95305f * h), (ox + 0.77110f * w), (oy + 0.93964f * h))
    p.cubicTo((ox + 0.79881f * w), (oy + 0.92622f * h), (ox + 0.80648f * w), (oy + 0.91281f * h), (ox + 0.81330f * w), (oy + 0.89940f * h))
    p.cubicTo((ox + 0.82012f * w), (oy + 0.88598f * h), (ox + 0.80904f * w), (oy + 0.87257f * h), (ox + 0.81202f * w), (oy + 0.85915f * h))
    p.cubicTo((ox + 0.81500f * w), (oy + 0.84574f * h), (ox + 0.82353f * w), (oy + 0.83233f * h), (ox + 0.83120f * w), (oy + 0.81891f * h))
    p.cubicTo((ox + 0.83887f * w), (oy + 0.80550f * h), (ox + 0.86296f * w), (oy + 0.79209f * h), (ox + 0.85806f * w), (oy + 0.77867f * h))
    p.cubicTo((ox + 0.85315f * w), (oy + 0.76526f * h), (ox + 0.81117f * w), (oy + 0.75184f * h), (ox + 0.80179f * w), (oy + 0.73843f * h))
    p.cubicTo((ox + 0.79241f * w), (oy + 0.72502f * h), (ox + 0.79923f * w), (oy + 0.71160f * h), (ox + 0.80179f * w), (oy + 0.69819f * h))
    p.cubicTo((ox + 0.80435f * w), (oy + 0.68478f * h), (ox + 0.81309f * w), (oy + 0.67136f * h), (ox + 0.81714f * w), (oy + 0.65795f * h))
    p.cubicTo((ox + 0.82118f * w), (oy + 0.64453f * h), (ox + 0.84783f * w), (oy + 0.63112f * h), (ox + 0.82609f * w), (oy + 0.61771f * h))
    p.cubicTo((ox + 0.80435f * w), (oy + 0.60429f * h), (ox + 0.71249f * w), (oy + 0.59088f * h), (ox + 0.68670f * w), (oy + 0.57746f * h))
    p.cubicTo((ox + 0.66091f * w), (oy + 0.56405f * h), (ox + 0.67285f * w), (oy + 0.55064f * h), (ox + 0.67136f * w), (oy + 0.53722f * h))
    p.cubicTo((ox + 0.66986f * w), (oy + 0.52381f * h), (ox + 0.67583f * w), (oy + 0.51040f * h), (ox + 0.67775f * w), (oy + 0.49698f * h))
    p.cubicTo((ox + 0.67967f * w), (oy + 0.48357f * h), (ox + 0.68159f * w), (oy + 0.47015f * h), (ox + 0.68286f * w), (oy + 0.45674f * h))
    p.cubicTo((ox + 0.68414f * w), (oy + 0.44333f * h), (ox + 0.68542f * w), (oy + 0.42991f * h), (ox + 0.68542f * w), (oy + 0.41650f * h))
    p.cubicTo((ox + 0.68542f * w), (oy + 0.40309f * h), (ox + 0.68521f * w), (oy + 0.38967f * h), (ox + 0.68286f * w), (oy + 0.37626f * h))
    p.cubicTo((ox + 0.68052f * w), (oy + 0.36284f * h), (ox + 0.67668f * w), (oy + 0.34943f * h), (ox + 0.67136f * w), (oy + 0.33602f * h))
    p.cubicTo((ox + 0.66603f * w), (oy + 0.32260f * h), (ox + 0.65899f * w), (oy + 0.30919f * h), (ox + 0.65090f * w), (oy + 0.29577f * h))
    p.cubicTo((ox + 0.64280f * w), (oy + 0.28236f * h), (ox + 0.63406f * w), (oy + 0.26895f * h), (ox + 0.62276f * w), (oy + 0.25553f * h))
    p.cubicTo((ox + 0.61147f * w), (oy + 0.24212f * h), (ox + 0.59889f * w), (oy + 0.22871f * h), (ox + 0.58312f * w), (oy + 0.21529f * h))
    p.cubicTo((ox + 0.56735f * w), (oy + 0.20188f * h), (ox + 0.53858f * w), (oy + 0.18176f * h), (ox + 0.52813f * w), (oy + 0.17505f * h))
    p.cubicTo((ox + 0.51769f * w), (oy + 0.16834f * h), (ox + 0.52131f * w), (oy + 0.16834f * h), (ox + 0.52046f * w), (oy + 0.17505f * h))
    p.close()
    return p
}

private fun appleYellowPath(w: Float, h: Float, ox: Float, oy: Float): Path {
    val p = Path()
    p.moveTo((ox + 0.51662f * w), (oy + 0.47887f * h))
    p.cubicTo((ox + 0.49979f * w), (oy + 0.48558f * h), (ox + 0.48593f * w), (oy + 0.50570f * h), (ox + 0.47570f * w), (oy + 0.51911f * h))
    p.cubicTo((ox + 0.46547f * w), (oy + 0.53253f * h), (ox + 0.47101f * w), (oy + 0.54594f * h), (ox + 0.45524f * w), (oy + 0.55936f * h))
    p.cubicTo((ox + 0.43947f * w), (oy + 0.57277f * h), (ox + 0.39727f * w), (oy + 0.58618f * h), (ox + 0.38107f * w), (oy + 0.59960f * h))
    p.cubicTo((ox + 0.36488f * w), (oy + 0.61301f * h), (ox + 0.37042f * w), (oy + 0.62643f * h), (ox + 0.35806f * w), (oy + 0.63984f * h))
    p.cubicTo((ox + 0.34569f * w), (oy + 0.65325f * h), (ox + 0.32310f * w), (oy + 0.66667f * h), (ox + 0.30691f * w), (oy + 0.68008f * h))
    p.cubicTo((ox + 0.29071f * w), (oy + 0.69349f * h), (ox + 0.26897f * w), (oy + 0.70691f * h), (ox + 0.26087f * w), (oy + 0.72032f * h))
    p.cubicTo((ox + 0.25277f * w), (oy + 0.73374f * h), (ox + 0.26130f * w), (oy + 0.74715f * h), (ox + 0.25831f * w), (oy + 0.76056f * h))
    p.cubicTo((ox + 0.25533f * w), (oy + 0.77398f * h), (ox + 0.24638f * w), (oy + 0.78739f * h), (ox + 0.24297f * w), (oy + 0.80080f * h))
    p.cubicTo((ox + 0.23956f * w), (oy + 0.81422f * h), (ox + 0.23679f * w), (oy + 0.82763f * h), (ox + 0.23785f * w), (oy + 0.84105f * h))
    p.cubicTo((ox + 0.23892f * w), (oy + 0.85446f * h), (ox + 0.23934f * w), (oy + 0.86787f * h), (ox + 0.24936f * w), (oy + 0.88129f * h))
    p.cubicTo((ox + 0.25938f * w), (oy + 0.89470f * h), (ox + 0.28538f * w), (oy + 0.90812f * h), (ox + 0.29795f * w), (oy + 0.92153f * h))
    p.cubicTo((ox + 0.31053f * w), (oy + 0.93494f * h), (ox + 0.30797f * w), (oy + 0.95003f * h), (ox + 0.32481f * w), (oy + 0.96177f * h))
    p.cubicTo((ox + 0.34165f * w), (oy + 0.97351f * h), (ox + 0.35635f * w), (oy + 0.98692f * h), (ox + 0.39898f * w), (oy + 0.99195f * h))
    p.cubicTo((ox + 0.44160f * w), (oy + 0.99698f * h), (ox + 0.54049f * w), (oy + 0.99698f * h), (ox + 0.58056f * w), (oy + 0.99195f * h))
    p.cubicTo((ox + 0.62063f * w), (oy + 0.98692f * h), (ox + 0.61935f * w), (oy + 0.97351f * h), (ox + 0.63939f * w), (oy + 0.96177f * h))
    p.cubicTo((ox + 0.65942f * w), (oy + 0.95003f * h), (ox + 0.68159f * w), (oy + 0.93494f * h), (ox + 0.70077f * w), (oy + 0.92153f * h))
    p.cubicTo((ox + 0.71995f * w), (oy + 0.90812f * h), (ox + 0.73785f * w), (oy + 0.89470f * h), (ox + 0.75448f * w), (oy + 0.88129f * h))
    p.cubicTo((ox + 0.77110f * w), (oy + 0.86787f * h), (ox + 0.79476f * w), (oy + 0.85446f * h), (ox + 0.80051f * w), (oy + 0.84105f * h))
    p.cubicTo((ox + 0.80627f * w), (oy + 0.82763f * h), (ox + 0.79348f * w), (oy + 0.81422f * h), (ox + 0.78900f * w), (oy + 0.80080f * h))
    p.cubicTo((ox + 0.78453f * w), (oy + 0.78739f * h), (ox + 0.77408f * w), (oy + 0.77398f * h), (ox + 0.77366f * w), (oy + 0.76056f * h))
    p.cubicTo((ox + 0.77323f * w), (oy + 0.74715f * h), (ox + 0.79710f * w), (oy + 0.73374f * h), (ox + 0.78645f * w), (oy + 0.72032f * h))
    p.cubicTo((ox + 0.77579f * w), (oy + 0.70691f * h), (ox + 0.73146f * w), (oy + 0.69349f * h), (ox + 0.70972f * w), (oy + 0.68008f * h))
    p.cubicTo((ox + 0.68798f * w), (oy + 0.66667f * h), (ox + 0.67050f * w), (oy + 0.65325f * h), (ox + 0.65601f * w), (oy + 0.63984f * h))
    p.cubicTo((ox + 0.64152f * w), (oy + 0.62643f * h), (ox + 0.63598f * w), (oy + 0.61301f * h), (ox + 0.62276f * w), (oy + 0.59960f * h))
    p.cubicTo((ox + 0.60955f * w), (oy + 0.58618f * h), (ox + 0.58568f * w), (oy + 0.57277f * h), (ox + 0.57673f * w), (oy + 0.55936f * h))
    p.cubicTo((ox + 0.56777f * w), (oy + 0.54594f * h), (ox + 0.56905f * w), (oy + 0.53253f * h), (ox + 0.56905f * w), (oy + 0.51911f * h))
    p.cubicTo((ox + 0.56905f * w), (oy + 0.50570f * h), (ox + 0.58546f * w), (oy + 0.48558f * h), (ox + 0.57673f * w), (oy + 0.47887f * h))
    p.cubicTo((ox + 0.56799f * w), (oy + 0.47217f * h), (ox + 0.53346f * w), (oy + 0.47217f * h), (ox + 0.51662f * w), (oy + 0.47887f * h))
    p.close()
    return p
}

private fun appleYellowTonguePath(w: Float, h: Float, ox: Float, oy: Float): Path {
    val p = Path()
    p.moveTo((ox + 0.51269f * w), (oy + 0.45813f * h))
    p.cubicTo((ox + 0.51216f * w), (oy + 0.46229f * h), (ox + 0.51520f * w), (oy + 0.47476f * h), (ox + 0.51427f * w), (oy + 0.48308f * h))
    p.cubicTo((ox + 0.51335f * w), (oy + 0.49140f * h), (ox + 0.51150f * w), (oy + 0.49971f * h), (ox + 0.50714f * w), (oy + 0.50803f * h))
    p.cubicTo((ox + 0.50277f * w), (oy + 0.51635f * h), (ox + 0.49551f * w), (oy + 0.52466f * h), (ox + 0.48811f * w), (oy + 0.53298f * h))
    p.cubicTo((ox + 0.48071f * w), (oy + 0.54130f * h), (ox + 0.47093f * w), (oy + 0.54961f * h), (ox + 0.46274f * w), (oy + 0.55793f * h))
    p.cubicTo((ox + 0.45454f * w), (oy + 0.56625f * h), (ox + 0.44582f * w), (oy + 0.57456f * h), (ox + 0.43895f * w), (oy + 0.58288f * h))
    p.cubicTo((ox + 0.43208f * w), (oy + 0.59120f * h), (ox + 0.42693f * w), (oy + 0.59951f * h), (ox + 0.42151f * w), (oy + 0.60783f * h))
    p.cubicTo((ox + 0.41609f * w), (oy + 0.61615f * h), (ox + 0.41054f * w), (oy + 0.62446f * h), (ox + 0.40645f * w), (oy + 0.63278f * h))
    p.cubicTo((ox + 0.40235f * w), (oy + 0.64110f * h), (ox + 0.39865f * w), (oy + 0.64941f * h), (ox + 0.39693f * w), (oy + 0.65773f * h))
    p.cubicTo((ox + 0.39521f * w), (oy + 0.66605f * h), (ox + 0.39706f * w), (oy + 0.67436f * h), (ox + 0.39614f * w), (oy + 0.68268f * h))
    p.cubicTo((ox + 0.39521f * w), (oy + 0.69100f * h), (ox + 0.41279f * w), (oy + 0.69931f * h), (ox + 0.39138f * w), (oy + 0.70763f * h))
    p.cubicTo((ox + 0.36997f * w), (oy + 0.71594f * h), (ox + 0.28844f * w), (oy + 0.72426f * h), (ox + 0.26770f * w), (oy + 0.73258f * h))
    p.cubicTo((ox + 0.24695f * w), (oy + 0.74089f * h), (ox + 0.26664f * w), (oy + 0.74921f * h), (ox + 0.26691f * w), (oy + 0.75753f * h))
    p.cubicTo((ox + 0.26717f * w), (oy + 0.76584f * h), (ox + 0.26757f * w), (oy + 0.77416f * h), (ox + 0.26928f * w), (oy + 0.78248f * h))
    p.cubicTo((ox + 0.27100f * w), (oy + 0.79079f * h), (ox + 0.27431f * w), (oy + 0.79911f * h), (ox + 0.27721f * w), (oy + 0.80743f * h))
    p.cubicTo((ox + 0.28012f * w), (oy + 0.81574f * h), (ox + 0.28263f * w), (oy + 0.82406f * h), (ox + 0.28673f * w), (oy + 0.83238f * h))
    p.cubicTo((ox + 0.29082f * w), (oy + 0.84069f * h), (ox + 0.29730f * w), (oy + 0.84901f * h), (ox + 0.30179f * w), (oy + 0.85733f * h))
    p.cubicTo((ox + 0.30628f * w), (oy + 0.86564f * h), (ox + 0.31276f * w), (oy + 0.87396f * h), (ox + 0.31368f * w), (oy + 0.88228f * h))
    p.cubicTo((ox + 0.31461f * w), (oy + 0.89059f * h), (ox + 0.30602f * w), (oy + 0.89891f * h), (ox + 0.30734f * w), (oy + 0.90723f * h))
    p.cubicTo((ox + 0.30866f * w), (oy + 0.91554f * h), (ox + 0.31038f * w), (oy + 0.92386f * h), (ox + 0.32161f * w), (oy + 0.93218f * h))
    p.cubicTo((ox + 0.33284f * w), (oy + 0.94049f * h), (ox + 0.36152f * w), (oy + 0.95193f * h), (ox + 0.37473f * w), (oy + 0.95713f * h))
    p.cubicTo((ox + 0.38795f * w), (oy + 0.96232f * h), (ox + 0.37050f * w), (oy + 0.96232f * h), (ox + 0.40090f * w), (oy + 0.96336f * h))
    p.cubicTo((ox + 0.43129f * w), (oy + 0.96440f * h), (ox + 0.52537f * w), (oy + 0.96440f * h), (ox + 0.55708f * w), (oy + 0.96336f * h))
    p.cubicTo((ox + 0.58880f * w), (oy + 0.96232f * h), (ox + 0.57268f * w), (oy + 0.96232f * h), (ox + 0.59118f * w), (oy + 0.95713f * h))
    p.cubicTo((ox + 0.60968f * w), (oy + 0.95193f * h), (ox + 0.65090f * w), (oy + 0.94049f * h), (ox + 0.66808f * w), (oy + 0.93218f * h))
    p.cubicTo((ox + 0.68526f * w), (oy + 0.92386f * h), (ox + 0.69002f * w), (oy + 0.91554f * h), (ox + 0.69425f * w), (oy + 0.90723f * h))
    p.cubicTo((ox + 0.69847f * w), (oy + 0.89891f * h), (ox + 0.69160f * w), (oy + 0.89059f * h), (ox + 0.69345f * w), (oy + 0.88228f * h))
    p.cubicTo((ox + 0.69530f * w), (oy + 0.87396f * h), (ox + 0.70059f * w), (oy + 0.86564f * h), (ox + 0.70535f * w), (oy + 0.85733f * h))
    p.cubicTo((ox + 0.71010f * w), (oy + 0.84901f * h), (ox + 0.72503f * w), (oy + 0.84069f * h), (ox + 0.72199f * w), (oy + 0.83238f * h))
    p.cubicTo((ox + 0.71896f * w), (oy + 0.82406f * h), (ox + 0.69292f * w), (oy + 0.81574f * h), (ox + 0.68711f * w), (oy + 0.80743f * h))
    p.cubicTo((ox + 0.68130f * w), (oy + 0.79911f * h), (ox + 0.68552f * w), (oy + 0.79079f * h), (ox + 0.68711f * w), (oy + 0.78248f * h))
    p.cubicTo((ox + 0.68870f * w), (oy + 0.77416f * h), (ox + 0.69411f * w), (oy + 0.76584f * h), (ox + 0.69662f * w), (oy + 0.75753f * h))
    p.cubicTo((ox + 0.69913f * w), (oy + 0.74921f * h), (ox + 0.71565f * w), (oy + 0.74089f * h), (ox + 0.70217f * w), (oy + 0.73258f * h))
    p.cubicTo((ox + 0.68870f * w), (oy + 0.72426f * h), (ox + 0.63174f * w), (oy + 0.71594f * h), (ox + 0.61575f * w), (oy + 0.70763f * h))
    p.cubicTo((ox + 0.59977f * w), (oy + 0.69931f * h), (ox + 0.60717f * w), (oy + 0.69100f * h), (ox + 0.60624f * w), (oy + 0.68268f * h))
    p.cubicTo((ox + 0.60532f * w), (oy + 0.67436f * h), (ox + 0.60902f * w), (oy + 0.66605f * h), (ox + 0.61020f * w), (oy + 0.65773f * h))
    p.cubicTo((ox + 0.61139f * w), (oy + 0.64941f * h), (ox + 0.61258f * w), (oy + 0.64110f * h), (ox + 0.61338f * w), (oy + 0.63278f * h))
    p.cubicTo((ox + 0.61417f * w), (oy + 0.62446f * h), (ox + 0.61496f * w), (oy + 0.61615f * h), (ox + 0.61496f * w), (oy + 0.60783f * h))
    p.cubicTo((ox + 0.61496f * w), (oy + 0.59951f * h), (ox + 0.61483f * w), (oy + 0.59120f * h), (ox + 0.61338f * w), (oy + 0.58288f * h))
    p.cubicTo((ox + 0.61192f * w), (oy + 0.57456f * h), (ox + 0.60954f * w), (oy + 0.56625f * h), (ox + 0.60624f * w), (oy + 0.55793f * h))
    p.cubicTo((ox + 0.60294f * w), (oy + 0.54961f * h), (ox + 0.59858f * w), (oy + 0.54130f * h), (ox + 0.59355f * w), (oy + 0.53298f * h))
    p.cubicTo((ox + 0.58853f * w), (oy + 0.52466f * h), (ox + 0.58312f * w), (oy + 0.51635f * h), (ox + 0.57611f * w), (oy + 0.50803f * h))
    p.cubicTo((ox + 0.56911f * w), (oy + 0.49971f * h), (ox + 0.56131f * w), (oy + 0.49140f * h), (ox + 0.55153f * w), (oy + 0.48308f * h))
    p.cubicTo((ox + 0.54176f * w), (oy + 0.47476f * h), (ox + 0.52392f * w), (oy + 0.46229f * h), (ox + 0.51744f * w), (oy + 0.45813f * h))
    p.cubicTo((ox + 0.51097f * w), (oy + 0.45397f * h), (ox + 0.51321f * w), (oy + 0.45397f * h), (ox + 0.51269f * w), (oy + 0.45813f * h))
    p.close()
    return p
}

private fun appleMainPath(w: Float, h: Float, ox: Float, oy: Float): Path {
    val p = Path()
    p.moveTo((ox + 0.45652f * w), (oy + 0.00000f * h))
    p.cubicTo((ox + 0.45396f * w), (oy + 0.00671f * h), (ox + 0.45439f * w), (oy + 0.02683f * h), (ox + 0.45524f * w), (oy + 0.04024f * h))
    p.cubicTo((ox + 0.45610f * w), (oy + 0.05366f * h), (ox + 0.45993f * w), (oy + 0.06707f * h), (ox + 0.46164f * w), (oy + 0.08048f * h))
    p.cubicTo((ox + 0.46334f * w), (oy + 0.09390f * h), (ox + 0.46547f * w), (oy + 0.10731f * h), (ox + 0.46547f * w), (oy + 0.12072f * h))
    p.cubicTo((ox + 0.46547f * w), (oy + 0.13414f * h), (ox + 0.46569f * w), (oy + 0.14755f * h), (ox + 0.46164f * w), (oy + 0.16097f * h))
    p.cubicTo((ox + 0.45759f * w), (oy + 0.17438f * h), (ox + 0.46100f * w), (oy + 0.18779f * h), (ox + 0.44118f * w), (oy + 0.20121f * h))
    p.cubicTo((ox + 0.42136f * w), (oy + 0.21462f * h), (ox + 0.36253f * w), (oy + 0.22803f * h), (ox + 0.34271f * w), (oy + 0.24145f * h))
    p.cubicTo((ox + 0.32289f * w), (oy + 0.25486f * h), (ox + 0.32907f * w), (oy + 0.26828f * h), (ox + 0.32225f * w), (oy + 0.28169f * h))
    p.cubicTo((ox + 0.31543f * w), (oy + 0.29510f * h), (ox + 0.30861f * w), (oy + 0.30852f * h), (ox + 0.30179f * w), (oy + 0.32193f * h))
    p.cubicTo((ox + 0.29497f * w), (oy + 0.33535f * h), (ox + 0.28687f * w), (oy + 0.34876f * h), (ox + 0.28133f * w), (oy + 0.36217f * h))
    p.cubicTo((ox + 0.27579f * w), (oy + 0.37559f * h), (ox + 0.30094f * w), (oy + 0.38900f * h), (ox + 0.26854f * w), (oy + 0.40241f * h))
    p.cubicTo((ox + 0.23615f * w), (oy + 0.41583f * h), (ox + 0.12212f * w), (oy + 0.42924f * h), (ox + 0.08696f * w), (oy + 0.44266f * h))
    p.cubicTo((ox + 0.05179f * w), (oy + 0.45607f * h), (ox + 0.06628f * w), (oy + 0.46948f * h), (ox + 0.05754f * w), (oy + 0.48290f * h))
    p.cubicTo((ox + 0.04881f * w), (oy + 0.49631f * h), (ox + 0.04113f * w), (oy + 0.50973f * h), (ox + 0.03453f * w), (oy + 0.52314f * h))
    p.cubicTo((ox + 0.02792f * w), (oy + 0.53655f * h), (ox + 0.02238f * w), (oy + 0.54997f * h), (ox + 0.01790f * w), (oy + 0.56338f * h))
    p.cubicTo((ox + 0.01343f * w), (oy + 0.57679f * h), (ox + 0.01066f * w), (oy + 0.59021f * h), (ox + 0.00767f * w), (oy + 0.60362f * h))
    p.cubicTo((ox + 0.00469f * w), (oy + 0.61704f * h), (ox + -0.00085f * w), (oy + 0.63045f * h), (ox + 0.00000f * w), (oy + 0.64386f * h))
    p.cubicTo((ox + 0.00085f * w), (oy + 0.65728f * h), (ox + 0.00789f * w), (oy + 0.67069f * h), (ox + 0.01279f * w), (oy + 0.68410f * h))
    p.cubicTo((ox + 0.01769f * w), (oy + 0.69752f * h), (ox + 0.02195f * w), (oy + 0.71093f * h), (ox + 0.02941f * w), (oy + 0.72435f * h))
    p.cubicTo((ox + 0.03687f * w), (oy + 0.73776f * h), (ox + 0.04689f * w), (oy + 0.75117f * h), (ox + 0.05754f * w), (oy + 0.76459f * h))
    p.cubicTo((ox + 0.06820f * w), (oy + 0.77800f * h), (ox + 0.09335f * w), (oy + 0.79142f * h), (ox + 0.09335f * w), (oy + 0.80483f * h))
    p.cubicTo((ox + 0.09335f * w), (oy + 0.81824f * h), (ox + 0.05584f * w), (oy + 0.83166f * h), (ox + 0.05754f * w), (oy + 0.84507f * h))
    p.cubicTo((ox + 0.05925f * w), (oy + 0.85848f * h), (ox + 0.08546f * w), (oy + 0.87190f * h), (ox + 0.10358f * w), (oy + 0.88531f * h))
    p.cubicTo((ox + 0.12170f * w), (oy + 0.89873f * h), (ox + 0.14109f * w), (oy + 0.91214f * h), (ox + 0.16624f * w), (oy + 0.92555f * h))
    p.cubicTo((ox + 0.19139f * w), (oy + 0.93897f * h), (ox + 0.21654f * w), (oy + 0.95406f * h), (ox + 0.25448f * w), (oy + 0.96579f * h))
    p.cubicTo((ox + 0.29241f * w), (oy + 0.97753f * h), (ox + 0.35486f * w), (oy + 0.99095f * h), (ox + 0.39386f * w), (oy + 0.99598f * h))
    p.cubicTo((ox + 0.43286f * w), (oy + 1.00101f * h), (ox + 0.43755f * w), (oy + 1.00101f * h), (ox + 0.48849f * w), (oy + 0.99598f * h))
    p.cubicTo((ox + 0.53943f * w), (oy + 0.99095f * h), (ox + 0.64621f * w), (oy + 0.97753f * h), (ox + 0.69949f * w), (oy + 0.96579f * h))
    p.cubicTo((ox + 0.75277f * w), (oy + 0.95406f * h), (ox + 0.77728f * w), (oy + 0.93897f * h), (ox + 0.80818f * w), (oy + 0.92555f * h))
    p.cubicTo((ox + 0.83909f * w), (oy + 0.91214f * h), (ox + 0.87617f * w), (oy + 0.89873f * h), (ox + 0.88491f * w), (oy + 0.88531f * h))
    p.cubicTo((ox + 0.89365f * w), (oy + 0.87190f * h), (ox + 0.85699f * w), (oy + 0.85848f * h), (ox + 0.86061f * w), (oy + 0.84507f * h))
    p.cubicTo((ox + 0.86424f * w), (oy + 0.83166f * h), (ox + 0.89216f * w), (oy + 0.81824f * h), (ox + 0.90665f * w), (oy + 0.80483f * h))
    p.cubicTo((ox + 0.92114f * w), (oy + 0.79142f * h), (ox + 0.93606f * w), (oy + 0.77800f * h), (ox + 0.94757f * w), (oy + 0.76459f * h))
    p.cubicTo((ox + 0.95908f * w), (oy + 0.75117f * h), (ox + 0.96867f * w), (oy + 0.73776f * h), (ox + 0.97570f * w), (oy + 0.72435f * h))
    p.cubicTo((ox + 0.98274f * w), (oy + 0.71093f * h), (ox + 0.98593f * w), (oy + 0.69752f * h), (ox + 0.98977f * w), (oy + 0.68410f * h))
    p.cubicTo((ox + 0.99361f * w), (oy + 0.67069f * h), (ox + 0.99829f * w), (oy + 0.65728f * h), (ox + 0.99872f * w), (oy + 0.64386f * h))
    p.cubicTo((ox + 0.99915f * w), (oy + 0.63045f * h), (ox + 0.99446f * w), (oy + 0.61704f * h), (ox + 0.99233f * w), (oy + 0.60362f * h))
    p.cubicTo((ox + 0.99020f * w), (oy + 0.59021f * h), (ox + 0.98934f * w), (oy + 0.57679f * h), (ox + 0.98593f * w), (oy + 0.56338f * h))
    p.cubicTo((ox + 0.98252f * w), (oy + 0.54997f * h), (ox + 0.97677f * w), (oy + 0.53655f * h), (ox + 0.97187f * w), (oy + 0.52314f * h))
    p.cubicTo((ox + 0.96697f * w), (oy + 0.50973f * h), (ox + 0.96270f * w), (oy + 0.49631f * h), (ox + 0.95652f * w), (oy + 0.48290f * h))
    p.cubicTo((ox + 0.95034f * w), (oy + 0.46948f * h), (ox + 0.95951f * w), (oy + 0.45607f * h), (ox + 0.93478f * w), (oy + 0.44266f * h))
    p.cubicTo((ox + 0.91006f * w), (oy + 0.42924f * h), (ox + 0.82822f * w), (oy + 0.41583f * h), (ox + 0.80818f * w), (oy + 0.40241f * h))
    p.cubicTo((ox + 0.78815f * w), (oy + 0.38900f * h), (ox + 0.81373f * w), (oy + 0.37559f * h), (ox + 0.81458f * w), (oy + 0.36217f * h))
    p.cubicTo((ox + 0.81543f * w), (oy + 0.34876f * h), (ox + 0.81436f * w), (oy + 0.33535f * h), (ox + 0.81330f * w), (oy + 0.32193f * h))
    p.cubicTo((ox + 0.81223f * w), (oy + 0.30852f * h), (ox + 0.81202f * w), (oy + 0.29510f * h), (ox + 0.80818f * w), (oy + 0.28169f * h))
    p.cubicTo((ox + 0.80435f * w), (oy + 0.26828f * h), (ox + 0.79859f * w), (oy + 0.25486f * h), (ox + 0.79028f * w), (oy + 0.24145f * h))
    p.cubicTo((ox + 0.78197f * w), (oy + 0.22803f * h), (ox + 0.77110f * w), (oy + 0.21462f * h), (ox + 0.75831f * w), (oy + 0.20121f * h))
    p.cubicTo((ox + 0.74552f * w), (oy + 0.18779f * h), (ox + 0.72933f * w), (oy + 0.17438f * h), (ox + 0.71355f * w), (oy + 0.16097f * h))
    p.cubicTo((ox + 0.69778f * w), (oy + 0.14755f * h), (ox + 0.68286f * w), (oy + 0.13414f * h), (ox + 0.66368f * w), (oy + 0.12072f * h))
    p.cubicTo((ox + 0.64450f * w), (oy + 0.10731f * h), (ox + 0.61999f * w), (oy + 0.09390f * h), (ox + 0.59847f * w), (oy + 0.08048f * h))
    p.cubicTo((ox + 0.57694f * w), (oy + 0.06707f * h), (ox + 0.55584f * w), (oy + 0.05366f * h), (ox + 0.53453f * w), (oy + 0.04024f * h))
    p.cubicTo((ox + 0.51321f * w), (oy + 0.02683f * h), (ox + 0.48359f * w), (oy + 0.00671f * h), (ox + 0.47059f * w), (oy + 0.00000f * h))
    p.cubicTo((ox + 0.45759f * w), (oy + -0.00671f * h), (ox + 0.45908f * w), (oy + -0.00671f * h), (ox + 0.45652f * w), (oy + 0.00000f * h))
    p.close()
    return p
}

private fun appleLickPath(w: Float, h: Float, ox: Float, oy: Float): Path {
    val p = Path()
    p.moveTo((ox + 0.15217f * w), (oy + 0.22837f * h))
    p.cubicTo((ox + 0.14621f * w), (oy + 0.23508f * h), (ox + 0.13470f * w), (oy + 0.25520f * h), (ox + 0.12276f * w), (oy + 0.26861f * h))
    p.cubicTo((ox + 0.11083f * w), (oy + 0.28203f * h), (ox + 0.09079f * w), (oy + 0.29544f * h), (ox + 0.08056f * w), (oy + 0.30885f * h))
    p.cubicTo((ox + 0.07033f * w), (oy + 0.32227f * h), (ox + 0.06458f * w), (oy + 0.33568f * h), (ox + 0.06138f * w), (oy + 0.34909f * h))
    p.cubicTo((ox + 0.05818f * w), (oy + 0.36251f * h), (ox + 0.06138f * w), (oy + 0.38095f * h), (ox + 0.06138f * w), (oy + 0.38934f * h))
    p.cubicTo((ox + 0.06138f * w), (oy + 0.39772f * h), (ox + 0.05818f * w), (oy + 0.39772f * h), (ox + 0.06138f * w), (oy + 0.39940f * h))
    p.cubicTo((ox + 0.06458f * w), (oy + 0.40107f * h), (ox + 0.07545f * w), (oy + 0.40107f * h), (ox + 0.08056f * w), (oy + 0.39940f * h))
    p.cubicTo((ox + 0.08568f * w), (oy + 0.39772f * h), (ox + 0.08291f * w), (oy + 0.39772f * h), (ox + 0.09207f * w), (oy + 0.38934f * h))
    p.cubicTo((ox + 0.10124f * w), (oy + 0.38095f * h), (ox + 0.12489f * w), (oy + 0.36251f * h), (ox + 0.13555f * w), (oy + 0.34909f * h))
    p.cubicTo((ox + 0.14621f * w), (oy + 0.33568f * h), (ox + 0.15111f * w), (oy + 0.32227f * h), (ox + 0.15601f * w), (oy + 0.30885f * h))
    p.cubicTo((ox + 0.16091f * w), (oy + 0.29544f * h), (ox + 0.16454f * w), (oy + 0.28203f * h), (ox + 0.16496f * w), (oy + 0.26861f * h))
    p.cubicTo((ox + 0.16539f * w), (oy + 0.25520f * h), (ox + 0.16070f * w), (oy + 0.23508f * h), (ox + 0.15857f * w), (oy + 0.22837f * h))
    p.cubicTo((ox + 0.15644f * w), (oy + 0.22166f * h), (ox + 0.15814f * w), (oy + 0.22166f * h), (ox + 0.15217f * w), (oy + 0.22837f * h))
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
    /** When true, and the flame is at least 64dp, adds 2-4 small celebration droplets
     *  sparkling outside each side of the silhouette. Defaults off, so existing renders
     *  and shipped placements are unchanged. The outer flame itself never changes. */
    sideSparks: Boolean = false,
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
    val sideSparkEligible = sideSparks && dpSize >= 64f
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
                painter.drawCrossFade(
                    this, from, shown, elapsed, milestoneFlash + crossflash, fade.value, forceAnimate,
                    sideSparks = sideSparkEligible,
                )
            } else {
                painter.draw(this, shown, elapsed, milestoneFlash + crossflash, forceAnimate, sideSparks = sideSparkEligible)
            }
        }
    }
}
