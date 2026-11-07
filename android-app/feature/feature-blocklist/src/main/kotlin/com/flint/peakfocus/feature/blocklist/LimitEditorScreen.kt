package com.flint.peakfocus.feature.blocklist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.BoneButton
import com.flint.peakfocus.core.common.ui.FlintAppTile
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintCircleButton
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.flintSwitchColors
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
    saveState: EditorSaveState = EditorSaveState(),
) {
    var chosenPackage by remember(packageName) { mutableStateOf(packageName) }
    val initialLimits = rememberEditorSnapshot(packageName, limitsState as? LimitsUiState.Ready)
    if (initialLimits == null) {
        StorageEditorUnavailable(onClose, loading = limitsState is LimitsUiState.Loading)
        return
    }

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
            val row = initialLimits.rows.firstOrNull { it.packageName == pkg }
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
                saveState = saveState,
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
    saveState: EditorSaveState = EditorSaveState(),
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
    val attemptSave: () -> Unit = {
        attemptedSave = true
        val result = draft.resolve()
        if (result is LimitFormResult.Valid) {
            haptics.confirm()
            onSave(result.timeLimit, result.openLimit)
        } else {
            haptics.reject()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose, enabled = !saveState.busy) {
                Text("Cancel", color = FlintPalette.Mist)
            }
            Text(
                "APP LIMIT",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                color = FlintPalette.Mist,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            BoneButton(
                text = "Save",
                onClick = attemptSave,
                compact = true,
                enabled = !saveState.busy,
            )
        }
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(FlintPalette.Card)
                .border(1.dp, FlintPalette.Hairline, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)),
                )
            } else {
                FlintAppTile(packageName = packageName, label = displayName, size = 44.dp, cornerRadius = 13.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(displayName, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp))
                Text(
                    listOfNotNull(
                        draft.timeEnabled.takeIf { it }?.let { "time limit on" },
                        draft.openEnabled.takeIf { it }?.let { "open limit on" },
                    ).ifEmpty { listOf("no limits yet") }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = FlintPalette.Mist,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        LimitTypeCard(
            title = "Time limit",
            description = "Rest it after this much use in a day.",
            checked = draft.timeEnabled,
            onCheckedChange = { draft = draft.copy(timeEnabled = it) },
        )
        Spacer(Modifier.height(8.dp))
        LimitTypeCard(
            title = "Open limit",
            description = "5-minute access credits, spent one open at a time.",
            checked = draft.openEnabled,
            onCheckedChange = { draft = draft.copy(openEnabled = it) },
        )

        if (draft.timeEnabled || draft.openEnabled) {
            val value = if (draft.timeEnabled) draft.minutesText else draft.opensText
            val unit = if (draft.timeEnabled) "min / day" else "credits / day"
            Box(Modifier.fillMaxWidth().padding(top = 22.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = FlintPalette.Bone.copy(alpha = 0.1f),
                            radius = size.minDimension / 2f - 6.dp.toPx(),
                            style = Stroke(width = 12.dp.toPx()),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            (value.toIntOrNull() ?: 0).let { if (draft.timeEnabled) "${it}m" else "$it" },
                            style = MaterialTheme.typography.displaySmall.merge(FlintNumerals),
                        )
                        Text(unit, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = FlintPalette.Mist)
                    }
                }
            }

            // One allowance control per enabled limit: both limits are independent, so the
            // credit count must stay editable when the time limit is on too.
            if (draft.timeEnabled) {
                AllowanceStepper(
                    label = "Daily allowance · minutes",
                    text = draft.minutesText,
                    onText = { draft = draft.copy(minutesText = it) },
                    onMinus = {
                        haptics.tick()
                        draft = draft.copy(minutesText = ((draft.minutesText.toIntOrNull() ?: 30) - 5).coerceAtLeast(5).toString())
                    },
                    onPlus = {
                        haptics.tick()
                        draft = draft.copy(minutesText = ((draft.minutesText.toIntOrNull() ?: 30) + 5).coerceAtMost(240).toString())
                    },
                )
            }
            if (draft.openEnabled) {
                AllowanceStepper(
                    label = "Daily allowance · credits",
                    text = draft.opensText,
                    onText = { draft = draft.copy(opensText = it) },
                    onMinus = {
                        haptics.tick()
                        draft = draft.copy(opensText = ((draft.opensText.toIntOrNull() ?: 5) - 1).coerceAtLeast(1).toString())
                    },
                    onPlus = {
                        haptics.tick()
                        draft = draft.copy(opensText = ((draft.opensText.toIntOrNull() ?: 5) + 1).coerceAtMost(20).toString())
                    },
                )
            }
        }

        if (draft.timeEnabled) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("Time-limit difficulty")
            Spacer(Modifier.height(FlintSpacing.sm))
            BreakLevelSelector(selected = draft.timeBreakLevel, onSelect = { draft = draft.copy(timeBreakLevel = it) })
        }
        if (draft.openEnabled) {
            Spacer(Modifier.height(18.dp))
            SectionLabel("Open-limit difficulty")
            Spacer(Modifier.height(FlintSpacing.sm))
            BreakLevelSelector(selected = draft.openBreakLevel, onSelect = { draft = draft.copy(openBreakLevel = it) })
        }

        Spacer(Modifier.height(FlintSpacing.lg))
        ErrorMessages(errors.map { it.message } + listOfNotNull(saveState.error))
        if (errors.isNotEmpty()) Spacer(Modifier.height(FlintSpacing.cardGap))

        com.flint.peakfocus.core.common.ui.EmberButton(
            text = "Save limits",
            onClick = attemptSave,
            enabled = !saveState.busy,
        )
        if (onRemove != null) {
            Spacer(Modifier.height(FlintSpacing.sm))
            TextButton(
                onClick = onRemove,
                enabled = !saveState.busy,
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
        Spacer(Modifier.height(FlintSpacing.lg))
    }
}

/** One limit type as a v3 card with a switch — both stay independent and free. */
@Composable
private fun LimitTypeCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberFlintHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(FlintPalette.Card)
            .border(1.dp, if (checked) FlintPalette.Ember.copy(alpha = 0.4f) else FlintPalette.Hairline, RoundedCornerShape(18.dp))
            .toggleable(value = checked, role = Role.Switch) { value ->
                if (value) haptics.toggleOn() else haptics.toggleOff()
                onCheckedChange(value)
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp))
            Text(description, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = FlintPalette.Mist)
        }
        Switch(checked = checked, onCheckedChange = null, colors = flintSwitchColors())
    }
}

private fun minutesTextValue(text: String): Int = text.trim().toIntOrNull()?.coerceIn(1, 1440) ?: 30

/** A v3 allowance card: editable number plus −/+ steppers. */
@Composable
private fun AllowanceStepper(
    label: String,
    text: String,
    onText: (String) -> Unit,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(FlintPalette.Card)
            .border(1.dp, FlintPalette.Hairline, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = FlintPalette.Mist)
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { onText(it.filter { c -> c.isDigit() }) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp).merge(FlintNumerals),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(FlintPalette.Ember),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .width(100.dp)
                    .semantics { contentDescription = label },
            )
        }
        FlintCircleButton(onClick = onMinus, contentDescription = "Less", size = 44.dp) {
            Icon(FlintGlyphs.minus, contentDescription = null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        FlintCircleButton(onClick = onPlus, contentDescription = "More", size = 44.dp) {
            Icon(FlintGlyphs.plus, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}
