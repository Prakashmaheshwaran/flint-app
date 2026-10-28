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
    val transition = rememberInfiniteTransition(label = "emberGlow")
    val alpha by transition.animateFloat(
        initialValue = 0.75f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(FlintMotion.GlowCycle / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "emberGlowAlpha",
    )
    return alpha
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
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp)
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
        val shape = if (plan.singleRow) CircleShape else RoundedCornerShape(32.dp)
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
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Canvas(Modifier.size(22.dp)) { drawTabGlyph(tab, isSelected, tint) }
                Text(
                    tab.label,
                    style = styles.getValue(tab),
                    color = tint,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Color(0xF0181618))
                .border(1.dp, FlintPalette.Divider, shape)
                .selectableGroup(),
        ) {
            plan.rows.forEach { tabs ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
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
            line(5f, 7f, 19f, 7f); line(5f, 12f, 15f, 12f); line(5f, 17f, 11f, 17f, accent)
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
    val search: ImageVector = glyph("Search") {
        moveTo(17f, 11f); arcToRelative(6f, 6f, 0f, true, true, -12f, 0f); arcToRelative(6f, 6f, 0f, true, true, 12f, 0f)
        moveTo(20f, 20f); lineTo(15.5f, 15.5f)
    }
}
