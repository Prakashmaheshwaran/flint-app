package com.flint.peakfocus.core.common.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintPalette

/*
 * The Ember & Mist scene: night sky, the ember on the horizon, three ridges and drifting mist.
 * Geometry is the 390-wide reference artboard (design/ember-mist/E03-Today.dc.html) scaled to
 * the screen's width.
 */

internal fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }

internal fun Modifier.selectableRole(selected: Boolean?): Modifier =
    semantics(mergeDescendants = true) {
        if (selected != null) this.selected = selected
        role = if (selected == null) Role.Button else Role.RadioButton
    }

fun Modifier.flintClickable(onClick: () -> Unit): Modifier =
    semantics(mergeDescendants = true) {}.clickable(role = Role.Button, onClick = onClick)

private val Sky = listOf(
    0xFF0B0D12 to 0f, 0xFF14151B to 321f, 0xFF2A221E to 439f, 0xFF4B3322 to 506f, 0xFF1A1614 to 608f, 0xFF0E0D0F to 844f,
)

private val Stars = listOf(
    floatArrayOf(40f, 120f, 0.9f, 0.5f), floatArrayOf(300f, 80f, 1f, 0.45f), floatArrayOf(352f, 190f, 0.8f, 0.35f),
    floatArrayOf(120f, 230f, 0.7f, 0.3f), floatArrayOf(250f, 160f, 0.7f, 0.3f),
)

// (start y, then cubic segments as c1x c1y c2x c2y x y) — the three ridges of the reference.
private val FarRidge = floatArrayOf(492f, 40f, 466f, 72f, 464f, 106f, 480f, 140f, 496f, 162f, 452f, 206f, 438f,
    246f, 426f, 272f, 468f, 306f, 476f, 336f, 483f, 362f, 455f, 390f, 449f)
private val MidRidge = floatArrayOf(548f, 36f, 530f, 62f, 512f, 96f, 520f, 132f, 528f, 152f, 498f, 192f, 505f,
    232f, 512f, 252f, 540f, 292f, 532f, 330f, 524f, 360f, 508f, 390f, 515f)
private val NearRidge = floatArrayOf(600f, 50f, 582f, 92f, 592f, 132f, 588f, 176f, 584f, 202f, 566f, 246f, 573f,
    290f, 580f, 332f, 598f, 390f, 590f)

private fun ridgePath(spec: FloatArray, floor: Float): Path = Path().apply {
    moveTo(0f, spec[0])
    var i = 1
    while (i + 5 < spec.size) {
        cubicTo(spec[i], spec[i + 1], spec[i + 2], spec[i + 3], spec[i + 4], spec[i + 5])
        i += 6
    }
    lineTo(390f, floor)
    lineTo(0f, floor)
    close()
}

/** A drifting bank of mist: a soft bone oval (radial falloff instead of a blur, so it works on every API). */
private fun DrawScope.drawMist(topLeft: Offset, size: Size, alpha: Float) {
    val center = Offset(topLeft.x + size.width / 2, topLeft.y + size.height / 2)
    withTransform({ scale(1f, size.height / size.width, pivot = center) }) {
        drawCircle(
            Brush.radialGradient(
                0f to FlintPalette.Bone.copy(alpha = alpha), 0.55f to FlintPalette.Bone.copy(alpha = alpha * 0.7f),
                1f to Color.Transparent, center = center, radius = size.width / 2,
            ),
            radius = size.width / 2, center = center,
        )
    }
}

/** The ember orb inside its halo; the halo breathes 0.75↔1 over 8 s unless motion is reduced. */
fun DrawScope.drawEmber(center: Offset, radius: Float, haloRadius: Float, haloAlpha: Float = 1f) {
    drawCircle(
        Brush.radialGradient(
            0f to FlintPalette.Ember.copy(alpha = 0.42f * haloAlpha),
            0.45f to FlintPalette.Ember.copy(alpha = 0.12f * haloAlpha),
            1f to FlintPalette.Ember.copy(alpha = 0f),
            center = center, radius = haloRadius,
        ),
        radius = haloRadius, center = center,
    )
    drawCircle(
        Brush.radialGradient(
            0f to FlintPalette.Glow, 0.35f to FlintPalette.Flame, 0.7f to FlintPalette.Ember, 1f to Color(0xFFC9781A),
            center = center, radius = radius,
        ),
        radius = radius, center = center,
    )
}

@Composable
private fun glowAlpha(enabled: Boolean): Float {
    if (!enabled) return 0.9f
    return ambientFloat(reduced = false, fallback = 0.9f, from = 0.75f, to = 1f, duration = FlintMotion.GlowCycle / 2)
}

/** One ambient 0..1 loop that returns [fallback] under reduced motion. */
@Composable
private fun ambientFloat(
    reduced: Boolean,
    fallback: Float,
    from: Float,
    to: Float,
    duration: Int,
    restart: Boolean = false,
): Float {
    if (reduced) return fallback
    val transition = rememberInfiniteTransition(label = "ambientFloat")
    val value by transition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = FastOutSlowInEasing),
            repeatMode = if (restart) RepeatMode.Restart else RepeatMode.Reverse,
        ),
        label = "ambientFloatValue",
    )
    return value
}

/**
 * Full-bleed night scene. [horizonOffset] shifts the ridges in reference points (Today 0,
 * Welcome 20, Session 8); [orbCenter] is in reference points (null hides the ember); [dim]
 * darkens everything for scenes behind a sheet.
 */
@Composable
fun EmberLandscape(
    modifier: Modifier = Modifier,
    horizonOffset: Float = 0f,
    orbCenter: Offset? = Offset(195f, 452f),
    orbRadius: Float = 58f,
    showsMist: Boolean = true,
    dim: Float = 0f,
) {
    val reduced = rememberReducedMotion()
    val halo = glowAlpha(enabled = orbCenter != null && !reduced)
    val drift = if (showsMist && !reduced) {
        val transition = rememberInfiniteTransition(label = "mist")
        val value by transition.animateFloat(
            initialValue = -14f, targetValue = 14f,
            animationSpec = infiniteRepeatable(tween(FlintMotion.MistDrift / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "mistDrift",
        )
        value
    } else {
        0f
    }
    BoxWithConstraints(modifier.fillMaxSize().background(FlintPalette.Ground)) {
        Canvas(Modifier.fillMaxSize()) {
            val s = size.width / 390f
            val o = horizonOffset
            val skyBottom = (844f + o) * s
            drawRect(
                Brush.verticalGradient(
                    *Sky.map { (hex, y) ->
                        (if (y == 0f) 0f else ((y + o) / (844f + o)).coerceIn(0f, 1f)) to Color(hex)
                    }.toTypedArray(),
                    startY = 0f, endY = skyBottom,
                ),
                size = Size(size.width, skyBottom),
            )
            Stars.forEach { (x, y, r, a) ->
                drawCircle(FlintPalette.Bone.copy(alpha = a), radius = r * s, center = Offset(x * s, y * s))
            }
            orbCenter?.let { c ->
                drawEmber(Offset(c.x * s, (c.y + o) * s), orbRadius * s, orbRadius * 2.93f * s, halo)
            }
            withTransform({ scale(s, s, pivot = Offset.Zero); translate(0f, o) }) {
                val floor = maxOf(size.height / s - o, 844f)
                drawPath(ridgePath(FarRidge, floor), FlintPalette.Ridge)
                drawPath(ridgePath(MidRidge, floor), Color(0xFF1C1919))
                drawPath(ridgePath(NearRidge, floor), Color(0xFF111012))
                if (showsMist) {
                    drawMist(Offset(-40f + drift, 500f), Size(470f, 46f), 0.07f)
                    drawMist(Offset(-60f + drift, 556f), Size(360f, 34f), 0.05f)
                }
            }
        }
        if (dim > 0f) Box(Modifier.fillMaxSize().background(Color(0xFF07080B).copy(alpha = dim)))
    }
}

/**
 * The sun's path across a session (E06): a dotted arc, the travelled part solid, and the ember
 * with two breath rings at the current point. [complete] draws the finished arc with the ember
 * set on the right horizon (E08).
 */
@Composable
fun SunArc(progress: Float, modifier: Modifier = Modifier, complete: Boolean = false) {
    val reduced = rememberReducedMotion()
    val breath = if (!complete && !reduced) {
        val transition = rememberInfiniteTransition(label = "breath")
        val v by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(FlintMotion.BreathCycle / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathPhase",
        )
        v
    } else {
        0.5f
    }
    val halo = glowAlpha(enabled = complete && !reduced)
    Canvas(modifier.fillMaxSize()) {
        val s = size.width / 390f
        val p0 = Offset(50f, 470f); val p1 = Offset(195f, 90f); val p2 = Offset(340f, 470f)
        val t = if (complete) 1f else progress.coerceIn(0f, 1f)
        fun at(t: Float): Offset {
            val u = 1 - t
            return Offset(u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x, u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y)
        }
        val point = at(t)
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            val full = Path().apply { moveTo(p0.x, p0.y); quadraticTo(p1.x, p1.y, p2.x, p2.y) }
            if (complete) {
                drawPath(full, FlintPalette.Bone.copy(alpha = 0.35f), style = Stroke(1.2f))
            } else {
                drawPath(full, FlintPalette.Bone.copy(alpha = 0.22f),
                    style = Stroke(1f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 7f))))
                val c = Offset(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t)
                val done = Path().apply { moveTo(p0.x, p0.y); quadraticTo(c.x, c.y, point.x, point.y) }
                drawPath(done, FlintPalette.Bone.copy(alpha = 0.5f), style = Stroke(1.2f, cap = StrokeCap.Round))
                drawCircle(FlintPalette.Bone.copy(alpha = 0.4f), 3f, p2)
            }
        }
        val center = Offset(point.x * s, (if (complete) point.y - 8f else point.y) * s)
        if (!complete) {
            listOf(78f to 0f, 104f to 0.25f).forEach { (r, lag) ->
                val phase = ((breath + lag) % 1f)
                val scale = 0.82f + 0.36f * phase
                drawCircle(FlintPalette.Flame.copy(alpha = 0.25f + 0.35f * phase), r * s * scale, center,
                    style = Stroke(if (r > 90f) 0.8f * s else 1f * s))
            }
        }
        drawEmber(center, (if (complete) 40f else 44f) * s, (if (complete) 170f else 150f) * s, halo)
    }
}

/** The v3 quiet night header: a soft ember halo over the top edge, fading into Ground. */
@Composable
fun FlintTopGlow(
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
    emberAlpha: Float = 0.14f,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(listOf(Color(0xFF0A0C11), FlintPalette.Ground)),
                )
                drawRect(
                    Brush.radialGradient(
                        0f to FlintPalette.Ember.copy(alpha = emberAlpha),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, 0f),
                        radius = size.width * 0.7f,
                    ),
                )
            },
    )
}

/**
 * M03's Today hero: night sky, layered ridges, the ember over the horizon and the Flint spark
 * rising above it. Geometry is the 390-wide reference artboard scaled to the screen width; the
 * spark's embers drift outward unless motion is reduced.
 */
@Composable
fun TodayHero(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val glow = ambientFloat(reduced, fallback = 0.85f, from = 0.7f, to = 1f, duration = FlintMotion.GlowCycle / 2)
    val flicker = ambientFloat(reduced, fallback = 1f, from = 0.55f, to = 1f, duration = 800)
    val sparkPhase = ambientFloat(reduced, fallback = 0f, from = 0f, to = 1f, duration = 2400, restart = true)
    Canvas(modifier.fillMaxWidth().height(430.dp)) {
        val s = size.width / 390f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF0A0C11), 0.5f to Color(0xFF14151B), 0.62f to Color(0xFF2E241F),
                0.70f to Color(0xFF4B3322), 0.82f to Color(0xFF1A1614), 1f to FlintPalette.Ground,
            ),
            size = size,
        )
        listOf(
            floatArrayOf(44f, 120f, 0.9f, 0.5f), floatArrayOf(318f, 96f, 1f, 0.45f),
            floatArrayOf(352f, 200f, 0.8f, 0.35f), floatArrayOf(80f, 190f, 0.7f, 0.3f),
            floatArrayOf(300f, 150f, 0.7f, 0.3f), floatArrayOf(140f, 100f, 0.6f, 0.3f),
        ).forEach { star ->
            drawCircle(FlintPalette.Bone.copy(alpha = star[3]), star[2] * s, p(star[0], star[1]))
        }
        drawCircle(
            Brush.radialGradient(
                0f to FlintPalette.Ember.copy(alpha = 0.45f * glow),
                0.45f to FlintPalette.Ember.copy(alpha = 0.12f * glow),
                1f to Color.Transparent,
                center = p(212f, 168f - SPARK_LIFT), radius = 150f * s,
            ),
            radius = 150f * s, center = p(212f, 168f - SPARK_LIFT),
        )
        withTransform({ scale(s, size.height / 430f, pivot = Offset.Zero) }) {
            listOf(
                floatArrayOf(300f, 40f, 274f, 72f, 272f, 106f, 288f, 140f, 304f, 162f, 262f, 206f, 250f,
                    246f, 240f, 272f, 280f, 306f, 288f, 336f, 295f, 362f, 268f, 390f, 262f),
                floatArrayOf(336f, 36f, 318f, 62f, 300f, 96f, 308f, 132f, 316f, 152f, 286f, 192f, 293f,
                    232f, 300f, 252f, 328f, 292f, 320f, 330f, 312f, 360f, 296f, 390f, 303f),
                floatArrayOf(364f, 50f, 346f, 92f, 356f, 132f, 352f, 176f, 348f, 202f, 330f, 246f, 337f,
                    290f, 344f, 332f, 362f, 390f, 354f),
            ).forEachIndexed { index, spec ->
                drawPath(
                    ridgePath(spec, 430f),
                    when (index) {
                        0 -> FlintPalette.Ridge
                        1 -> Color(0xFF1C1919)
                        else -> Color(0xFF131214)
                    },
                )
            }
            // Horizon fade back into the list ground.
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to FlintPalette.Ground),
                topLeft = Offset(0f, 340f), size = Size(390f, 90f),
            )
            // The spark mark, enlarged from the 56-unit logo grid. Lifted because the board's
            // coordinates include the status bar while the copy below starts beneath it.
            translate(top = -SPARK_LIFT) {
            fun poly(vararg pts: Float) = Path().apply {
                moveTo(pts[0], pts[1])
                var i = 2
                while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
                close()
            }
            drawPath(poly(158.6f, 211.6f, 195f, 118f, 215.8f, 164.8f, 236.6f, 144f, 200.2f, 222f), FlintPalette.Bone)
            drawPath(poly(215.8f, 164.8f, 236.6f, 144f, 231.4f, 175.2f), FlintPalette.Ember.copy(alpha = flicker))
            drawPath(poly(200.2f, 222f, 231.4f, 175.2f, 236.6f, 201.2f), FlintPalette.SparkHighlight)
            drawCircle(
                Brush.radialGradient(
                    0f to FlintPalette.Glow, 0.5f to FlintPalette.Flame, 1f to FlintPalette.Ember,
                    center = Offset(236f, 146f), radius = 6f,
                ),
                radius = 6f, center = Offset(236f, 146f),
            )
            if (!reduced) {
                listOf(
                    floatArrayOf(26f, -36f, 2.4f, 0f), floatArrayOf(44f, -12f, 1.9f, 0.3f),
                    floatArrayOf(10f, -50f, 2.6f, 0.6f), floatArrayOf(38f, -30f, 1.7f, 0.9f),
                    floatArrayOf(-10f, -42f, 2.1f, 1.2f), floatArrayOf(22f, -20f, 2.3f, 1.8f),
                ).forEach { spark ->
                    val phase = ((sparkPhase + spark[3] / 2.4f) % 1f)
                    drawCircle(
                        FlintPalette.Flame.copy(alpha = (1f - phase) * 0.9f),
                        spark[2],
                        Offset(237f + spark[0] * phase, 145f + spark[1] * phase),
                    )
                }
            }
            }
        }
    }
}

/** The Flint mark: bone stone, ember and spark facets. */
@Composable
fun FlintMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val k = size.width / 56f
        fun poly(vararg pts: Float) = Path().apply {
            moveTo(pts[0] * k, pts[1] * k)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * k, pts[i + 1] * k); i += 2 }
            close()
        }
        drawPath(poly(14f, 44f, 28f, 8f, 36f, 26f, 44f, 18f, 30f, 48f), FlintPalette.Bone)
        drawPath(poly(36f, 26f, 44f, 18f, 42f, 30f), FlintPalette.Ember)
        drawPath(poly(30f, 48f, 42f, 30f, 44f, 40f), FlintPalette.SparkHighlight)
    }
}

/** The four destinations of the floating bar, identical on iPhone and Android. */
enum class FlintTabGlyph(val label: String) { Today("Today"), Rules("Rules"), Insights("Insights"), You("You") }

/**
 * The tab bar's one-row/two-row decision, kept independent of composition so the fallback is
 * unit-testable. [widths] are the measured, scaled label widths for each tab; [maxWidth] is the
 * bar's available content width. A single row is used only when every label fits side by side;
 * otherwise the tabs fall back to two rows of two.
 */
internal data class FlintTabBarPlan(
    val singleRow: Boolean,
    val equalWidths: Boolean,
    val rows: List<List<FlintTabGlyph>>,
)

internal fun planFlintTabBar(widths: Map<FlintTabGlyph, Dp>, maxWidth: Dp): FlintTabBarPlan {
    val singleRow = widths.values.sumOf { it.value.toDouble() } <= maxWidth.value
    val equalWidths = widths.values.max() * FlintTabGlyph.entries.size <= maxWidth
    val rows = if (singleRow) listOf(FlintTabGlyph.entries.toList()) else FlintTabGlyph.entries.chunked(2)
    return FlintTabBarPlan(singleRow, equalWidths, rows)
}

/** Keep full tab names visible, sharing width by their measured type before using two rows. */
@Composable
fun FlintTabBar(
    selected: FlintTabGlyph,
    onSelect: (FlintTabGlyph) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .fillMaxWidth(),
    ) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val styles = FlintTabGlyph.entries.associateWith { tab ->
            MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (tab == selected) androidx.compose.ui.text.font.FontWeight.SemiBold
                else androidx.compose.ui.text.font.FontWeight.Medium,
            )
        }
        val widths = FlintTabGlyph.entries.associateWith { tab ->
            val pixels = measurer.measure(AnnotatedString(tab.label), styles.getValue(tab), softWrap = false).size.width
            maxOf(48.dp, with(density) { pixels.toDp() } + 8.dp + 1.dp)
        }
        val plan = planFlintTabBar(widths, maxWidth)
        val shape = if (plan.singleRow) RoundedCornerShape(34.dp) else RoundedCornerShape(32.dp)
        val item: @Composable (FlintTabGlyph, Modifier) -> Unit = { tab, itemModifier ->
            val isSelected = tab == selected
            val tint = if (isSelected) FlintPalette.Bone else FlintPalette.Stone
            Column(
                itemModifier
                    .fillMaxHeight()
                    // No square ripple inside the capsule: the tint and ember accent answer the tap.
                    .selectable(
                        selected = isSelected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                    ) { onSelect(tab) }
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (isSelected) FlintPalette.Bone.copy(alpha = 0.1f) else Color.Transparent)
                    .heightIn(min = if (plan.singleRow) 56.dp else 64.dp)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Canvas(Modifier.size(22.dp)) { drawTabGlyph(tab, isSelected, tint) }
                Text(
                    tab.label,
                    style = styles.getValue(tab),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(FlintPalette.TabBar)
                .border(1.dp, FlintPalette.Divider, shape)
                .padding(6.dp)
                .selectableGroup(),
        ) {
            plan.rows.forEach { tabs ->
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (plan.singleRow) Modifier.height(56.dp) else Modifier.height(IntrinsicSize.Min)),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tabs.forEach { tab ->
                        val weight = if (!plan.singleRow || plan.equalWidths) 1f else widths.getValue(tab).value
                        item(tab, Modifier.weight(weight))
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawTabGlyph(tab: FlintTabGlyph, selected: Boolean, ink: Color) {
    val k = size.width / 24f
    val stroke = Stroke(1.5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val accent = if (selected) FlintPalette.Ember else ink
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color = ink) =
        drawLine(color, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), 1.5f * k, StrokeCap.Round)
    when (tab) {
        FlintTabGlyph.Today -> {
            drawArc(ink, startAngle = 0f, sweepAngle = 298.5f, useCenter = false,
                topLeft = Offset(4f * k, 4f * k), size = Size(16f * k, 16f * k), style = stroke)
            drawCircle(accent, 1.6f * k, Offset(18.6f * k, 6.4f * k))
        }
        FlintTabGlyph.Rules -> {
            // v3 board: a lock (rect 5,11 14x9 r2.5 + shackle M8 11V8a4 4 0 0 1 8 0v3).
            drawRoundRect(ink, topLeft = Offset(5f * k, 11f * k), size = Size(14f * k, 9f * k),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * k), style = stroke)
            val shackle = Path().apply {
                moveTo(8f * k, 11f * k)
                lineTo(8f * k, 8f * k)
                arcTo(androidx.compose.ui.geometry.Rect(8f * k, 4f * k, 16f * k, 12f * k), 180f, 180f, false)
                lineTo(16f * k, 11f * k)
            }
            drawPath(shackle, ink, style = stroke)
            if (selected) drawCircle(accent, 1.2f * k, Offset(12f * k, 15.5f * k))
        }
        FlintTabGlyph.Insights -> {
            line(6f, 19f, 6f, 13f); line(12f, 19f, 12f, 6f); line(18f, 19f, 18f, 10f, accent)
        }
        FlintTabGlyph.You -> {
            drawCircle(ink, 3.5f * k, Offset(12f * k, 9f * k), style = stroke)
            val body = Path().apply {
                moveTo(5f * k, 20f * k)
                cubicTo(6.5f * k, 16.5f * k, 9f * k, 15f * k, 12f * k, 15f * k)
                cubicTo(15f * k, 15f * k, 17.5f * k, 16.5f * k, 19f * k, 20f * k)
            }
            drawPath(body, ink, style = stroke)
            if (selected) drawCircle(accent, 1.2f * k, Offset(12f * k, 9f * k))
        }
    }
}

/** Stroke glyphs from the reference screens (24-unit grid, 1.6 stroke). */
object FlintGlyphs {
    private fun glyph(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
            }.build()

    val chevronRight: ImageVector = glyph("ChevronRight") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
    val back: ImageVector = glyph("Back") { moveTo(15f, 6f); lineTo(9f, 12f); lineTo(15f, 18f) }
    val minus: ImageVector = glyph("Minus") { moveTo(6f, 12f); lineTo(18f, 12f) }
    val plus: ImageVector = glyph("Plus") { moveTo(6f, 12f); lineTo(18f, 12f); moveTo(12f, 6f); lineTo(12f, 18f) }
    val close: ImageVector = glyph("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val check: ImageVector = glyph("Check") { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }
    val lock: ImageVector = glyph("Lock") {
        moveTo(7.5f, 11f); lineTo(16.5f, 11f); arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, 2.5f)
        lineTo(19f, 17.5f); arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f); lineTo(7.5f, 20f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, -2.5f); lineTo(5f, 13.5f)
        arcToRelative(2.5f, 2.5f, 0f, false, true, 2.5f, -2.5f); close()
        moveTo(8f, 11f); lineTo(8f, 8f); arcToRelative(4f, 4f, 0f, false, true, 8f, 0f); lineTo(16f, 11f)
    }
    val shield: ImageVector = glyph("Shield") {
        moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 11f); curveTo(19f, 15.5f, 16f, 19f, 12f, 21f)
        curveTo(8f, 19f, 5f, 15.5f, 5f, 11f); lineTo(5f, 6f); close()
    }
    val key: ImageVector = glyph("Key") {
        moveTo(12f, 12f); arcToRelative(4f, 4f, 0f, true, true, -8f, 0f); arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
        moveTo(12f, 12f); lineTo(20f, 12f); moveTo(17f, 12f); lineTo(17f, 15f); moveTo(20f, 12f); lineTo(20f, 14f)
    }
    val chart: ImageVector = glyph("Chart") {
        moveTo(6f, 19f); lineTo(6f, 13f); moveTo(12f, 19f); lineTo(12f, 6f); moveTo(18f, 19f); lineTo(18f, 10f)
    }
    val bell: ImageVector = glyph("Bell") {
        moveTo(6f, 16f); lineTo(6f, 11f)
        curveTo(6f, 7.7f, 8.7f, 5f, 12f, 5f)
        curveTo(15.3f, 5f, 18f, 7.7f, 18f, 11f)
        lineTo(18f, 16f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close()
        moveTo(10f, 20.5f); curveTo(10.5f, 21.5f, 11.2f, 22f, 12f, 22f)
        curveTo(12.8f, 22f, 13.5f, 21.5f, 14f, 20.5f)
    }
    val clock: ImageVector = glyph("Clock") {
        moveTo(18f, 13f); arcToRelative(6f, 6f, 0f, true, true, -12f, 0f); arcToRelative(6f, 6f, 0f, true, true, 12f, 0f)
        moveTo(12f, 13f); lineTo(12f, 9.5f); moveTo(10f, 3f); lineTo(14f, 3f)
    }
    /** The v3 "Start focus" timer glyph: an open ring with an ember dot at its head. */
    val timer: ImageVector = glyph("Timer") {
        moveTo(20f, 12f); arcToRelative(8f, 8f, 0f, true, true, -4.2f, -7f)
    }
    val calendar: ImageVector = glyph("Calendar") {
        moveTo(7f, 5.5f); lineTo(17f, 5.5f); arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
        lineTo(20f, 16.5f); arcToRelative(3f, 3f, 0f, false, true, -3f, 3f); lineTo(7f, 19.5f)
        arcToRelative(3f, 3f, 0f, false, true, -3f, -3f); lineTo(4f, 8.5f)
        arcToRelative(3f, 3f, 0f, false, true, 3f, -3f); close()
        moveTo(4f, 10f); lineTo(20f, 10f); moveTo(8.5f, 3.5f); lineTo(8.5f, 6.5f); moveTo(15.5f, 3.5f); lineTo(15.5f, 6.5f)
    }
    val moon: ImageVector = glyph("Moon") {
        moveTo(20f, 15.5f); arcToRelative(8f, 8f, 0f, false, true, -11.5f, -11.5f)
        arcToRelative(6.5f, 6.5f, 0f, true, false, 11.5f, 11.5f); close()
    }
    val personOff: ImageVector = glyph("PersonOff") {
        moveTo(15f, 9.5f); arcToRelative(3.5f, 3.5f, 0f, true, true, -7f, 0f)
        arcToRelative(3.5f, 3.5f, 0f, true, true, 7f, 0f)
        moveTo(5f, 20f); curveTo(6.5f, 16.5f, 9f, 15f, 12f, 15f)
        curveTo(13.2f, 15f, 14.3f, 15.3f, 15.3f, 15.8f)
        moveTo(3.5f, 3.5f); lineTo(20.5f, 20.5f)
    }
    val device: ImageVector = glyph("Device") {
        moveTo(6f, 6f); lineTo(18f, 6f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        lineTo(20f, 16f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); lineTo(6f, 18f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); lineTo(4f, 8f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
        moveTo(4f, 11f); lineTo(20f, 11f)
    }
    val search: ImageVector = glyph("Search") {
        moveTo(17f, 11f); arcToRelative(6f, 6f, 0f, true, true, -12f, 0f); arcToRelative(6f, 6f, 0f, true, true, 12f, 0f)
        moveTo(20f, 20f); lineTo(15.5f, 15.5f)
    }
}

/** Board units the Today spark is raised by; see [TodayHero]. */
private const val SPARK_LIFT = 52f
