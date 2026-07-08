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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintTimeField
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
@OptIn(ExperimentalFoundationApi::class) // bringIntoViewRequester on older foundation lines.
@Composable
internal fun RuleEditorScreen(
    initialDraft: RuleDraft,
    appsState: InstalledAppsUiState,
    onSave: (RuleDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    groups: List<AppGroup> = emptyList(),
    onSaveGroup: ((name: String, apps: Set<AppRef>, domains: List<String>) -> Unit)? = null,
    onDeleteGroup: ((groupId: String) -> Unit)? = null,
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
    var namingGroup by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var deletingGroup by remember { mutableStateOf<AppGroup?>(null) }
    // Which group chip just applied, and how many new targets it brought (transient label).
    var appliedGroup by remember { mutableStateOf<Pair<String, Int>?>(null) }

    val haptics = rememberFlintHaptics()
    val scope = rememberCoroutineScope()
    // One requester per validation neighborhood, so a failed save can scroll to the culprit.
    val nameRequester = remember { BringIntoViewRequester() }
    val targetsRequester = remember { BringIntoViewRequester() }
    val scheduleRequester = remember { BringIntoViewRequester() }
    val iconByPackage = remember(appsState) {
        (appsState as? InstalledAppsUiState.Ready)
            ?.apps?.associate { it.packageName to it.icon }
            .orEmpty()
    }

    LaunchedEffect(appliedGroup) {
        if (appliedGroup != null) {
            delay(2L * FlintMotion.DurationLong) // Long enough to read, short enough to revert.
            appliedGroup = null
        }
    }

    if (pickingApps) {
        // Composed after the entry screen's BackHandler, so this one wins while picking.
        BackHandler { pickingApps = false }
    }

    // Unparsable time text maps to -1, which validate() rejects as INVALID_TIME.
    val candidate = draft.copy(
        startMinuteOfDay = parseMinuteOfDay(startText) ?: -1,
        endMinuteOfDay = parseMinuteOfDay(endText) ?: -1,
    )
    val errors = if (attemptedSave) validate(candidate) else emptyList()

    AnimatedContent(
        targetState = pickingApps,
        modifier = modifier,
        transitionSpec = { editorPushPop(forward = targetState) },
        label = "ruleEditorPicker",
    ) { picking ->
        if (picking) {
            AppPickerScreen(
                appsState = appsState,
                title = if (draft.allowListMode) "Choose apps to allow" else "Choose apps to block",
                eyebrow = "Block rule",
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
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(FlintSpacing.gutter),
            ) {
                EditorHeader(
                    title = if (initialDraft.id == null) "New block rule" else "Edit block rule",
                    eyebrow = "Block rule",
                    onBack = onClose,
                )
                Spacer(Modifier.height(FlintSpacing.md))

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(nameRequester),
                    label = { Text("Rule name") },
                    placeholder = { Text("Social media") },
                    singleLine = true,
                    isError = attemptedSave && draft.name.isBlank(),
                )
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel(if (draft.allowListMode) "Allowed apps" else "Apps to block")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard(modifier = Modifier.bringIntoViewRequester(targetsRequester)) {
                    Column(Modifier.animateContentSize(editorRevealSpec())) {
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
                                TargetAppRow(
                                    app = app,
                                    icon = iconByPackage[app.packageName],
                                    onRemove = { draft = draft.copy(apps = draft.apps - app) },
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
                        Spacer(Modifier.height(FlintSpacing.md))
                        LabeledSwitchRow(
                            title = "Allow-list mode",
                            description = "Flip the rule: block every app on the phone except " +
                                "the ones above.",
                            checked = draft.allowListMode,
                            onCheckedChange = { draft = draft.copy(allowListMode = it) },
                        )
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Websites to block")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    AnimatedVisibility(
                        visible = draft.allowListMode,
                        enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                        exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
                    ) {
                        Column {
                            Text(
                                text = "While allow-list mode is on, website targets are " +
                                    "ignored by the blocker. They're kept for when you " +
                                    "switch back.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(FlintSpacing.sm))
                        }
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
                        Spacer(Modifier.width(FlintSpacing.sm))
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
                    AnimatedVisibility(
                        visible = domainError,
                        enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                        exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
                    ) {
                        Column {
                            Spacer(Modifier.height(FlintSpacing.xs))
                            Text(
                                text = "Enter a web address like youtube.com.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(FlintSpacing.xs))
                    Text(
                        text = "Subdomains count too — youtube.com also covers m.youtube.com.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.animateContentSize(editorRevealSpec())) {
                        draft.domains.forEach { domain ->
                            SelectedDomainRow(
                                domain = domain,
                                onRemove = { draft = draft.copy(domains = draft.domains - domain) },
                            )
                        }
                    }
                }
                if (groups.isNotEmpty() || onSaveGroup != null) {
                    Spacer(Modifier.height(FlintSpacing.lg))
                    SectionLabel("Groups")
                    Spacer(Modifier.height(FlintSpacing.sm))
                    FlintCard {
                        if (groups.isEmpty()) {
                            Text(
                                text = "Save a set of targets once, reuse it in any rule.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            if (draft.allowListMode) {
                                // In allow-list mode a group's apps become ALLOWED — the
                                // inverse of the chip's usual meaning. Say so at the point
                                // of tap, not a screen-scroll away.
                                Text(
                                    text = "Applying a group adds its apps to the allowed " +
                                        "list; its websites are ignored here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(FlintSpacing.sm))
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm),
                            ) {
                                groups.forEach { group ->
                                    InputChip(
                                        selected = false,
                                        onClick = {
                                            // Additive merge, deduped by package — a group
                                            // may carry a stale label for an app the draft
                                            // already lists; two rows for one package would
                                            // make "Remove" look like it worked while the
                                            // rule still targets the app.
                                            val known = draft.apps.map { it.packageName }.toSet()
                                            val newApps =
                                                group.apps.filter { it.packageName !in known }
                                            val newDomains = group.domains.map { it.domain }
                                                .distinct()
                                                .filter { it !in draft.domains }
                                            draft = draft.copy(
                                                apps = draft.apps + newApps,
                                                domains = draft.domains + newDomains,
                                            )
                                            haptics.tick()
                                            appliedGroup =
                                                group.id to (newApps.size + newDomains.size)
                                        },
                                        label = {
                                            // Transient "· N added" receipt, then back to name.
                                            val added = appliedGroup
                                                ?.takeIf { it.first == group.id }
                                                ?.second
                                            AnimatedContent(
                                                targetState = added,
                                                transitionSpec = {
                                                    fadeIn(
                                                        tween(
                                                            FlintMotion.DurationShort,
                                                            easing = FlintMotion.EasingStandard,
                                                        ),
                                                    ) togetherWith fadeOut(
                                                        tween(
                                                            FlintMotion.DurationShort,
                                                            easing = FlintMotion.EasingStandard,
                                                        ),
                                                    )
                                                },
                                                label = "groupChipLabel",
                                            ) { count ->
                                                Text(
                                                    if (count == null) {
                                                        group.name
                                                    } else {
                                                        "${group.name} · $count added"
                                                    },
                                                )
                                            }
                                        },
                                        trailingIcon = if (onDeleteGroup != null) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Delete group ${group.name}",
                                                    modifier = Modifier
                                                        .size(InputChipDefaults.IconSize)
                                                        .clickable { deletingGroup = group },
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                        if (onSaveGroup != null) {
                            TextButton(
                                onClick = { namingGroup = true },
                                enabled = draft.apps.isNotEmpty() || draft.domains.isNotEmpty(),
                            ) {
                                Text("Save these targets as a group")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Schedule")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard(modifier = Modifier.bringIntoViewRequester(scheduleRequester)) {
                    LabeledSwitchRow(
                        title = "Only at certain times",
                        description = "Off means the rule is active around the clock while " +
                            "enabled.",
                        checked = draft.scheduled,
                        onCheckedChange = { draft = draft.copy(scheduled = it) },
                    )
                    AnimatedVisibility(
                        visible = draft.scheduled,
                        enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                        exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
                    ) {
                        Column {
                            Spacer(Modifier.height(FlintSpacing.cardGap))
                            DayOfWeekRow(
                                selected = draft.days,
                                onToggle = { day ->
                                    draft = draft.copy(
                                        days = if (day in draft.days) {
                                            draft.days - day
                                        } else {
                                            draft.days + day
                                        },
                                    )
                                },
                            )
                            Spacer(Modifier.height(FlintSpacing.xs))
                            Text(
                                text = "No days selected = every day.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(FlintSpacing.cardGap))
                            Row {
                                FlintTimeField(
                                    label = "From",
                                    valueText = startText,
                                    onValueText = { startText = it },
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(FlintSpacing.cardGap))
                                FlintTimeField(
                                    label = "Until",
                                    valueText = endText,
                                    onValueText = { endText = it },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            val start = parseMinuteOfDay(startText)
                            val end = parseMinuteOfDay(endText)
                            if (start != null && end != null) {
                                Spacer(Modifier.height(FlintSpacing.cardGap))
                                FlintDayStrip(startMinute = start, endMinute = end)
                            }
                            if (start != null && end != null && end != start) {
                                Spacer(Modifier.height(FlintSpacing.xs))
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
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Break difficulty")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintCard {
                    BreakLevelSelector(
                        selected = draft.breakLevel,
                        onSelect = { draft = draft.copy(breakLevel = it) },
                    )
                    AnimatedVisibility(
                        visible = draft.breakLevel == BreakLevel.HARDCORE,
                        enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                        exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
                    ) {
                        Column {
                            Spacer(Modifier.height(FlintSpacing.sm))
                            Text(
                                text = "Hardcore means it: no breaks, no early stop. Your " +
                                    "weekly Emergency Pass is the only exit.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
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
                        val problems = validate(candidate)
                        if (problems.isEmpty()) {
                            haptics.confirm()
                            onSave(candidate)
                        } else {
                            haptics.reject()
                            // Walk the eye to the first offending field, not just the summary.
                            val requester = when (problems.first()) {
                                RuleDraftError.NAME_REQUIRED -> nameRequester
                                RuleDraftError.NO_TARGETS,
                                RuleDraftError.NO_ALLOWED_APPS,
                                -> targetsRequester
                                RuleDraftError.INVALID_TIME,
                                RuleDraftError.ZERO_LENGTH_WINDOW,
                                -> scheduleRequester
                            }
                            scope.launch { requester.bringIntoView() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save rule")
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(FlintSpacing.sm))
                    TextButton(
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete rule")
                    }
                }
                Spacer(Modifier.height(FlintSpacing.sm))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }

    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this rule?") },
            text = { Text("Its blocks stop applying. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep it") }
            },
        )
    }

    deletingGroup?.let { group ->
        if (onDeleteGroup != null) {
            AlertDialog(
                onDismissRequest = { deletingGroup = null },
                title = { Text("Delete “${group.name}”?") },
                text = {
                    Text(
                        "Rules that already used this group keep their targets — only the " +
                            "reusable group goes away.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteGroup(group.id)
                            deletingGroup = null
                        },
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletingGroup = null }) { Text("Keep it") }
                },
            )
        }
    }

    if (namingGroup && onSaveGroup != null) {
        AlertDialog(
            onDismissRequest = { namingGroup = false },
            title = { Text("Name this group") },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group name") },
                    placeholder = { Text("Social media") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveGroup(groupName, draft.apps, draft.domains)
                        namingGroup = false
                        groupName = ""
                    },
                    enabled = groupName.isNotBlank(),
                ) {
                    Text("Save group")
                }
            },
            dismissButton = {
                TextButton(onClick = { namingGroup = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SelectedDomainRow(domain: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = FlintSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = domain,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
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
internal fun DayOfWeekRow(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
    ) {
        IsoDays.ALL.forEach { day ->
            val isSelected = day in selected
            val container by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
                label = "dayPillContainer",
            )
            // The label eases on the container's own spec — a snapped color would sit on the
            // wrong side of the fill for the animation's first half.
            val labelColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
                label = "dayPillLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    // 48dp touch minimum; the visual pill stays compact via center alignment.
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(container)
                    .toggleable(
                        value = isSelected,
                        role = Role.Checkbox,
                        onValueChange = {
                            haptics.tick()
                            onToggle(day)
                        },
                    )
                    .padding(vertical = FlintSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = IsoDays.shortLabel(day),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
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
