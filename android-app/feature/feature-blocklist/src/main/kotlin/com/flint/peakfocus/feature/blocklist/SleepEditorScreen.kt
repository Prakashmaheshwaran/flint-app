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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintTimeField
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.AppRef

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
) {
    var config by remember { mutableStateOf(initial) }
    var bedText by remember { mutableStateOf(formatMinuteOfDay(initial.bedMinute)) }
    var wakeText by remember { mutableStateOf(formatMinuteOfDay(initial.wakeMinute)) }
    var morningText by remember { mutableStateOf(formatMinuteOfDay(initial.morningEndMinute)) }
    var attemptedSave by remember { mutableStateOf(false) }
    var pickingApps by remember { mutableStateOf(false) }
    val haptics = rememberFlintHaptics()
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
        transitionSpec = { editorPushPop(forward = targetState) },
        label = "sleepEditorPicker",
    ) { picking ->
        if (picking) {
            AppPickerScreen(
                appsState = appsState,
                title = "Allowed overnight",
                eyebrow = "Sleep mode",
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(FlintSpacing.gutter),
            ) {
                EditorHeader(title = "Sleep mode", eyebrow = "Sleep mode", onBack = onClose)
                Spacer(Modifier.height(FlintSpacing.md))

                FlintCard {
                    LabeledSwitchRow(
                        title = "Sleep mode",
                        description = "During the window, everything except your allowed apps " +
                            "is blocked.",
                        checked = config.enabled,
                        onCheckedChange = { config = config.copy(enabled = it) },
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Nights")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    DayOfWeekRow(
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
                        text = "Each night starts on the selected evening. " +
                            "No nights selected = every night.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Window")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    Row {
                        FlintTimeField(
                            label = "Bedtime",
                            valueText = bedText,
                            onValueText = { bedText = it },
                            modifier = Modifier.weight(1f),
                            fallbackMinutes = SleepConfig.DEFAULT.bedMinute,
                        )
                        Spacer(Modifier.width(FlintSpacing.cardGap))
                        FlintTimeField(
                            label = "Wake",
                            valueText = wakeText,
                            onValueText = { wakeText = it },
                            modifier = Modifier.weight(1f),
                            fallbackMinutes = SleepConfig.DEFAULT.wakeMinute,
                        )
                    }
                    // The editor's centerpiece: the night across a 24h day, plus the Morning
                    // Assist extension in the secondary tone while it's enabled.
                    val bed = parseMinuteOfDay(bedText)
                    val wake = parseMinuteOfDay(wakeText)
                    val morningEnd = parseMinuteOfDay(morningText)
                    if (bed != null && wake != null) {
                        val morningShown = config.morningEnabled && morningEnd != null
                        Spacer(Modifier.height(FlintSpacing.md))
                        FlintDayStrip(
                            startMinute = bed,
                            endMinute = wake,
                            startLabel = "Bed",
                            endLabel = "Wake",
                            secondaryStartMinute = if (morningShown) wake else null,
                            secondaryEndMinute = if (morningShown) morningEnd else null,
                        )
                    }
                    Spacer(Modifier.height(FlintSpacing.xs))
                    Text(
                        text = "24-hour time. Windows past midnight just work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Assist level")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    AssistSelector(
                        selected = config.assist,
                        onSelect = { config = config.copy(assist = it) },
                    )
                    Spacer(Modifier.height(FlintSpacing.sm))
                    Text(
                        text = "Blocking only — Flint bundles no soundscapes or meditations, " +
                            "by design.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Allowed overnight")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    Column(Modifier.animateContentSize(editorRevealSpec())) {
                        if (config.allowedApps.isEmpty()) {
                            Text(
                                text = "Nothing picked — everything blocks during the window. " +
                                    "Consider allowing your clock or phone app.",
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
                            Text("Choose apps")
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Morning assist")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    LabeledSwitchRow(
                        title = "Keep blocking after waking",
                        description = "The same allow-list holds from wake time until the " +
                            "morning end.",
                        checked = config.morningEnabled,
                        onCheckedChange = { config = config.copy(morningEnabled = it) },
                    )
                    AnimatedVisibility(
                        visible = config.morningEnabled,
                        enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                        exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
                    ) {
                        Column {
                            Spacer(Modifier.height(FlintSpacing.sm))
                            FlintTimeField(
                                label = "Until",
                                valueText = morningText,
                                onValueText = { morningText = it },
                                modifier = Modifier.fillMaxWidth(),
                                fallbackMinutes = SleepConfig.DEFAULT.morningEndMinute,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                ErrorMessages(errors.map { it.message })
                if (errors.isNotEmpty()) Spacer(Modifier.height(FlintSpacing.cardGap))

                Button(
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
                    Text("Save sleep mode")
                }
                Spacer(Modifier.height(FlintSpacing.sm))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

/** The two assist levels as radio rows — same idiom as [BreakLevelSelector]. */
@Composable
private fun AssistSelector(
    selected: SleepAssist,
    onSelect: (SleepAssist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    Column(modifier = modifier.fillMaxWidth().selectableGroup()) {
        SleepAssist.entries.forEach { assist ->
            val isSelected = assist == selected
            val container by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
                label = "assistRowContainer",
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
                            SleepAssist.WIND_DOWN -> "Wind down"
                            SleepAssist.FULL_ASSIST -> "Full assist"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (assist) {
                            SleepAssist.WIND_DOWN -> "Easy to break when you really need to."
                            SleepAssist.FULL_ASSIST ->
                                "Everything but your allowed apps locks; breaks take friction."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
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
