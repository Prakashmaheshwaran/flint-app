package com.flint.peakfocus.feature.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintText
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.FlintStreakCinematicStage
import com.flint.peakfocus.core.common.ui.FlintStreakEmber
import com.flint.peakfocus.core.common.ui.FocusStreakSheetCopy
import com.flint.peakfocus.core.common.ui.StreakCoolDeep
import com.flint.peakfocus.core.common.ui.StreakCoolTop
import com.flint.peakfocus.core.common.ui.StreakEmberMood
import com.flint.peakfocus.core.common.ui.rememberReducedMotion
import com.flint.peakfocus.core.history.FocusDay
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.FocusHistoryText
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class FocusPeriodTotals(val focused: String, val completed: Int, val endedEarly: Int)

internal fun focusPeriodTotals(days: List<FocusDay>): FocusPeriodTotals = FocusPeriodTotals(
    if (days.none { it.focusedMs != null }) "No data" else FocusHistoryText.duration(days.sumOf { it.focusedMs ?: 0 }),
    days.sumOf { it.completed }, days.sumOf { it.endedEarly },
)

/**
 * Single description for the Focus Streak hero group. Only what the group itself shows:
 * the streak and its motivation, read as two sentences. The card title is a heading above
 * the group, and the status/best lines below are their own nodes, so including them here
 * duplicated them.
 */
internal fun streakCardDescription(streakCaption: String, mood: StreakEmberMood, streak: Int): String =
    "$streakCaption. ${FocusStreakSheetCopy.motivation(mood, streak)}"

/** Independent of Usage Access: these are app-owned session events, never screen usage. */
@Composable
internal fun FocusSessionsSection() {
    if (LocalInspectionMode.current) {
        FlintSectionLabel("Focus sessions")
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    val history by remember(context) { FocusHistoryBridge.summaries(context) }
        .collectAsStateWithLifecycle(initialValue = null)
    var week by rememberSaveable { mutableStateOf(true) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val summary = history

    FocusStreakCard(summary)
    Spacer(Modifier.height(FlintSpacing.lg))

    FlintCard {
        FlintSectionLabel("Focus sessions")
        Text(
            FocusHistoryText.DEFINITION,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (summary == null || summary.availability == HistoryAvailability.UNAVAILABLE) {
            Text(FocusHistoryText.today(summary, context.resources), modifier = Modifier.padding(top = 12.dp))
            if (summary?.availability == HistoryAvailability.UNAVAILABLE) {
                Button(
                    enabled = !retrying,
                    onClick = {
                        retrying = true
                        scope.launch {
                            FocusHistoryBridge.retry(context)
                            retrying = false
                        }
                    },
                ) { Text(if (retrying) "Retrying…" else "Retry focus history") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !week, onClick = { week = false }, label = { Text("Day") })
                FilterChip(selected = week, onClick = { week = true }, label = { Text("Week") })
            }
            if (summary.availability == HistoryAvailability.EMPTY) {
                Text("No focus sessions yet", modifier = Modifier.padding(vertical = 8.dp))
            }
            val selected = summary.days.firstOrNull { it.date.toString() == selectedDate }
                ?: summary.days.lastOrNull()
            val shown = if (week) summary.days else listOfNotNull(selected)
            val totals = focusPeriodTotals(shown)
            Text(totals.focused, style = MaterialTheme.typography.headlineMedium)
            Text(
                "${totals.completed} completed · ${totals.endedEarly} ended early",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (week) {
                    FocusHistoryText.weekLabel(summary.days)
                } else {
                    selected?.date?.format(DateTimeFormatter.ofPattern("EEE, MMM d")) ?: "Today"
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            val maximum = maxOf(1L, summary.days.maxOfOrNull { it.focusedMs ?: 0 } ?: 0)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                summary.days.forEach { day ->
                    val label = day.date.format(DateTimeFormatter.ofPattern("EEEEE"))
                    val value = day.focusedMs?.let(FocusHistoryText::duration) ?: "No data"
                    Column(
                        Modifier
                            .weight(1f)
                            .selectable(
                                selected = day == selected && !week,
                                role = Role.Tab,
                                onClick = {
                                    selectedDate = day.date.toString()
                                    week = false
                                },
                            )
                            .semantics {
                                contentDescription =
                                    "${day.date}, $value focused, ${day.completed} completed, ${day.endedEarly} ended early"
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (day.focusedMs == null) "—" else value,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                        Box(Modifier.height(72.dp), contentAlignment = Alignment.BottomCenter) {
                            day.focusedMs?.let { ms ->
                                Box(
                                    Modifier
                                        .width(16.dp)
                                        .height(if (ms == 0L) 2.dp else maxOf(3f, 68f * ms / maximum).dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.shapes.small,
                                        ),
                                )
                            }
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            FocusHistoryText.note(summary, shown, LocalContext.current.resources)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    Spacer(Modifier.height(FlintSpacing.cardGap))
    FlintSectionLabel("Screen usage")
    Spacer(Modifier.height(FlintSpacing.sm))
}

@Composable
private fun FocusStreakCard(summary: FocusSummary?) {
    when (val ui = focusStreakUi(summary)) {
        FocusStreakUi.Hidden -> Unit
        FocusStreakUi.Empty -> FlintCard(modifier = Modifier.testTag("history.streak")) {
            FlintSectionLabel(FocusStreakCopy.TITLE, modifier = Modifier.semantics { heading() })
            Text(
                FocusStreakCopy.EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is FocusStreakUi.Visible -> FocusStreakVisibleCard(ui)
    }
}

@Composable
private fun FocusStreakVisibleCard(ui: FocusStreakUi.Visible) {
    val reducedMotion = rememberReducedMotion()
    var previousStreak by remember { mutableStateOf(ui.streak) }
    var milestoneFlash by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ui.streak) {
        val grew = ui.streak > previousStreak
        val milestone = grew && (ui.streak == 7 || ui.streak == 14 || ui.streak == 30 || ui.streak % 50 == 0)
        previousStreak = ui.streak
        if (milestone && !reducedMotion) {
            milestoneFlash = 1f
            delay(900)
            milestoneFlash = 0f
        }
    }
    val numberColor = when (ui.emberMood) {
        StreakEmberMood.Active -> FlintPalette.Ember
        StreakEmberMood.Warning -> StreakCoolDeep
        StreakEmberMood.Dormant -> FlintPalette.Mist.copy(alpha = 0.72f)
    }
    // Hero number — Opal sheet gradient: light top melting into the mood color.
    val numberBrush = when (ui.emberMood) {
        StreakEmberMood.Active -> Brush.verticalGradient(
            listOf(Color(0xFFFFE9B0), FlintPalette.Ember),
        )
        StreakEmberMood.Warning -> Brush.verticalGradient(
            listOf(StreakCoolTop, StreakCoolDeep),
        )
        StreakEmberMood.Dormant -> null
    }
    val statusColor = when (ui.emberMood) {
        StreakEmberMood.Active -> FlintPalette.Ember
        StreakEmberMood.Warning -> StreakCoolDeep
        StreakEmberMood.Dormant -> FlintPalette.Mist
    }
    // Bold sans hero number — Opal sheet uses SF Pro Bold; Hanken Semibold is Flint's sans weight.
    val streakNumberStyle = TextStyle(
        fontFamily = FlintText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 72.sp,
        lineHeight = 76.sp,
        letterSpacing = (-1.6).sp,
    ).merge(FlintNumerals)

    FlintCard(modifier = Modifier.testTag("history.streak")) {
        FlintSectionLabel(FocusStreakCopy.TITLE, modifier = Modifier.semantics { heading() })
        // Streak sheet (Opal in-app composition): flame → number → DAY STREAK → subtitle.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlintSpacing.sm)
                // Minimum, not fixed: the hero text grows with the system font scale, so a
                // hard 420dp box clipped the number/status at Large and accessibility sizes.
                .heightIn(min = 420.dp)
                .clip(RoundedCornerShape(32.dp))
                .semantics(mergeDescendants = true) {
                    // The card is announced once, as the streak itself. The title above is its
                    // own heading and status/best below are their own nodes, so repeating them
                    // here only produced duplicate announcements.
                    contentDescription = streakCardDescription(ui.streakCaption, ui.emberMood, ui.streak)
                },
            contentAlignment = Alignment.Center,
        ) {
            FlintStreakCinematicStage(
                mood = ui.emberMood,
                modifier = Modifier.matchParentSize(),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 28.dp)
                    // Visual-only: the description above covers number, label and motivation,
                    // so the decorative flame and the split text can never be re-announced.
                    .clearAndSetSemantics { },
            ) {
                FlintStreakEmber(
                    mood = ui.emberMood,
                    milestoneFlash = milestoneFlash,
                    size = 160.dp,
                )
                AnimatedContent(
                    targetState = ui.streak,
                    transitionSpec = {
                        val enter = if (reducedMotion) {
                            fadeIn(tween(FlintMotion.DurationShort))
                        } else if (targetState > initialState) {
                            slideInVertically(tween(FlintMotion.DurationMedium)) { it / 5 } +
                                fadeIn(tween(FlintMotion.DurationMedium))
                        } else {
                            slideInVertically(tween(FlintMotion.DurationMedium)) { -it / 5 } +
                                fadeIn(tween(FlintMotion.DurationMedium))
                        }
                        val exit = if (reducedMotion) {
                            fadeOut(tween(FlintMotion.DurationShort))
                        } else if (targetState > initialState) {
                            slideOutVertically(tween(FlintMotion.DurationMedium)) { -it / 5 } +
                                fadeOut(tween(FlintMotion.DurationShort))
                        } else {
                            slideOutVertically(tween(FlintMotion.DurationMedium)) { it / 5 } +
                                fadeOut(tween(FlintMotion.DurationShort))
                        }
                        enter togetherWith exit
                    },
                    label = "streak-number",
                ) { value ->
                    Text(
                        value.toString(),
                        style = if (numberBrush == null) {
                            streakNumberStyle
                        } else {
                            streakNumberStyle.copy(brush = numberBrush)
                        },
                        color = if (numberBrush == null) numberColor else Color.Unspecified,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Text(
                    FocusStreakSheetCopy.DAY_STREAK.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = numberColor.copy(alpha = 0.95f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    FocusStreakSheetCopy.motivation(ui.emberMood, ui.streak),
                    style = MaterialTheme.typography.bodySmall,
                    color = FlintPalette.Mist.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp, start = 12.dp, end = 12.dp),
                )
            }
        }

        ui.statusLine?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelLarge,
                color = statusColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FlintSpacing.md),
            )
        }
        ui.bestCaption?.let { best ->
            Text(
                best,
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FlintSpacing.sm)
                    .semantics { contentDescription = best },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlintSpacing.md)
                .semantics { contentDescription = FocusStreakCopy.WEEK_STRIP },
            horizontalArrangement = Arrangement.Center,
        ) {
            ui.days.forEach { day ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 5.dp)
                        .size(if (day.isToday) 9.dp else 7.dp)
                        .background(
                            color = when {
                                day.kind == FocusStreakDayKind.COMPLETED ->
                                    FlintPalette.Ember.copy(alpha = if (day.isToday) 0.95f else 0.7f)
                                day.isToday -> FlintPalette.Mist.copy(alpha = 0.55f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                            },
                            shape = CircleShape,
                        )
                        .semantics { contentDescription = day.contentDescription },
                )
            }
        }
        Text(
            ui.weekCaption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlintSpacing.xs),
        )
        Text(
            ui.footnote,
            style = MaterialTheme.typography.bodySmall,
            color = FlintPalette.Mist,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlintSpacing.sm),
        )
    }
}
