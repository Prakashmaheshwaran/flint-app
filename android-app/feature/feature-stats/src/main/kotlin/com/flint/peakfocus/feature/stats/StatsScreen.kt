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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.FlintAppTile
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintMeterBar
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.FlintSegmented
import com.flint.peakfocus.core.common.ui.FlintSkeletonBlock
import com.flint.peakfocus.core.common.ui.FlintSkeletonRow
import com.flint.peakfocus.core.common.ui.FlintTopGlow
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

private val UsagePeriodSaver = Saver<UsagePeriod, String>(
    save = { period ->
        when (period) {
            UsagePeriod.Today -> "today"
            UsagePeriod.Week -> "week"
            is UsagePeriod.Day -> period.startMillis.toString()
        }
    },
    restore = { key ->
        when (key) {
            "week" -> UsagePeriod.Week
            else -> key.toLongOrNull()?.let(UsagePeriod::Day) ?: UsagePeriod.Today
        }
    },
)

/**
 * Screen-time reports: today's total, a 7-day bar comparison, and selectable-period top apps by
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
                is StatsUiState.Error -> UsageError(onRetry = { statsViewModel.refresh() })
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

@Composable
private fun UsageError(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        FlintTopGlow(height = 240.dp, emberAlpha = 0.12f)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text("Insights", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(FlintSpacing.md))
            FocusSessionsSection()
            FlintCard {
                Text("Screen usage unavailable", style = MaterialTheme.typography.titleMedium)
                Text("Couldn't load screen usage. Try again.", modifier = Modifier.padding(vertical = FlintSpacing.sm))
                com.flint.peakfocus.core.common.ui.EmberButton("Retry screen usage", onRetry)
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
        FocusSessionsSection()
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
    Box(Modifier.fillMaxSize()) {
        FlintTopGlow(height = 240.dp, emberAlpha = 0.12f)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
        Text("Insights", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(FlintSpacing.md))
        FocusSessionsSection()
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
            com.flint.peakfocus.core.common.ui.EmberButton("Allow usage access", onOpenSettings)
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
    var requestedPeriod by rememberSaveable(stateSaver = UsagePeriodSaver) {
        mutableStateOf<UsagePeriod>(UsagePeriod.Today)
    }
    val period = report.resolvePeriod(requestedPeriod)
    LaunchedEffect(period) { requestedPeriod = period }
    // Reveal gate keyed on report identity: the composed entrance (cards → bars → meters)
    // replays only when the data actually changes, not on every ON_RESUME refresh that
    // yields an equal report.
    var revealed by remember(report) { mutableStateOf(false) }
    LaunchedEffect(report) { revealed = true }
    Box(Modifier.fillMaxSize()) {
        FlintTopGlow(height = 240.dp, emberAlpha = 0.12f)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Insights", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                FlintSegmented(
                    options = listOf(UsagePeriod.Today to "Today", UsagePeriod.Week to "7 days"),
                    selected = if (period == UsagePeriod.Today) UsagePeriod.Today else UsagePeriod.Week,
                    onSelect = { requestedPeriod = it },
                    compact = true,
                )
            }
            Spacer(Modifier.height(16.dp))
            ComparisonCard(
                todayTotalMillis = report.todayTotalMillis,
                typicalDayMillis = report.typicalDayMillis,
                revealed = revealed,
                modifier = Modifier.cardEntrance(revealed, 1),
            )
            Spacer(Modifier.height(22.dp))
            MostReachedSection(
                insights = report.appInsights(period),
                modifier = Modifier.cardEntrance(revealed, 2),
            )
            Spacer(Modifier.height(24.dp))
            WeekStrip(
                report = report,
                selectedIndex = when (period) {
                    UsagePeriod.Today -> report.days.indexOfLast { it.isToday }
                    UsagePeriod.Week -> -1
                    is UsagePeriod.Day -> report.days.indexOfFirst { it.startMillis == period.startMillis }
                },
                onSelect = { index ->
                    if (index != -1) {
                        val day = report.days[index]
                        requestedPeriod = if (day.isToday) UsagePeriod.Today else UsagePeriod.Day(day.startMillis)
                    }
                },
                modifier = Modifier.cardEntrance(revealed, 3),
            )
            Spacer(Modifier.height(24.dp))
            FocusSessionsSection()
            Spacer(Modifier.height(FlintSpacing.lg))
        }
    }
}

/**
 * M12's comparison card: one honest sentence built from today's measured total and the 7-day
 * baseline, then two bars on a shared scale. No baseline means no comparison sentence.
 */
@Composable
private fun ComparisonCard(
    todayTotalMillis: Long,
    typicalDayMillis: Long,
    revealed: Boolean,
    modifier: Modifier = Modifier,
) {
    val maxMillis = maxOf(todayTotalMillis, typicalDayMillis, 1L)
    val todayFraction by animateFloatAsState(
        targetValue = if (revealed) (todayTotalMillis.toFloat() / maxMillis).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(FlintMotion.DurationLong, easing = FlintMotion.EasingEmphasized),
        label = "insightsTodayBar",
    )
    val typicalFraction by animateFloatAsState(
        targetValue = if (revealed) (typicalDayMillis.toFloat() / maxMillis).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(FlintMotion.DurationLong, delayMillis = BarStaggerStepMillis, easing = FlintMotion.EasingEmphasized),
        label = "insightsTypicalBar",
    )
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(FlintPalette.Card)
            .border(1.dp, FlintPalette.Hairline, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val today = DurationFormat.format(todayTotalMillis)
        val baseline = if (typicalDayMillis > 0L) {
            val diff = todayTotalMillis - typicalDayMillis
            val direction = if (diff >= 0) "more" else "less"
            "You spent $today on screen today, ${DurationFormat.format(kotlin.math.abs(diff))} $direction than your 7-day average."
        } else {
            "You spent $today on screen today."
        }
        Text(
            buildAnnotatedString {
                val marker = today
                val index = baseline.indexOf(marker)
                if (index >= 0 && todayTotalMillis > 0) {
                    append(baseline.substring(0, index))
                    withStyle(androidx.compose.ui.text.SpanStyle(color = FlintPalette.EmberText)) {
                        append(marker)
                    }
                    append(baseline.substring(index + marker.length))
                } else {
                    append(baseline)
                }
            },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(FlintPalette.Hairline))
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ComparisonBar(
                    label = DurationFormat.format(todayTotalMillis),
                    caption = "Screen time today",
                    fraction = todayFraction,
                    ember = true,
                    modifier = Modifier.weight(1f),
                )
                ComparisonBar(
                    label = if (typicalDayMillis > 0L) DurationFormat.format(typicalDayMillis) else "—",
                    caption = "Your 7-day average",
                    fraction = typicalFraction,
                    ember = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            "Measured on this phone. Nothing is uploaded.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = FlintPalette.Stone,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ComparisonBar(
    label: String,
    caption: String,
    fraction: Float,
    ember: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(140.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .width(64.dp)
                    .height((140.dp * fraction).coerceAtLeast(4.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (ember) {
                            Brush.verticalGradient(
                                listOf(
                                    FlintPalette.EmberTop,
                                    FlintPalette.Ember,
                                    FlintPalette.Ember.copy(alpha = 0.25f),
                                ),
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    FlintPalette.Bone.copy(alpha = 0.22f),
                                    FlintPalette.Bone.copy(alpha = 0.10f),
                                    FlintPalette.Bone.copy(alpha = 0.03f),
                                ),
                            )
                        },
                    ),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleLarge.merge(FlintNumerals),
            color = if (ember) FlintPalette.Bone else FlintPalette.Mist,
            maxLines = 1,
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = FlintPalette.Mist,
            textAlign = TextAlign.Center,
        )
    }
}

/** M12's "Most reached for": real app icons, a proportional hairline bar and measured time. */
@Composable
private fun MostReachedSection(insights: AppInsights, modifier: Modifier = Modifier) {
    val topApps = insights.topApps
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text("Most reached for", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "time",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = FlintPalette.Mist,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (topApps.isEmpty()) {
            Text(
                "No app usage recorded for this period. Your device may not retain older usage records.",
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            val maxMillis = topApps.maxOf { it.foregroundMillis }.coerceAtLeast(1L)
            topApps.take(3).forEachIndexed { index, stat ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(FlintPalette.Hairline.copy(alpha = 0.7f)))
                MostReachedRow(stat = stat, maxMillis = maxMillis)
            }
        }
    }
}

@Composable
private fun MostReachedRow(stat: UsageStat, maxMillis: Long) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlintAppTile(
            packageName = stat.packageName,
            label = stat.label,
            size = 36.dp,
            cornerRadius = 11.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                stat.label ?: stat.packageName,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FlintPalette.Bone.copy(alpha = 0.08f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((stat.foregroundMillis.toFloat() / maxMillis).coerceIn(0f, 1f))
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(FlintPalette.Ember),
                )
            }
        }
        Text(
            DurationFormat.format(stat.foregroundMillis),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp).merge(FlintNumerals),
            color = FlintPalette.Mist,
            maxLines = 1,
        )
    }
}

/** M12's compact week strip: seven bars on a shared scale; today is ember, empty days dashed. */
@Composable
private fun WeekStrip(
    report: UsageReport,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = report.days
    if (days.isEmpty()) return
    val maxMillis = days.maxOf { it.totalMillis }.coerceAtLeast(1L)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEachIndexed { index, day ->
                val fraction = (day.totalMillis.toFloat() / maxMillis).coerceIn(0f, 1f)
                val isToday = day.isToday
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { contentDescription = "${day.dateLabel}, ${DurationFormat.format(day.totalMillis)}" }
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (day.totalMillis <= 0L) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .border(1.dp, FlintPalette.Bone.copy(alpha = 0.2f), RoundedCornerShape(2.dp)),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((40.dp * fraction).coerceAtLeast(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isToday) FlintPalette.Ember else FlintPalette.Bone.copy(alpha = 0.18f)),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEachIndexed { index, day ->
                Text(
                    day.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = if (index == selectedIndex) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                    ),
                    color = if (index == selectedIndex) FlintPalette.Bone else FlintPalette.Stone,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TodayCard(
    todayTotalMillis: Long,
    shareOfTypical: Float?,
    revealed: Boolean,
    modifier: Modifier = Modifier,
) {
    FlintCard(modifier = modifier) {
        FlintSectionLabel("Today")
        Spacer(Modifier.height(FlintSpacing.xs))
        Text(
            text = DurationFormat.format(todayTotalMillis),
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
private fun WeekChartCard(
    report: UsageReport,
    period: UsagePeriod,
    onSelectPeriod: (UsagePeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    val selectedIndex = when (period) {
        UsagePeriod.Today -> report.days.indexOfLast { it.isToday }
        UsagePeriod.Week -> -1
        is UsagePeriod.Day -> report.days.indexOfFirst { it.startMillis == period.startMillis }
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
        Spacer(Modifier.height(FlintSpacing.sm))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm),
        ) {
            FilterChip(
                selected = period == UsagePeriod.Today,
                onClick = { onSelectPeriod(UsagePeriod.Today) },
                label = { Text("Today") },
            )
            FilterChip(
                selected = period == UsagePeriod.Week,
                onClick = { onSelectPeriod(UsagePeriod.Week) },
                label = { Text("Last 7 days") },
            )
        }
        Text(
            text = "Tap a day to see its apps below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FlintSpacing.md))
        if (report.weekTotalMillis == 0L) {
            Text(
                text = "No usage recorded in this period. Older records may be unavailable on your device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FlintSpacing.md))
        }
        if (report.days.isNotEmpty()) {
            WeekChart(
                days = report.days,
                typicalDayMillis = report.typicalDayMillis,
                selectedIndex = selectedIndex,
                onSelect = { index ->
                    if (index != selectedIndex) {
                        val day = report.days[index]
                        onSelectPeriod(if (day.isToday) UsagePeriod.Today else UsagePeriod.Day(day.startMillis))
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
                        modifier = Modifier.weight(2f),
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
                            text = "${day.dateLabel} · ${DurationFormat.format(day.totalMillis)}",
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
    val description = "${day.dateLabel}, ${DurationFormat.format(day.totalMillis)}, show app usage"
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
    insights: AppInsights,
    modifier: Modifier = Modifier,
) {
    val topApps = insights.topApps
    FlintCard(modifier = modifier) {
        FlintSectionLabel("Top apps · ${insights.label}")
        Spacer(Modifier.height(FlintSpacing.xs))
        Text(
            text = "${DurationFormat.format(insights.totalMillis)} total screen time",
            style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FlintSpacing.cardGap))
        if (topApps.isEmpty()) {
            Text(
                text = "No app usage recorded for this period. " +
                    "Your device may not retain older usage records.",
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
                        periodTotalMillis = insights.totalMillis,
                        shareLabel = insights.shareLabel,
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
    periodTotalMillis: Long,
    shareLabel: String,
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
                val share = shareOfPeriod(stat.foregroundMillis, periodTotalMillis)
                if (share != null) {
                    Text(
                        text = "${(share * 100).roundToInt()}% of $shareLabel",
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
    val start = 1_791_504_000_000L // October 9, 2026, UTC; preview data only.
    val windows = List(7) { index ->
        DayWindow(start + index * 24 * hour, start + (index + 1) * 24 * hour)
    }
    val names = mapOf(
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.android.chrome" to "Chrome",
    )
    return buildUsageReport(
        DayBuckets(windows, mapOf(
            "com.instagram.android" to longArrayOf(60, 90, 75, 30, 20, 60, 42).map { it * minute }.toLongArray(),
            "com.google.android.youtube" to longArrayOf(80, 120, 100, 60, 45, 80, 31).map { it * minute }.toLongArray(),
            "com.android.chrome" to longArrayOf(90, 160, 120, 100, 100, 80, 18).map { it * minute }.toLongArray(),
            "org.thoughtcrime.securesms" to longArrayOf(22, 30, 10, 20, 10, 20, 9).map { it * minute }.toLongArray(),
        )),
        appLabel = names::get,
        timeZone = java.util.TimeZone.getTimeZone("UTC"),
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
