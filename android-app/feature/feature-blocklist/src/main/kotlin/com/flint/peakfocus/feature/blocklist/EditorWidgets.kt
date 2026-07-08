package com.flint.peakfocus.feature.blocklist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintBadge
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel

/**
 * Small building blocks shared by the blocklist editors. Everything derives colors, type,
 * shape, spacing, and motion from FlintTheme's tokens — no literals, per the design-token
 * rule.
 */

/** Section heading used across the overview and editors — the Flint eyebrow idiom. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    FlintSectionLabel(text = text, modifier = modifier)
}

/** Back arrow + the brand signature: headlineMedium title over the uppercase primary
 *  [eyebrow] carrying the editor's context ("BLOCK RULE", "SLEEP MODE", "APP LIMITS"). */
@Composable
internal fun EditorHeader(
    title: String,
    eyebrow: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.width(FlintSpacing.xs))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** The in-place editor↔picker swap, in the app's push/pop motion vocabulary. */
internal fun editorPushPop(forward: Boolean): ContentTransform {
    val direction = if (forward) 1 else -1
    val enterSpec = tween<Float>(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)
    return (
        slideInHorizontally(
            animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
        ) { fullWidth -> direction * fullWidth / 8 } + fadeIn(animationSpec = enterSpec)
        ) togetherWith fadeOut(
        animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
    )
}

/** Reveal spec for conditional editor sections — one motion vocabulary across the editors. */
internal fun <T> editorRevealSpec(): FiniteAnimationSpec<T> =
    tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)

/** A titled switch with an explanatory line underneath; the whole row is the toggle target. */
@Composable
internal fun LabeledSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { value ->
                    if (value) haptics.toggleOn() else haptics.toggleOff()
                    onCheckedChange(value)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(FlintSpacing.cardGap))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The three break tiers as radio rows — all free, Hardcore included (spec 2.3/2.4). */
@Composable
internal fun BreakLevelSelector(
    selected: BreakLevel,
    onSelect: (BreakLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    Column(modifier = modifier.fillMaxWidth().selectableGroup()) {
        BreakLevel.entries.forEach { level ->
            val isSelected = level == selected
            val container by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
                label = "breakLevelRowContainer",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(container)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = {
                            // Choosing Hardcore is a commitment moment, not a light selection.
                            if (level == BreakLevel.HARDCORE) haptics.confirm() else haptics.tick()
                            onSelect(level)
                        },
                    )
                    .padding(horizontal = FlintSpacing.sm, vertical = FlintSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(Modifier.width(FlintSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = breakLevelLabel(level),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = breakLevelDescription(level),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (level == BreakLevel.HARDCORE) {
                    Spacer(Modifier.width(FlintSpacing.sm))
                    FlintBadge(text = breakLevelLabel(level), emphasized = isSelected)
                }
            }
        }
    }
}

/** App icon, or a letter avatar when the icon is missing — one rounded silhouette for both. */
@Composable
internal fun AppIconThumb(
    icon: ImageBitmap?,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier.size(size).clip(MaterialTheme.shapes.small),
        )
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Validation problems in one contained error surface, revealed/dismissed on the editors'
 *  shared motion. The last non-empty list keeps rendering through the exit animation. */
@Composable
internal fun ErrorMessages(messages: List<String>, modifier: Modifier = Modifier) {
    var lastShown by remember { mutableStateOf(messages) }
    if (messages.isNotEmpty()) lastShown = messages
    AnimatedVisibility(
        visible = messages.isNotEmpty(),
        modifier = modifier,
        enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
        exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Column(
                modifier = Modifier.padding(FlintSpacing.md),
                verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
            ) {
                lastShown.forEach { message ->
                    Text(text = message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** One chosen target app in an editor list: icon thumb, label + package, remove affordance —
 *  the same row in every editor, so "your picked apps" reads identically app-wide. */
@Composable
internal fun TargetAppRow(
    app: AppRef,
    icon: ImageBitmap?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = app.label ?: app.packageName
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = FlintSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconThumb(icon = icon, label = label)
        Spacer(Modifier.width(FlintSpacing.cardGap))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (app.label != null) {
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Minutes in a day, as the float the day-strip fractions divide by. */
private const val DAY_STRIP_MINUTES = 1440f

/** The day strip's track height — a component dimension, like [FlintMeterBar]'s 4dp. */
private val DayStripHeight = 12.dp

/**
 * Fractions of a 24h track covered by [startMinute] → [endMinute] (half-open, mirroring the
 * engine's window semantics). Two segments when the window wraps midnight; empty when either
 * endpoint is out of range or the window has zero length (never active in the engine).
 */
internal fun dayStripSegments(startMinute: Int, endMinute: Int): List<Pair<Float, Float>> {
    if (startMinute !in MINUTE_OF_DAY_RANGE || endMinute !in MINUTE_OF_DAY_RANGE) return emptyList()
    return dayStripSegmentFractions(
        startMinute / DAY_STRIP_MINUTES,
        endMinute / DAY_STRIP_MINUTES,
    )
}

/** The wrap-aware split itself, on raw fractions — shared with the animated draw pass. */
internal fun dayStripSegmentFractions(start: Float, end: Float): List<Pair<Float, Float>> =
    when {
        start < end -> listOf(start to end)
        start > end -> listOf(start to 1f, 0f to end)
        else -> emptyList()
    }

/**
 * The schedule editors' 24-hour window visual: a rounded track on the highest container step,
 * the active window filled in primary (two segments when it wraps midnight), quarter-day ticks
 * in outlineVariant, and the endpoints in tabular numerals. [secondaryStartMinute] /
 * [secondaryEndMinute] draw an optional second window in the secondary tone (sleep's Morning
 * Assist extension). Segment ends ease to their new fractions on the long emphasized tween.
 */
@Composable
internal fun FlintDayStrip(
    startMinute: Int,
    endMinute: Int,
    modifier: Modifier = Modifier,
    startLabel: String = "From",
    endLabel: String = "Until",
    secondaryStartMinute: Int? = null,
    secondaryEndMinute: Int? = null,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val fillColor = MaterialTheme.colorScheme.primary
    val secondaryFillColor = MaterialTheme.colorScheme.secondary
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val stripSpec = tween<Float>(FlintMotion.DurationLong, easing = FlintMotion.EasingEmphasized)
    val startFraction by animateFloatAsState(
        targetValue = startMinute.coerceIn(MINUTE_OF_DAY_RANGE) / DAY_STRIP_MINUTES,
        animationSpec = stripSpec,
        label = "dayStripStart",
    )
    val endFraction by animateFloatAsState(
        targetValue = endMinute.coerceIn(MINUTE_OF_DAY_RANGE) / DAY_STRIP_MINUTES,
        animationSpec = stripSpec,
        label = "dayStripEnd",
    )
    val hasSecondary = secondaryStartMinute != null && secondaryEndMinute != null
    val secondaryStartFraction by animateFloatAsState(
        targetValue = (secondaryStartMinute ?: endMinute).coerceIn(MINUTE_OF_DAY_RANGE) /
            DAY_STRIP_MINUTES,
        animationSpec = stripSpec,
        label = "dayStripSecondaryStart",
    )
    val secondaryEndFraction by animateFloatAsState(
        targetValue = (secondaryEndMinute ?: endMinute).coerceIn(MINUTE_OF_DAY_RANGE) /
            DAY_STRIP_MINUTES,
        animationSpec = stripSpec,
        label = "dayStripSecondaryEnd",
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(DayStripHeight)) {
            val radius = CornerRadius(size.height / 2f)
            fun drawWindow(from: Float, to: Float, color: Color) {
                if (to <= from) return
                drawRoundRect(
                    color = color,
                    topLeft = Offset(from * size.width, 0f),
                    size = Size((to - from) * size.width, size.height),
                    cornerRadius = radius,
                )
            }
            fun drawWrappable(from: Float, to: Float, color: Color) {
                dayStripSegmentFractions(from, to).forEach { (a, b) -> drawWindow(a, b, color) }
            }
            drawRoundRect(color = trackColor, cornerRadius = radius)
            listOf(0.25f, 0.5f, 0.75f).forEach { fraction -> // 06:00 / 12:00 / 18:00
                val x = fraction * size.width
                drawLine(
                    color = tickColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            if (hasSecondary) {
                drawWrappable(secondaryStartFraction, secondaryEndFraction, secondaryFillColor)
            }
            drawWrappable(startFraction, endFraction, fillColor)
        }
        Spacer(Modifier.height(FlintSpacing.xs))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$startLabel ${formatMinuteOfDay(startMinute)}",
                style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$endLabel ${formatMinuteOfDay(endMinute)}",
                style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
