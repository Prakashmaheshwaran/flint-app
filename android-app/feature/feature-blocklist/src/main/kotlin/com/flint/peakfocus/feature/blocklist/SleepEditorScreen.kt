package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintTimeField
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.common.ui.rememberReducedMotion
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel

/**
 * Author the one [SleepConfig] (spec 3.3 — free; blocking only, no bundled audio content).
 * Same shape as the other editors: time fields keep their *text* as the source of truth and
 * only materialize minutes at save time via [validate]-gated parsing; the allowed-apps picker
 * swaps in-place exactly like the rule editor's.
 */
@Composable
internal fun SleepEditorScreen(
    initial: SleepConfig,
    appsState: InstalledAppsUiState,
    onSave: (SleepConfig) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    saveState: EditorSaveState = EditorSaveState(),
) {
    var config by remember { mutableStateOf(initial) }
    var morningHasTier by remember { mutableStateOf(initial.morningEnabled) }
    var bedText by remember { mutableStateOf(formatMinuteOfDay(initial.bedMinute)) }
    var wakeText by remember { mutableStateOf(formatMinuteOfDay(initial.wakeMinute)) }
    var morningText by remember { mutableStateOf(formatMinuteOfDay(initial.morningEndMinute)) }
    var attemptedSave by remember { mutableStateOf(false) }
    var pickingApps by remember { mutableStateOf(false) }
    val haptics = rememberFlintHaptics()
    val reducedMotion = rememberReducedMotion()
    val presentation = sleepPresentation(config)
    val iconByPackage = remember(appsState) {
        (appsState as? InstalledAppsUiState.Ready)
            ?.apps?.associate { it.packageName to it.icon }
            .orEmpty()
    }

    if (pickingApps) {
        // Composed after the entry screen's BackHandler, so this one wins while picking.
        BackHandler { pickingApps = false }
    }

    // Unparsable time text maps to -1, which validate() rejects as INVALID_TIME.
    val candidate = config.copy(
        bedMinute = parseMinuteOfDay(bedText) ?: -1,
        wakeMinute = parseMinuteOfDay(wakeText) ?: -1,
        morningEndMinute = parseMinuteOfDay(morningText) ?: -1,
    )
    val errors = if (attemptedSave) validate(candidate) else emptyList()

    AnimatedContent(
        targetState = pickingApps,
        modifier = modifier,
        transitionSpec = {
            if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
            else editorPushPop(forward = targetState)
        },
        label = "sleepEditorPicker",
    ) { picking ->
        if (picking) {
            AppPickerScreen(
                appsState = appsState,
                title = stringResource(R.string.sleep_allowed),
                eyebrow = stringResource(R.string.sleep_title),
                multiSelect = true,
                initiallySelected = config.allowedApps.map { it.packageName }.toSet(),
                onConfirm = { picked ->
                    // Same preservation contract as the rule editor: entries for apps the
                    // picker can't show (uninstalled) must not be silently dropped.
                    val visible = (appsState as? InstalledAppsUiState.Ready)
                        ?.apps?.map { it.packageName }?.toSet()
                        ?: emptySet()
                    val preserved = config.allowedApps.filter { it.packageName !in visible }.toSet()
                    val chosen = picked.map { AppRef(packageName = it.packageName, label = it.label) }
                    config = config.copy(allowedApps = preserved + chosen)
                    pickingApps = false
                },
                onCancel = { pickingApps = false },
            )
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            val compactTimeSummary = sleepTimeFieldsStack(maxWidth.value, LocalDensity.current.fontScale)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .imePadding()
                    .padding(FlintSpacing.gutter),
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.sleep_back)) }
                Text(
                    stringResource(R.string.sleep_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(FlintSpacing.md))

                FlintCard {
                    LabeledSwitchRow(
                        title = stringResource(R.string.sleep_title),
                        description = stringResource(R.string.sleep_window_description),
                        checked = config.enabled,
                        onCheckedChange = { config = config.copy(enabled = it) },
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SleepSectionLabel(R.string.sleep_nights)
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    SleepDayChoices(
                        selected = config.nights,
                        onToggle = { day ->
                            config = config.copy(
                                nights = if (day in config.nights) {
                                    config.nights - day
                                } else {
                                    config.nights + day
                                },
                            )
                        },
                    )
                    Spacer(Modifier.height(FlintSpacing.xs))
                    Text(
                        text = stringResource(R.string.sleep_nights_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SleepSectionLabel(R.string.sleep_window)
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    BoxWithConstraints {
                        val stacked = sleepTimeFieldsStack(maxWidth.value, LocalDensity.current.fontScale)
                        @Composable fun BedField(modifier: Modifier) {
                        FlintTimeField(
                            label = stringResource(R.string.sleep_bedtime),
                            valueText = bedText,
                            onValueText = { bedText = it },
                            modifier = modifier,
                            fallbackMinutes = SleepConfig.DEFAULT.bedMinute,
                        )
                        }
                        @Composable fun WakeField(modifier: Modifier) {
                        FlintTimeField(
                            label = stringResource(R.string.sleep_wake),
                            valueText = wakeText,
                            onValueText = { wakeText = it },
                            modifier = modifier,
                            fallbackMinutes = SleepConfig.DEFAULT.wakeMinute,
                        )
                        }
                        if (stacked) {
                            Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap)) {
                                BedField(Modifier.fillMaxWidth())
                                WakeField(Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap)) {
                                BedField(Modifier.weight(1f))
                                WakeField(Modifier.weight(1f))
                            }
                        }
                    }
                    // The editor's centerpiece: the night across a 24h day, plus the Morning
                    // Assist extension in the secondary tone while it's enabled.
                    val bed = parseMinuteOfDay(bedText)
                    val wake = parseMinuteOfDay(wakeText)
                    val morningEnd = parseMinuteOfDay(morningText)
                    if (bed != null && wake != null) {
                        val morningShown = config.morningEnabled && morningEnd != null
                        Spacer(Modifier.height(FlintSpacing.md))
                        if (compactTimeSummary) {
                            Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs)) {
                                Text(stringResource(R.string.sleep_time_value, stringResource(R.string.sleep_bed), formatMinuteOfDay(bed)))
                                Text(stringResource(R.string.sleep_time_value, stringResource(R.string.sleep_wake), formatMinuteOfDay(wake)))
                                if (morningShown) Text(stringResource(R.string.sleep_time_value, stringResource(R.string.sleep_morning), formatMinuteOfDay(morningEnd!!)))
                            }
                        } else {
                            FlintDayStrip(
                                startMinute = bed,
                                endMinute = wake,
                                startLabel = stringResource(R.string.sleep_bed),
                                endLabel = stringResource(R.string.sleep_wake),
                                secondaryStartMinute = if (morningShown) wake else null,
                                secondaryEndMinute = if (morningShown) morningEnd else null,
                            )
                        }
                    }
                    Spacer(Modifier.height(FlintSpacing.xs))
                    Text(
                        text = stringResource(R.string.sleep_time_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SleepSectionLabel(R.string.sleep_assist)
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    AssistSelector(
                        selected = config.assist,
                        onSelect = { config = config.withAssist(it) },
                    )
                    Spacer(Modifier.height(FlintSpacing.sm))
                    Text(
                        text = stringResource(R.string.sleep_audio_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SleepSectionLabel(R.string.sleep_tier)
                Text(stringResource(R.string.sleep_tier_value, stringResource(sleepTierText(config.breakLevel))))
                SleepTierSelector(
                    selected = config.breakLevel,
                    onSelect = { config = config.copy(breakLevel = it) },
                    modifier = Modifier.testTag("sleep-window-tier"),
                )
                AnimatedVisibility(visible = config.breakLevel == BreakLevel.HARDCORE ||
                    (config.morningEnabled && config.morningBreakLevel == BreakLevel.HARDCORE)) {
                    Column {
                        Spacer(Modifier.height(FlintSpacing.sm))
                        HardcoreCommitmentDisclosure()
                    }
                }
                Text(
                    stringResource(presentation.offExplanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.sleep_legacy_rights),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(FlintSpacing.lg))
                SleepSectionLabel(R.string.sleep_allowed)
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    Column(if (reducedMotion) Modifier else Modifier.animateContentSize(editorRevealSpec())) {
                        if (config.allowedApps.isEmpty()) {
                            Text(
                                text = stringResource(R.string.sleep_allowed_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val sorted = config.allowedApps.sortedWith(
                                compareBy(String.CASE_INSENSITIVE_ORDER) { app: AppRef ->
                                    app.label ?: app.packageName
                                },
                            )
                            sorted.forEach { app ->
                                TargetAppRow(
                                    app = app,
                                    icon = iconByPackage[app.packageName],
                                    onRemove = {
                                        config =
                                            config.copy(allowedApps = config.allowedApps - app)
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(FlintSpacing.sm))
                        OutlinedButton(
                            onClick = { pickingApps = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.sleep_choose_apps))
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SleepSectionLabel(R.string.sleep_morning)
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    val morningTier = stringResource(R.string.sleep_tier_value, stringResource(sleepTierText(config.morningBreakLevel)))
                    LabeledSwitchRow(
                        title = stringResource(R.string.sleep_morning_toggle),
                        description = if (config.morningEnabled) {
                            "${stringResource(R.string.sleep_morning_note)} $morningTier"
                        } else stringResource(R.string.sleep_morning_note),
                        checked = config.morningEnabled,
                        onCheckedChange = { enabled ->
                            config = config.copy(
                                morningEnabled = enabled,
                                morningBreakLevel = if (enabled && !morningHasTier) config.breakLevel else config.morningBreakLevel,
                            )
                            if (enabled) morningHasTier = true
                        },
                    )
                    AnimatedVisibility(
                        visible = config.morningEnabled,
                        enter = if (reducedMotion) EnterTransition.None else expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                        exit = if (reducedMotion) ExitTransition.None else shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
                    ) {
                        Column {
                            Spacer(Modifier.height(FlintSpacing.sm))
                            Text(morningTier)
                            SleepTierSelector(
                                selected = config.morningBreakLevel,
                                onSelect = { config = config.copy(morningBreakLevel = it) },
                                modifier = Modifier.testTag("sleep-morning-tier"),
                            )
                            FlintTimeField(
                                label = stringResource(R.string.sleep_until),
                                valueText = morningText,
                                onValueText = { morningText = it },
                                modifier = Modifier.fillMaxWidth(),
                                fallbackMinutes = SleepConfig.DEFAULT.morningEndMinute,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                ErrorMessages(errors.map { stringResource(sleepErrorText(it)) } + listOfNotNull(saveState.error))
                if (errors.isNotEmpty()) Spacer(Modifier.height(FlintSpacing.cardGap))

                Button(
                    enabled = !saveState.busy,
                    onClick = {
                        attemptedSave = true
                        if (validate(candidate).isEmpty()) {
                            haptics.confirm()
                            onSave(candidate)
                        } else {
                            haptics.reject()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sleep_save))
                }
                Spacer(Modifier.height(FlintSpacing.sm))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sleep_cancel))
                }
            }
            }
        }
    }
}

/** The two assist levels as radio rows — same idiom as [BreakLevelSelector]. */
@Composable
private fun AssistSelector(
    selected: SleepAssist?,
    onSelect: (SleepAssist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    val reducedMotion = rememberReducedMotion()
    Column(modifier = modifier.fillMaxWidth().selectableGroup()) {
        SleepAssist.entries.forEach { assist ->
            val isSelected = assist == selected
            val container by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                animationSpec = if (reducedMotion) snap() else tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
                label = "assistRowContainer",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(container)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = {
                            haptics.tick()
                            onSelect(assist)
                        },
                    )
                    .padding(horizontal = FlintSpacing.sm, vertical = FlintSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(Modifier.width(FlintSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (assist) {
                            SleepAssist.WIND_DOWN -> stringResource(R.string.sleep_wind_down)
                            SleepAssist.FULL_ASSIST -> stringResource(R.string.sleep_full_assist)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (assist) {
                            SleepAssist.WIND_DOWN -> stringResource(R.string.sleep_wind_down_note)
                            SleepAssist.FULL_ASSIST ->
                                stringResource(R.string.sleep_full_assist_note)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepSectionLabel(@androidx.annotation.StringRes text: Int) {
    SectionLabel(stringResource(text), Modifier.semantics { heading() })
}

/** Tier choice only; no promise about an unmerged pass or a re-derived active occurrence. */
@Composable
private fun SleepTierSelector(selected: BreakLevel, onSelect: (BreakLevel) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().selectableGroup()) {
        BreakLevel.entries.forEach { tier ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = tier == selected, role = Role.RadioButton, onClick = { onSelect(tier) })
                    .padding(FlintSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = tier == selected, onClick = null)
                Spacer(Modifier.width(FlintSpacing.sm))
                Text(stringResource(sleepTierText(tier)))
            }
        }
    }
}

/** Wrap rather than shrinking seven touch targets into the viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepDayChoices(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val names = stringArrayResource(R.array.sleep_weekday_names)
    val shortNames = stringArrayResource(R.array.sleep_weekday_short)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
    ) {
        IsoDays.ALL.forEach { day ->
            val isSelected = day in selected
            Text(
                text = if (isSelected) "✓ ${shortNames[day - 1]}" else shortNames[day - 1],
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .toggleable(value = isSelected, role = Role.Checkbox, onValueChange = { onToggle(day) })
                    .semantics { contentDescription = names[day - 1] }
                    .padding(FlintSpacing.sm),
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
internal fun sleepSummaryText(config: SleepConfig): String {
    val presentation = sleepPresentation(config)
    val names = stringArrayResource(R.array.sleep_weekday_short)
    val nights = presentation.nights?.let { stringResource(it) }
        ?: config.nights.sorted().joinToString(" ") { names[it - 1] }
    val window = stringResource(R.string.sleep_summary, nights, formatMinuteOfDay(config.bedMinute), formatMinuteOfDay(config.wakeMinute))
    return if (config.morningEnabled) stringResource(R.string.sleep_summary_morning, window, formatMinuteOfDay(config.morningEndMinute)) else window
}

@Preview(showBackground = true)
@Composable
private fun SleepEditorPreview() {
    FlintTheme {
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
            SleepEditorScreen(
                initial = SleepConfig.DEFAULT.copy(
                    enabled = true,
                    allowedApps = setOf(AppRef("com.android.deskclock", "Clock")),
                    morningEnabled = true,
                ),
                appsState = InstalledAppsUiState.Ready(emptyList()),
                onSave = {},
                onClose = {},
            )
        }
    }
}
