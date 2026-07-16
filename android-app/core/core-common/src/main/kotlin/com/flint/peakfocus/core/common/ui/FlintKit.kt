package com.flint.peakfocus.core.common.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing

/**
 * Flint's shared component kit — the coherence lever. Every feature screen builds from these
 * so the app reads as one continuous surface: same card idiom (20dp radius, hairline border,
 * matte container — depth from the tonal ladder, never shadows), same typographic signature
 * (headline + uppercase eyebrow), same pill/badge/status vocabulary. All values come from
 * FlintTheme / tokens.json; nothing here hard-codes a color or an off-scale dimension.
 */

/** The screen-opening signature: headlineMedium title over the uppercase primary eyebrow. */
@Composable
fun FlintScreenHeader(title: String, eyebrow: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (eyebrow != null) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The Flint card: matte tonal container, hairline border, 20dp corners, 16dp inner padding.
 * Pass [onClick] for the tappable variant (ripple + semantics from Material3's Surface,
 * plus a gentle spring press-scale so the card answers the finger physically).
 */
@Composable
fun FlintCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(FlintSpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (pressed) FlintMotion.PressedScale else 1f,
            animationSpec = FlintMotion.SpringSettle,
            label = "flintCardPressScale",
        )
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
            interactionSource = interactionSource,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            border = border,
        ) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * The gentle infinite alpha breath (0.5 → 1.0 on the standard easing) — one shared pulse for
 * skeleton loading, live status dots, and anything "quietly alive". Same recipe as the block
 * screen's breathing mark, so every idle motion in the app shares a heartbeat.
 */
fun Modifier.flintPulse(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "flintPulse")
    val breathAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2 * FlintMotion.DurationLong, easing = FlintMotion.EasingStandard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flintPulseAlpha",
    )
    graphicsLayer { alpha = breathAlpha }
}

/**
 * One loading block: a pulsing box on the highest container step. Width comes from [modifier]
 * (e.g. `Modifier.fillMaxWidth(0.5f)` / `Modifier.width(40.dp)`), height from [height].
 */
@Composable
fun FlintSkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    shape: Shape = MaterialTheme.shapes.small,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    Box(
        modifier
            .height(height)
            .clip(shape)
            .flintPulse()
            .background(color),
    )
}

/** The prefab list-row skeleton (40dp thumb + two bars) — the one loading idiom app-wide. */
@Composable
fun FlintSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = FlintSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
    ) {
        FlintSkeletonBlock(Modifier.width(40.dp), height = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs)) {
            FlintSkeletonBlock(Modifier.fillMaxWidth(0.5f), height = 14.dp)
            FlintSkeletonBlock(
                Modifier.fillMaxWidth(0.3f),
                height = 10.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
}

/**
 * 8dp status dot — primary = live/healthy, onSurfaceVariant = idle, error = broken.
 * [pulsing] adds the shared breath; reserve it for "live right now" states so the app
 * keeps exactly one quietly-alive ember per screen.
 */
@Composable
fun FlintStatusDot(color: Color, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val base = if (pulsing) modifier.flintPulse() else modifier
    Box(base.size(FlintSpacing.sm).background(color, CircleShape))
}

/** Uppercase pill badge; [emphasized] renders spark-filled (the HARDCORE treatment). */
@Composable
fun FlintBadge(text: String, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    val container = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (emphasized) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(
                horizontal = FlintSpacing.sm + FlintSpacing.xs,
                vertical = FlintSpacing.xs,
            ),
        )
    }
}

/** Pill for live values; [spokenText] can replace abbreviated visual copy for screen readers. */
@Composable
fun FlintInfoPill(text: String, modifier: Modifier = Modifier, spokenText: String? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(percent = 50),
        modifier = if (spokenText != null) {
            modifier.clearAndSetSemantics { contentDescription = spokenText }
        } else {
            modifier
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.merge(FlintNumerals),
            modifier = Modifier.padding(horizontal = FlintSpacing.md, vertical = FlintSpacing.sm),
        )
    }
}

/**
 * Thin horizontal meter — track on the highest container step, secondary fill. The fraction
 * change animates here (emphasized long tween), so callers pass the raw target value.
 */
@Composable
fun FlintMeterBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(FlintMotion.DurationLong, easing = FlintMotion.EasingEmphasized),
        label = "flintMeterFill",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animatedFraction)
                .height(height)
                .background(
                    color = MaterialTheme.colorScheme.secondary,
                    shape = MaterialTheme.shapes.small,
                ),
        )
    }
}

/** Uppercase section label — the eyebrow style in its quiet, on-surface-variant role. */
@Composable
fun FlintSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The one time-input idiom app-wide: a tappable hairline field (eyebrow label over the time
 * in tabular numerals) that opens [FlintTimePickerDialog]. The canonical exchange format is
 * "HH:mm" text — callers keep text as their draft source of truth, so their existing
 * parse/validate paths are untouched. A [valueText] that doesn't parse renders as a
 * placeholder and the dialog opens at [fallbackMinutes].
 */
@Composable
fun FlintTimeField(
    label: String,
    valueText: String,
    onValueText: (String) -> Unit,
    modifier: Modifier = Modifier,
    fallbackMinutes: Int = 9 * 60,
) {
    var showDialog by remember { mutableStateOf(false) }
    val parsed = parseMinuteOfDayText(valueText)
    Surface(
        onClick = { showDialog = true },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = FlintSpacing.md, vertical = FlintSpacing.sm + FlintSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (parsed != null) valueText else "--:--",
                style = MaterialTheme.typography.titleLarge.merge(FlintNumerals),
            )
        }
    }
    if (showDialog) {
        FlintTimePickerDialog(
            title = label,
            initialMinutes = parsed ?: fallbackMinutes,
            onConfirm = { minutes ->
                onValueText(formatMinuteOfDayText(minutes))
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Material3 TimePicker in an AlertDialog (material3 1.3 ships no TimePickerDialog composable).
 * is24Hour is pinned — not the locale default — to keep the "HH:mm" contract callers persist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlintTimePickerDialog(
    title: String,
    initialMinutes: Int,
    onConfirm: (minuteOfDay: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val clamped = initialMinutes.coerceIn(0, 24 * 60 - 1)
    val state = rememberTimePickerState(
        initialHour = clamped / 60,
        initialMinute = clamped % 60,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** "HH:mm" for a minute-of-day — the inverse of [parseMinuteOfDayText]. */
fun formatMinuteOfDayText(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

/** Strict "H:mm"/"HH:mm" (0–23:59) → minute-of-day, or null when the text isn't a time. */
fun parseMinuteOfDayText(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
