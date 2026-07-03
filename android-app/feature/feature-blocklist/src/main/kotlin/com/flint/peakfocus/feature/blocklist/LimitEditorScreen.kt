package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val pkg = chosenPackage

    if (pkg == null) {
        AppPickerScreen(
            appsState = appsState,
            title = "Choose an app to limit",
            multiSelect = false,
            initiallySelected = emptySet(),
            onConfirm = { picked -> picked.firstOrNull()?.let { chosenPackage = it.packageName } },
            onCancel = onClose,
            modifier = modifier,
        )
        return
    }

    val row = (limitsState as? LimitsUiState.Ready)?.rows?.firstOrNull { it.packageName == pkg }
    val app = (appsState as? InstalledAppsUiState.Ready)?.apps?.firstOrNull { it.packageName == pkg }

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
        modifier = modifier,
    )
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
            .padding(20.dp),
    ) {
        EditorHeader(title = "App limits", onBack = onClose)
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconThumb(icon = appIcon, label = displayName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
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
        Spacer(Modifier.height(20.dp))

        LabeledSwitchRow(
            title = "Daily time limit",
            description = "Block it after this much use in a day.",
            checked = draft.timeEnabled,
            onCheckedChange = { draft = draft.copy(timeEnabled = it) },
        )
        if (draft.timeEnabled) {
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 120).forEach { minutes ->
                    OutlinedButton(
                        onClick = { draft = draft.copy(minutesText = minutes.toString()) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(formatDailyMinutes(minutes))
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        LabeledSwitchRow(
            title = "Daily open limit",
            description = "Block it after opening it this many times in a day.",
            checked = draft.openEnabled,
            onCheckedChange = { draft = draft.copy(openEnabled = it) },
        )
        if (draft.openEnabled) {
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(12.dp))
            SectionLabel("Open-limit break difficulty")
            Spacer(Modifier.height(4.dp))
            BreakLevelSelector(
                selected = draft.openBreakLevel,
                onSelect = { draft = draft.copy(openBreakLevel = it) },
            )
        }
        Spacer(Modifier.height(24.dp))

        ErrorMessages(errors.map { it.message })
        if (errors.isNotEmpty()) Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                attemptedSave = true
                val result = draft.resolve()
                if (result is LimitFormResult.Valid) {
                    onSave(result.timeLimit, result.openLimit)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save limits")
        }
        if (onRemove != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                Text("Remove limits")
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}
