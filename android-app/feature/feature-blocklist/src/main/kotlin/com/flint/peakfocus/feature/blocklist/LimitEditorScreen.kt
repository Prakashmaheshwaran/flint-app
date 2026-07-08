package com.flint.peakfocus.feature.blocklist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.TimeLimit

/**
 * Author one app's daily limits: a Time Limit (minutes of use) and an Open Limit (launches),
 * spec 2.1/2.2 — both free in Flint, Open Limits on Android being a deliberate
 * beyond-Opal win. With a null [packageName] it first runs the single-select app picker.
 *
 * The Open Limit carries its own break difficulty ([OpenLimit.breakLevel]); the Time Limit
 * model has no such field yet, so no selector is shown for it — the form doesn't pretend.
 */
@Composable
internal fun LimitEditorFlow(
    packageName: String?,
    appsState: InstalledAppsUiState,
    limitsState: LimitsUiState,
    defaultBreakLevel: BreakLevel,
    onSaveLimits: (String, TimeLimit?, OpenLimit?) -> Unit,
    onClearLimits: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chosenPackage by remember(packageName) { mutableStateOf(packageName) }

    AnimatedContent(
        targetState = chosenPackage,
        modifier = modifier,
        transitionSpec = { editorPushPop(forward = targetState != null) },
        label = "limitEditorFlow",
    ) { pkg ->
        if (pkg == null) {
            AppPickerScreen(
                appsState = appsState,
                title = "Choose an app to limit",
                eyebrow = "App limits",
                multiSelect = false,
                // Marks the currently assigned app when a future entry point re-opens the
                // picker with one; today the picker only shows while nothing is assigned.
                initiallySelected = setOfNotNull(chosenPackage),
                onConfirm = { picked ->
                    picked.firstOrNull()?.let { chosenPackage = it.packageName }
                },
                onCancel = onClose,
            )
        } else {
            val row = (limitsState as? LimitsUiState.Ready)
                ?.rows?.firstOrNull { it.packageName == pkg }
            val app = (appsState as? InstalledAppsUiState.Ready)
                ?.apps?.firstOrNull { it.packageName == pkg }

            LimitEditorContent(
                packageName = pkg,
                appLabel = app?.label ?: row?.label,
                appIcon = app?.icon,
                existingTime = row?.timeLimit,
                existingOpen = row?.openLimit,
                defaultBreakLevel = defaultBreakLevel,
                onSave = { time, open -> onSaveLimits(pkg, time, open) },
                onRemove = if (row?.timeLimit != null || row?.openLimit != null) {
                    { onClearLimits(pkg) }
                } else {
                    null
                },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun LimitEditorContent(
    packageName: String,
    appLabel: String?,
    appIcon: ImageBitmap?,
    existingTime: TimeLimit?,
    existingOpen: OpenLimit?,
    defaultBreakLevel: BreakLevel,
    onSave: (TimeLimit?, OpenLimit?) -> Unit,
    onRemove: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(packageName) {
        mutableStateOf(limitDraftOf(packageName, existingTime, existingOpen, defaultBreakLevel))
    }
    var attemptedSave by remember(packageName) { mutableStateOf(false) }
    val haptics = rememberFlintHaptics()

    val errors = if (attemptedSave) {
        (draft.resolve() as? LimitFormResult.Invalid)?.errors.orEmpty()
    } else {
        emptyList()
    }
    val displayName = appLabel?.takeIf { it.isNotBlank() } ?: packageName

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(FlintSpacing.gutter),
    ) {
        EditorHeader(title = "App limits", eyebrow = "App limits", onBack = onClose)
        Spacer(Modifier.height(FlintSpacing.md))

        FlintCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconThumb(icon = appIcon, label = displayName)
                Spacer(Modifier.width(FlintSpacing.cardGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(FlintSpacing.lg))

        SectionLabel("Time limit")
        Spacer(Modifier.height(FlintSpacing.sm))
        FlintCard {
            LabeledSwitchRow(
                title = "Daily time limit",
                description = "Block it after this much use in a day.",
                checked = draft.timeEnabled,
                onCheckedChange = { draft = draft.copy(timeEnabled = it) },
            )
            AnimatedVisibility(
                visible = draft.timeEnabled,
                enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
            ) {
                Column {
                    Spacer(Modifier.height(FlintSpacing.sm))
                    OutlinedTextField(
                        value = draft.minutesText,
                        onValueChange = { draft = draft.copy(minutesText = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Minutes per day") },
                        placeholder = { Text("45") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = errors.contains(LimitFormError.BAD_MINUTES),
                    )
                    Spacer(Modifier.height(FlintSpacing.sm))
                    // Stateful shortcut pills in the day-pill idiom: the one matching the
                    // typed minutes lights up; typing a custom value deselects them all.
                    Row(horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
                        listOf(15, 30, 60, 120).forEach { minutes ->
                            val isSelected = draft.minutesText.trim().toIntOrNull() == minutes
                            val container by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                animationSpec = tween(
                                    FlintMotion.DurationShort,
                                    easing = FlintMotion.EasingStandard,
                                ),
                                label = "quickMinutesContainer",
                            )
                            val labelColor by animateColorAsState(
                                targetValue = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                animationSpec = tween(
                                    FlintMotion.DurationShort,
                                    easing = FlintMotion.EasingStandard,
                                ),
                                label = "quickMinutesLabel",
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    // 48dp touch minimum, like the schedule day pills.
                                    .heightIn(min = 48.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(container)
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        onClick = {
                                            haptics.toggleOn()
                                            draft = draft.copy(minutesText = minutes.toString())
                                        },
                                    )
                                    .padding(vertical = FlintSpacing.sm),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = formatDailyMinutes(minutes),
                                    style = MaterialTheme.typography.labelSmall
                                        .merge(FlintNumerals),
                                    color = labelColor,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(FlintSpacing.lg))

        SectionLabel("Open limit")
        Spacer(Modifier.height(FlintSpacing.sm))
        FlintCard {
            LabeledSwitchRow(
                title = "Daily open limit",
                description = "Block it after opening it this many times in a day.",
                checked = draft.openEnabled,
                onCheckedChange = { draft = draft.copy(openEnabled = it) },
            )
            AnimatedVisibility(
                visible = draft.openEnabled,
                enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
            ) {
                Column {
                    Spacer(Modifier.height(FlintSpacing.sm))
                    OutlinedTextField(
                        value = draft.opensText,
                        onValueChange = { draft = draft.copy(opensText = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Opens per day") },
                        placeholder = { Text("5") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = errors.contains(LimitFormError.BAD_OPENS),
                    )
                    Spacer(Modifier.height(FlintSpacing.cardGap))
                    SectionLabel("Open-limit break difficulty")
                    Spacer(Modifier.height(FlintSpacing.xs))
                    BreakLevelSelector(
                        selected = draft.openBreakLevel,
                        onSelect = { draft = draft.copy(openBreakLevel = it) },
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
                val result = draft.resolve()
                if (result is LimitFormResult.Valid) {
                    haptics.confirm()
                    onSave(result.timeLimit, result.openLimit)
                } else {
                    haptics.reject()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save limits")
        }
        if (onRemove != null) {
            Spacer(Modifier.height(FlintSpacing.sm))
            TextButton(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Remove limits")
            }
        }
        Spacer(Modifier.height(FlintSpacing.sm))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}
