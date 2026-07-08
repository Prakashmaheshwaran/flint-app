package com.flint.peakfocus.feature.stats

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintMeterBar
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.FlintSkeletonBlock
import com.flint.peakfocus.core.common.ui.FlintSkeletonRow
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.UsageStat
import com.flint.peakfocus.permissions.UsageAccess
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Content-intrinsic chart dimensions (not spacing rhythm): plot height, the zero-day stub,
// and the floor that keeps a tiny-but-nonzero day visible.
private val ChartHeight = 120.dp
private val BarStubHeight = 2.dp
private val BarMinHeight = 3.dp

// Bars grow out of the baseline, so corners round on top only; below GradientMinHeight the
// selected bar falls back to solid primary so the gradient never crushes into a sliver.
private val BarShape = RoundedCornerShape(
    topStart = 8.dp,
    topEnd = 8.dp,
    bottomEnd = 0.dp,
    bottomStart = 0.dp,
)
private val GradientMinHeight = 12.dp
private val AppIconSize = 40.dp
private val EntranceOffset = 16.dp
private val TypicalTagLift = 18.dp
private val TypicalDash = 4.dp

// Stagger steps derived from the motion vocabulary: bars/meters cascade in DurationShort
// thirds, cards in halves, so the whole reveal reads as one sweep, not seven animations.
private const val BarStaggerStepMillis = FlintMotion.DurationShort / 3
private const val CardStaggerStepMillis = FlintMotion.DurationShort / 2

/**
 * Screen-time reports: today's total, a 7-day bar comparison, and today's top apps by
 * foreground time. Public entry point for the A-VERIFY integrator to wire into MainActivity
 * navigation — this module does not touch the nav graph itself.
 *
 * Handles the Usage Access special grant: when missing, shows a friendly explainer whose
 * button hands off to Settings ([UsageAccess.settingsIntent]), and re-checks on ON_RESUME —
 * the same pattern MainActivity uses for the accessibility grant — so coming back from
 * Settings updates the screen immediately.
 */
@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val statsViewModel: StatsViewModel =
        viewModel(factory = remember(context) { StatsViewModel.factory(context) })
    val state by statsViewModel.uiState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statsViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Keyed on the state *class* so Ready→Ready refreshes swap data without re-running
        // the whole-screen transition (the per-card entrance handles those).
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard)) +
                    slideInVertically(
                        tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
                    ) { height -> height / 24 }) togetherWith
                    fadeOut(tween(FlintMotion.DurationShort))
            },
            label = "statsState",
        ) { s ->
            when (s) {
                is StatsUiState.Loading -> LoadingState()
                is StatsUiState.PermissionRequired -> UsageAccessPrompt(
                    onOpenSettings = {
                        context.startActivity(
                            UsageAccess.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                is StatsUiState.Ready -> StatsReport(s.report)
            }
        }
    }
}

/** Skeleton that echoes the report layout, so Ready lands in place instead of reflowing. */
@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FlintSpacing.gutter),
    ) {
        FlintSkeletonBlock(Modifier.fillMaxWidth(0.45f), height = 28.dp)
        Spacer(Modifier.height(FlintSpacing.sm))
        FlintSkeletonBlock(Modifier.width(96.dp), height = 10.dp)
        Spacer(Modifier.height(FlintSpacing.md))
        FlintCard {
            FlintSkeletonBlock(Modifier.width(56.dp), height = 10.dp)
            Spacer(Modifier.height(FlintSpacing.sm))
            FlintSkeletonBlock(Modifier.fillMaxWidth(0.5f), height = 36.dp)
            Spacer(Modifier.height(FlintSpacing.sm))
            FlintSkeletonBlock(Modifier.fillMaxWidth(0.35f), height = 10.dp)
        }
        Spacer(Modifier.height(FlintSpacing.cardGap))
        FlintCard {
            FlintSkeletonBlock(Modifier.width(80.dp), height = 10.dp)
            Spacer(Modifier.height(FlintSpacing.md))
            FlintSkeletonBlock(
                Modifier.fillMaxWidth(),
                height = ChartHeight,
                shape = MaterialTheme.shapes.medium,
            )
        }
        Spacer(Modifier.height(FlintSpacing.cardGap))
        FlintCard {
            FlintSkeletonBlock(Modifier.width(96.dp), height = 10.dp)
            Spacer(Modifier.height(FlintSpacing.xs))
            repeat(3) { FlintSkeletonRow() }
        }
    }
}

/** Friendly no-permission state; explains the grant in Flint's local-only, no-telemetry voice. */
@Composable
private fun UsageAccessPrompt(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FlintSpacing.gutter),
    ) {
        FlintScreenHeader(title = "See where your time goes", eyebrow = "Screen time")
        Spacer(Modifier.height(FlintSpacing.md))
        FlintCard {
            Text(
                text = "To show screen-time reports, Flint needs Android's Usage Access. It reads " +
                    "which apps were in the foreground and for how long — nothing else.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FlintSpacing.cardGap))
            Text(
                text = "Like everything in Flint, this stays on your device. No account, no " +
                    "analytics — nothing leaves your phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FlintSpacing.cardGap))
            Text(
                text = "On the next screen, find “Flint” under Usage access and switch it on, then " +
                    "come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FlintSpacing.lg))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Allow usage access")
            }
        }
    }
}

/** Per-card entrance: alpha + translationY sweep staggered by [cardIndex]. */
@Composable
private fun Modifier.cardEntrance(revealed: Boolean, cardIndex: Int): Modifier {
    val progress by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(
            FlintMotion.DurationMedium,
            delayMillis = cardIndex * CardStaggerStepMillis,
            easing = FlintMotion.EasingEmphasized,
        ),
        label = "cardEntrance",
    )
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * EntranceOffset.toPx()
    }
}

@Composable
private fun StatsReport(report: UsageReport) {
    // Reveal gate keyed on report identity: the composed entrance (cards → bars → meters)
    // replays only when the data actually changes, not on every ON_RESUME refresh that
    // yields an equal report.
    var revealed by remember(report) { mutableStateOf(false) }
    LaunchedEffect(report) { revealed = true }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(FlintSpacing.gutter),
    ) {
        FlintScreenHeader(
            title = "Screen time",
            eyebrow = "Last 7 days",
            modifier = Modifier.cardEntrance(revealed, 0),
        )
        Spacer(Modifier.height(FlintSpacing.md))
        TodayCard(
            todayTotalMillis = report.todayTotalMillis,
            shareOfTypical = report.todayShareOfTypical,
            revealed = revealed,
            modifier = Modifier.cardEntrance(revealed, 1),
        )
        Spacer(Modifier.height(FlintSpacing.cardGap))
        WeekChartCard(report, modifier = Modifier.cardEntrance(revealed, 2))
        if (report.weekTotalMillis > 0L) {
            Spacer(Modifier.height(FlintSpacing.cardGap))
            InsightsCard(report, modifier = Modifier.cardEntrance(revealed, 3))
        }
        Spacer(Modifier.height(FlintSpacing.cardGap))
        TopAppsCard(
            topApps = report.topAppsToday,
            todayTotalMillis = report.todayTotalMillis,
            modifier = Modifier.cardEntrance(revealed, 4),
        )
    }
}

@Composable
private fun TodayCard(
    todayTotalMillis: Long,
    shareOfTypical: Float?,
    revealed: Boolean,
    modifier: Modifier = Modifier,
) {
    // Hero count-up shares the bars' report-identity gate; every frame goes through the real
    // duration formatter and FlintNumerals keeps the digits from jittering as they change.
    val countUp by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(FlintMotion.DurationLong, easing = FlintMotion.EasingEmphasized),
        label = "todayCountUp",
    )
    FlintCard(modifier = modifier) {
        FlintSectionLabel("Today")
        Spacer(Modifier.height(FlintSpacing.xs))
        Text(
            text = DurationFormat.format((todayTotalMillis * countUp).toLong()),
            style = MaterialTheme.typography.displaySmall.merge(FlintNumerals),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "of screen time so far",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Meter + percent only when a typical-day baseline exists — never faked against nothing.
        if (shareOfTypical != null) {
            Spacer(Modifier.height(FlintSpacing.md))
            Text(
                text = "${(shareOfTypical * 100).roundToInt()}% of a typical day so far",
                style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FlintSpacing.xs))
            FlintMeterBar(if (revealed) shareOfTypical.coerceAtMost(1f) else 0f)
        }
    }
}

/**
 * "Week in review" — two honest, at-a-glance facts derived from the 7-day window: the average
 * of a *completed* day (so the partial today doesn't understate it) and the heaviest single day.
 * Only shown when there's usage to summarize (guarded by the caller).
 */
@Composable
private fun InsightsCard(report: UsageReport, modifier: Modifier = Modifier) {
    FlintCard(modifier = modifier) {
        FlintSectionLabel("Week in review")
        if (report.completedDays.isNotEmpty()) {
            Spacer(Modifier.height(FlintSpacing.cardGap))
            InsightRow("Typical day", DurationFormat.format(report.typicalDayMillis))
        }
        report.busiestDay?.let { busiest ->
            Spacer(Modifier.height(FlintSpacing.cardGap))
            InsightRow(
                label = "Busiest day",
                value = "${busiest.label} · ${DurationFormat.format(busiest.totalMillis)}",
            )
        }
    }
}

@Composable
private fun InsightRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.merge(FlintNumerals),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Day-by-day comparison: plain Compose boxes scaled against the busiest day — no chart library. */
@Composable
private fun WeekChartCard(report: UsageReport, modifier: Modifier = Modifier) {
    val haptics = rememberFlintHaptics()
    // Today preselected; a tap moves the accent — one ember, following the selection.
    var selectedIndex by remember(report.days) {
        mutableIntStateOf(
            report.days.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: report.days.lastIndex,
        )
    }
    FlintCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlintSectionLabel("Last 7 days", modifier = Modifier.weight(1f))
            Text(
                text = DurationFormat.format(report.weekTotalMillis),
                style = MaterialTheme.typography.bodyMedium.merge(FlintNumerals),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(FlintSpacing.md))
        if (report.weekTotalMillis == 0L) {
            Text(
                text = "No usage recorded yet. Use your phone for a bit, then check back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            WeekChart(
                days = report.days,
                typicalDayMillis = report.typicalDayMillis,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    if (index != selectedIndex) {
                        selectedIndex = index
                        haptics.tick()
                    }
                },
            )
            val selected = report.days.getOrNull(selectedIndex)
            if (selected != null) {
                Spacer(Modifier.height(FlintSpacing.cardGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Selected day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            (fadeIn(
                                tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
                            ) + slideInVertically(
                                tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
                            ) { height -> height / 2 }) togetherWith
                                fadeOut(
                                    tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
                                )
                        },
                        label = "dayReadout",
                    ) { day ->
                        Text(
                            text = "${day.label} · ${DurationFormat.format(day.totalMillis)}",
                            style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(FlintSpacing.cardGap))
            Row {
                Text(
                    text = "Daily average",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DurationFormat.format(report.dailyAverageMillis),
                    style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun WeekChart(
    days: List<DaySummary>,
    typicalDayMillis: Long,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    // Grow-in gate keyed on report identity: bars replay only when the data actually changes,
    // not on every ON_RESUME refresh that yields an equal report.
    var grown by remember(days) { mutableStateOf(false) }
    LaunchedEffect(days) { grown = true }
    val maxDayMillis = days.maxOf { it.totalMillis }.coerceAtLeast(1L)
    // Typical ≤ max by construction (it averages completed days); null hides the reference
    // line entirely when no full day has elapsed — no baseline, no line.
    val typicalFraction = if (typicalDayMillis > 0L) {
        (typicalDayMillis.toFloat() / maxDayMillis).coerceIn(0f, 1f)
    } else {
        null
    }
    // Second beat of the reveal: the reference line waits for the bars' grow-in to finish.
    val typicalAlpha by animateFloatAsState(
        targetValue = if (grown && typicalFraction != null) 1f else 0f,
        animationSpec = tween(
            FlintMotion.DurationMedium,
            delayMillis = FlintMotion.DurationLong,
            easing = FlintMotion.EasingStandard,
        ),
        label = "typicalLineFade",
    )
    val outline = MaterialTheme.colorScheme.outline
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (typicalFraction != null && typicalAlpha > 0f) {
                        val y = size.height * (1f - typicalFraction)
                        drawLine(
                            color = outline,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(TypicalDash.toPx(), TypicalDash.toPx()),
                            ),
                            alpha = typicalAlpha,
                        )
                    }
                },
        ) {
            days.forEachIndexed { index, day ->
                DayBar(
                    day = day,
                    index = index,
                    maxDayMillis = maxDayMillis,
                    grown = grown,
                    selected = index == selectedIndex,
                    onSelect = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (typicalFraction != null) {
            Text(
                text = "TYPICAL",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        y = (ChartHeight * (1f - typicalFraction) - TypicalTagLift)
                            .coerceAtLeast(0.dp),
                    )
                    .graphicsLayer { alpha = typicalAlpha },
            )
        }
    }
    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(FlintSpacing.sm))
    // Label cells mirror the bar columns (no spacedBy) so centers stay aligned.
    Row(modifier = Modifier.fillMaxWidth()) {
        days.forEachIndexed { index, day ->
            Text(
                text = day.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (index == selectedIndex) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayBar(
    day: DaySummary,
    index: Int,
    maxDayMillis: Long,
    grown: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = (day.totalMillis.toFloat() / maxDayMillis).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = if (grown) fraction else 0f,
        animationSpec = tween(
            FlintMotion.DurationLong,
            delayMillis = index * BarStaggerStepMillis,
            easing = FlintMotion.EasingEmphasized,
        ),
        label = "dayBarGrowIn",
    )
    val barHeight =
        if (day.totalMillis <= 0L) BarStubHeight
        else (ChartHeight * animatedFraction).coerceAtLeast(BarMinHeight)
    // Spark is rationed to the one selected bar; solid primary below the gradient's legible floor.
    val barBrush = when {
        !selected -> SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
        barHeight < GradientMinHeight -> SolidColor(MaterialTheme.colorScheme.primary)
        else -> Brush.verticalGradient(
            listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary),
        )
    }
    val description = "${day.label}, ${DurationFormat.format(day.totalMillis)}"
    // The whole column cell — bar plus its gap, full chart height — is the tap target, so the
    // hit area stays finger-sized even though the visible bar is narrower.
    Box(
        modifier = modifier
            .height(ChartHeight)
            .selectable(selected = selected, onClick = onSelect)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = FlintSpacing.xs)
                .height(barHeight)
                .background(barBrush, BarShape),
        )
    }
}

@Composable
private fun TopAppsCard(
    topApps: List<UsageStat>,
    todayTotalMillis: Long,
    modifier: Modifier = Modifier,
) {
    FlintCard(modifier = modifier) {
        FlintSectionLabel("Top apps today")
        Spacer(Modifier.height(FlintSpacing.cardGap))
        if (topApps.isEmpty()) {
            Text(
                text = "Nothing recorded yet today.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            var grown by remember(topApps) { mutableStateOf(false) }
            LaunchedEffect(topApps) { grown = true }
            val maxMillis = topApps.maxOf { it.foregroundMillis }.coerceAtLeast(1L)
            Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.md)) {
                topApps.forEachIndexed { index, stat ->
                    TopAppRow(
                        stat = stat,
                        index = index,
                        maxMillis = maxMillis,
                        todayTotalMillis = todayTotalMillis,
                        grown = grown,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopAppRow(
    stat: UsageStat,
    index: Int,
    maxMillis: Long,
    todayTotalMillis: Long,
    grown: Boolean,
) {
    // FlintMeterBar owns its grow-in tween, so the top→bottom cascade delays each row's gate
    // flip rather than the tween itself.
    var rowGrown by remember(stat, grown) { mutableStateOf(false) }
    LaunchedEffect(stat, grown) {
        if (grown) {
            delay(index.toLong() * BarStaggerStepMillis)
            rowGrown = true
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
        ) {
            AppIconThumb(packageName = stat.packageName, label = stat.label)
            Column(Modifier.weight(1f)) {
                Text(
                    text = stat.label ?: stat.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val share = shareOfToday(stat.foregroundMillis, todayTotalMillis)
                if (share != null) {
                    Text(
                        text = "${(share * 100).roundToInt()}% of today",
                        style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = DurationFormat.format(stat.foregroundMillis),
                style = MaterialTheme.typography.bodyMedium.merge(FlintNumerals),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(FlintSpacing.sm))
        val fraction = (stat.foregroundMillis.toFloat() / maxMillis).coerceIn(0f, 1f)
        FlintMeterBar(if (rowGrown) fraction else 0f)
    }
}

/** Real app icon, remember-cached per package; fallback is a tonal square with the initial. */
@Composable
private fun AppIconThumb(packageName: String, label: String?) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { AppIconSize.roundToPx() }
    val iconBitmap = remember(packageName, sizePx) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toIconBitmap(sizePx)
        }.getOrNull()
    }
    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier
                .size(AppIconSize)
                .clip(MaterialTheme.shapes.small),
        )
    } else {
        Box(
            modifier = Modifier
                .size(AppIconSize)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.shapes.small,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ((label ?: packageName).firstOrNull()?.uppercaseChar() ?: '?').toString(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Rasterizes an app-icon [Drawable] at [sizePx] via a framework Canvas. androidx.core's
 * `toBitmap()` isn't on this module's compile classpath, and adaptive icons aren't
 * BitmapDrawables anyway — drawing into our own small bitmap handles every drawable kind.
 */
private fun Drawable.toIconBitmap(sizePx: Int): ImageBitmap {
    val size = sizePx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, size, size)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

// --- Previews (fake data only; no ViewModel) ---

private fun previewReport(): UsageReport {
    val hour = 3_600_000L
    val minute = 60_000L
    return UsageReport(
        days = listOf(
            DaySummary("Fri", 4 * hour + 12 * minute, isToday = false),
            DaySummary("Sat", 6 * hour + 40 * minute, isToday = false),
            DaySummary("Sun", 5 * hour + 5 * minute, isToday = false),
            DaySummary("Mon", 3 * hour + 30 * minute, isToday = false),
            DaySummary("Tue", 2 * hour + 55 * minute, isToday = false),
            DaySummary("Wed", 4 * hour, isToday = false),
            DaySummary("Thu", 1 * hour + 47 * minute, isToday = true),
        ),
        todayTotalMillis = 1 * hour + 47 * minute,
        weekTotalMillis = 28 * hour + 9 * minute,
        topAppsToday = listOf(
            UsageStat("com.instagram.android", "Instagram", 42 * minute),
            UsageStat("com.google.android.youtube", "YouTube", 31 * minute),
            UsageStat("com.android.chrome", "Chrome", 18 * minute),
            UsageStat("org.thoughtcrime.securesms", null, 9 * minute),
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun StatsReportPreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StatsReport(previewReport())
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LoadingStatePreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            LoadingState()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UsageAccessPromptPreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            UsageAccessPrompt(onOpenSettings = {})
        }
    }
}
