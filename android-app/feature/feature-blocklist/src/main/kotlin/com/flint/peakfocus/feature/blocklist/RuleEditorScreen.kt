package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel

/**
 * Create/edit one [com.flint.peakfocus.core.model.BlockRule]: name, app + website targets,
 * allow-list mode, an optional weekly schedule, and the break difficulty. Validation is the
 * pure [validate]; nothing is persisted until [onSave] hands the finished draft up (the entry
 * screen calls the ViewModel).
 *
 * The schedule's time fields keep their *text* as the source of truth and only materialize
 * minutes at save time — unparsable text becomes an out-of-range sentinel the validator
 * rejects, so a stale-but-valid minute can never sneak into a save.
 *
 * [onDelete] is null for a new rule (nothing to delete yet).
 */
@Composable
internal fun RuleEditorScreen(
    initialDraft: RuleDraft,
    appsState: InstalledAppsUiState,
    onSave: (RuleDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(initialDraft.id) { mutableStateOf(initialDraft) }
    var startText by remember(initialDraft.id) {
        mutableStateOf(formatMinuteOfDay(initialDraft.startMinuteOfDay))
    }
    var endText by remember(initialDraft.id) {
        mutableStateOf(formatMinuteOfDay(initialDraft.endMinuteOfDay))
    }
    var domainText by remember { mutableStateOf("") }
    var domainError by remember { mutableStateOf(false) }
    var attemptedSave by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingApps by remember { mutableStateOf(false) }

    if (pickingApps) {
        // Composed after the entry screen's BackHandler, so this one wins while picking.
        BackHandler { pickingApps = false }
        AppPickerScreen(
            appsState = appsState,
            title = if (draft.allowListMode) "Choose apps to allow" else "Choose apps to block",
            multiSelect = true,
            initiallySelected = draft.apps.map { it.packageName }.toSet(),
            onConfirm = { picked ->
                // Keep rule entries for apps that are no longer installed — the picker can't
                // show them, and confirming must not silently drop them.
                val visible = (appsState as? InstalledAppsUiState.Ready)
                    ?.apps?.map { it.packageName }?.toSet()
                    ?: emptySet()
                val preserved = draft.apps.filter { it.packageName !in visible }.toSet()
                val chosen = picked.map { AppRef(packageName = it.packageName, label = it.label) }
                draft = draft.copy(apps = preserved + chosen)
                pickingApps = false
            },
            onCancel = { pickingApps = false },
            modifier = modifier,
        )
        return
    }

    // Unparsable time text maps to -1, which validate() rejects as INVALID_TIME.
    val candidate = draft.copy(
        startMinuteOfDay = parseMinuteOfDay(startText) ?: -1,
        endMinuteOfDay = parseMinuteOfDay(endText) ?: -1,
    )
    val errors = if (attemptedSave) validate(candidate) else emptyList()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        EditorHeader(
            title = if (initialDraft.id == null) "New block rule" else "Edit block rule",
            onBack = onClose,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Rule name") },
            placeholder = { Text("Social media") },
            singleLine = true,
            isError = attemptedSave && draft.name.isBlank(),
        )
        Spacer(Modifier.height(20.dp))

        SectionLabel(if (draft.allowListMode) "Allowed apps" else "Apps to block")
        Spacer(Modifier.height(4.dp))
        if (draft.apps.isEmpty()) {
            Text(
                text = "No apps picked yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val sortedApps = draft.apps.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { app: AppRef ->
                    app.label ?: app.packageName
                },
            )
            sortedApps.forEach { app ->
                SelectedAppRow(
                    app = app,
                    onRemove = { draft = draft.copy(apps = draft.apps - app) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pickingApps = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Choose apps")
        }
        Spacer(Modifier.height(20.dp))

        LabeledSwitchRow(
            title = "Allow-list mode",
            description = "Flip the rule: block every app on the phone except the ones above.",
            checked = draft.allowListMode,
            onCheckedChange = { draft = draft.copy(allowListMode = it) },
        )
        Spacer(Modifier.height(20.dp))

        SectionLabel("Websites to block")
        Spacer(Modifier.height(4.dp))
        if (draft.allowListMode) {
            Text(
                text = "While allow-list mode is on, website targets are ignored by the " +
                    "blocker. They're kept for when you switch back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = domainText,
                onValueChange = {
                    domainText = it
                    domainError = false
                },
                modifier = Modifier.weight(1f),
                label = { Text("Add a website") },
                placeholder = { Text("youtube.com") },
                singleLine = true,
                isError = domainError,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val normalized = normalizeDomain(domainText)
                if (normalized == null) {
                    domainError = true
                } else {
                    if (normalized !in draft.domains) {
                        draft = draft.copy(domains = draft.domains + normalized)
                    }
                    domainText = ""
                }
            }) {
                Text("Add")
            }
        }
        if (domainError) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Enter a web address like youtube.com.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Subdomains count too — youtube.com also covers m.youtube.com.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        draft.domains.forEach { domain ->
            SelectedDomainRow(
                domain = domain,
                onRemove = { draft = draft.copy(domains = draft.domains - domain) },
            )
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("Schedule")
        Spacer(Modifier.height(4.dp))
        LabeledSwitchRow(
            title = "Only at certain times",
            description = "Off means the rule is active around the clock while enabled.",
            checked = draft.scheduled,
            onCheckedChange = { draft = draft.copy(scheduled = it) },
        )
        if (draft.scheduled) {
            Spacer(Modifier.height(12.dp))
            DayOfWeekRow(
                selected = draft.days,
                onToggle = { day ->
                    draft = draft.copy(
                        days = if (day in draft.days) draft.days - day else draft.days + day,
                    )
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "No days selected = every day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("From") },
                    placeholder = { Text("09:00") },
                    singleLine = true,
                    isError = parseMinuteOfDay(startText) == null,
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Until") },
                    placeholder = { Text("17:00") },
                    singleLine = true,
                    isError = parseMinuteOfDay(endText) == null,
                )
            }
            val start = parseMinuteOfDay(startText)
            val end = parseMinuteOfDay(endText)
            if (start != null && end != null && end != start) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (end < start) {
                        "Overnight window — runs past midnight into the next day."
                    } else {
                        "24-hour time, e.g. 21:30."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        SectionLabel("Break difficulty")
        Spacer(Modifier.height(4.dp))
        BreakLevelSelector(
            selected = draft.breakLevel,
            onSelect = { draft = draft.copy(breakLevel = it) },
        )
        if (draft.breakLevel == BreakLevel.HARDCORE) {
            Text(
                text = "Hardcore means it: no breaks, no early stop. Your weekly Emergency " +
                    "Pass is the only exit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(24.dp))

        ErrorMessages(errors.map { it.message })
        if (errors.isNotEmpty()) Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                attemptedSave = true
                if (validate(candidate).isEmpty()) onSave(candidate)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save rule")
        }
        if (onDelete != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete rule")
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }

    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this rule?") },
            text = { Text("Its blocks stop applying. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun SelectedAppRow(app: AppRef, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label ?: app.packageName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
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
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectedDomainRow(domain: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = domain,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Seven equal-width day pills, ISO order Mon..Sun, matching [IsoDays]. */
@Composable
private fun DayOfWeekRow(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IsoDays.ALL.forEach { day ->
            val isSelected = day in selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .clickable { onToggle(day) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = IsoDays.shortLabel(day),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

// --- Previews (fake data only; no ViewModel) ---

@Preview(showBackground = true)
@Composable
private fun RuleEditorPreview() {
    FlintTheme {
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
            RuleEditorScreen(
                initialDraft = RuleDraft(
                    id = "preview",
                    name = "Social media",
                    apps = setOf(
                        AppRef("com.instagram.android", "Instagram"),
                        AppRef("com.zhiliaoapp.musically", "TikTok"),
                    ),
                    domains = listOf("reddit.com"),
                    scheduled = true,
                    days = IsoDays.WEEKDAYS,
                    startMinuteOfDay = 9 * 60,
                    endMinuteOfDay = 17 * 60,
                    breakLevel = BreakLevel.HARDER,
                ),
                appsState = InstalledAppsUiState.Ready(emptyList()),
                onSave = {},
                onDelete = {},
                onClose = {},
            )
        }
    }
}
