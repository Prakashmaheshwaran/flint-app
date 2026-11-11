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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.BoneButton
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintSegmented
import com.flint.peakfocus.core.common.ui.FlintTimeField
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BreakLevel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class) // bringIntoViewRequester; date picker
@Composable
internal fun RuleEditorScreen(
    initialDraft: RuleDraft,
    appsState: InstalledAppsUiState,
    onSave: (RuleDraft) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    groups: List<AppGroup> = emptyList(),
    onSaveGroup: ((name: String, apps: Set<AppRef>, domains: List<String>, onSaved: () -> Unit) -> Unit)? = null,
    onDeleteGroup: ((groupId: String, onSaved: () -> Unit) -> Unit)? = null,
    saveState: EditorSaveState = EditorSaveState(),
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
    var pickingDate by remember { mutableStateOf(false) }
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
                groups = groups,
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
                    .padding(horizontal = 20.dp),
            ) {
                val attemptSave: () -> Unit = {
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
                            RuleDraftError.DATE_REQUIRED,
                            RuleDraftError.START_IN_PAST,
                            -> scheduleRequester
                        }
                        scope.launch { requester.bringIntoView() }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onClose,
                        enabled = !saveState.busy,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Text("Cancel", color = FlintPalette.Mist)
                    }
                    Text(
                        "Block rule",
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

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .bringIntoViewRequester(nameRequester),
                ) {
                    Text("Name", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = FlintPalette.Mist)
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        singleLine = true,
                        // BasicTextField has no theme color: without one it draws black on Ground.
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 28.sp,
                            letterSpacing = (-0.5).sp,
                            color = FlintPalette.Bone,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(FlintPalette.Ember),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
                            if (attemptedSave && draft.name.isBlank()) {
                                error("Rule name is required")
                            }
                        },
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                                if (draft.name.isEmpty()) {
                                    Text(
                                        "Laser Focus",
                                        style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, letterSpacing = (-0.5).sp),
                                        color = FlintPalette.Faint,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(
                                if (attemptedSave && draft.name.isBlank()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    FlintPalette.Divider
                                },
                            ),
                    )
                }
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
                    if (draft.id == null && draft.scheduled) {
                        // Only a NEW rule with a window chooses its kind: an existing rule keeps
                        // the one it has, so a weekly rule can never be silently rewritten into
                        // a one-time one. With "Only at certain times" off there is no window at
                        // all, so there is no kind to choose.
                        FlintSegmented(
                            options = listOf(false to "Repeats", true to "One time"),
                            selected = draft.oneTime,
                            onSelect = { oneTime ->
                                haptics.tick()
                                draft = if (oneTime) {
                                    draft.copy(oneTime = true, scheduled = true, date = draft.date ?: LocalDate.now())
                                } else {
                                    draft.copy(oneTime = false, scheduled = true)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(FlintSpacing.cardGap))
                    }
                    if (draft.oneTime) {
                        OneTimeWindowFields(
                            date = draft.date,
                            onPickDate = { pickingDate = true },
                            startText = startText,
                            onStartText = { startText = it },
                            endText = endText,
                            onEndText = { endText = it },
                        )
                    } else {
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
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                SectionLabel("Break level")
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintSegmented(
                    options = BreakLevel.entries.map { it to breakLevelLabel(it) },
                    selected = draft.breakLevel,
                    onSelect = { draft = draft.copy(breakLevel = it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when (draft.breakLevel) {
                        BreakLevel.EASY -> "Stop any time."
                        BreakLevel.HARDER -> "No early stop. Each break waits a few minutes first."
                        BreakLevel.HARDCORE -> "No breaks, no stop, can’t delete Flint until it ends."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = FlintPalette.Mist,
                )
                AnimatedVisibility(
                    visible = draft.breakLevel == BreakLevel.HARDCORE,
                    enter = expandVertically(editorRevealSpec()) + fadeIn(editorRevealSpec()),
                    exit = shrinkVertically(editorRevealSpec()) + fadeOut(editorRevealSpec()),
                ) {
                    Column {
                        Spacer(Modifier.height(FlintSpacing.sm))
                        HardcoreCommitmentDisclosure()
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))

                ErrorMessages(errors.map { it.message } + if (namingGroup || deletingGroup != null) emptyList() else listOfNotNull(saveState.error))
                if (errors.isNotEmpty()) Spacer(Modifier.height(FlintSpacing.cardGap))

                com.flint.peakfocus.core.common.ui.EmberButton(
                    text = "Save rule",
                    onClick = attemptSave,
                    enabled = !saveState.busy,
                )
                if (onDelete != null) {
                    Spacer(Modifier.height(FlintSpacing.sm))
                    TextButton(
                        onClick = { confirmingDelete = true },
                        enabled = !saveState.busy,
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

    if (pickingDate) {
        val today = LocalDate.now()
        val initial = draft.date ?: today
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // A one-time session can be any distance ahead, never in the past.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

                override fun isSelectableYear(year: Int): Boolean = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            draft = draft.copy(date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        pickingDate = false
                    },
                ) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state, title = { Text("One-time session date") })
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
                    Column {
                        ErrorMessages(listOfNotNull(saveState.error))
                        Text(
                            "Rules that already used this group keep their targets — only the " +
                                "reusable group goes away.",
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !saveState.busy,
                        onClick = {
                            onDeleteGroup(group.id) { deletingGroup = null }
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
                Column {
                    ErrorMessages(listOfNotNull(saveState.error))
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        enabled = !saveState.busy,
                        label = { Text("Group name") },
                        placeholder = { Text("Social media") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveGroup(groupName, draft.apps, draft.domains) {
                            namingGroup = false
                            groupName = ""
                        }
                    },
                    enabled = groupName.isNotBlank() && !saveState.busy,
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

/**
 * The one-time window's own fields: a calendar date (any distance ahead, never in the past) plus
 * the same From/Until time fields the weekly window uses. There are no weekday pills — a date,
 * not a weekday, decides when this single occurrence runs.
 */
@Composable
private fun OneTimeWindowFields(
    date: LocalDate?,
    onPickDate: () -> Unit,
    startText: String,
    onStartText: (String) -> Unit,
    endText: String,
    onEndText: (String) -> Unit,
) {
    Spacer(Modifier.height(FlintSpacing.cardGap))
    Surface(
        onClick = onPickDate,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Date",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = date?.let(::oneTimeDateLabel) ?: "Choose a date",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp),
            )
        }
    }
    Spacer(Modifier.height(FlintSpacing.cardGap))
    Row {
        FlintTimeField(
            label = "From",
            valueText = startText,
            onValueText = onStartText,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(FlintSpacing.cardGap))
        FlintTimeField(
            label = "Until",
            valueText = endText,
            onValueText = onEndText,
            modifier = Modifier.weight(1f),
        )
    }
    val start = parseMinuteOfDay(startText)
    val end = parseMinuteOfDay(endText)
    if (start != null && end != null) {
        val minutes = when {
            end > start -> end - start
            end < start -> 1440 - start + end // overnight: ends on the NEXT day
            else -> 0
        }
        if (minutes > 0) {
            Spacer(Modifier.height(FlintSpacing.xs))
            Text(
                text = "Lasts ${formatDailyMinutes(minutes)}" +
                    if (end < start) " · Ends the next day" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(FlintSpacing.xs))
        Text(
            text = "One-time session on ${date?.let(::oneTimeDateLabel) ?: "the chosen date"} — it " +
                "runs once and never repeats. Times use this device's time zone (${ZoneId.systemDefault().id}).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * Seven equal-width day pills, ISO order Mon..Sun, matching [IsoDays]. When the scaled labels
 * cannot all fit on one line the pills wrap to two rows (4 + 3) instead of splitting a label
 * mid-word ("Mo/n").
 */
@Composable
internal fun DayOfWeekRow(
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    val labelStyle = MaterialTheme.typography.titleSmall.copy(
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
    )
    val pill: @Composable (Int, Modifier) -> Unit = { day, pillModifier ->
        val isSelected = day in selected
        val container by animateColorAsState(
            targetValue = if (isSelected) FlintPalette.Ember else FlintPalette.Card,
            animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
            label = "dayPillContainer",
        )
        // The label eases on the container's own spec — a snapped color would sit on the
        // wrong side of the fill for the animation's first half.
        val labelColor by animateColorAsState(
            targetValue = if (isSelected) FlintPalette.OnEmber else FlintPalette.Mist,
            animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
            label = "dayPillLabel",
        )
        Box(
            modifier = pillModifier
                // 48dp touch minimum; the visual pill stays compact via center alignment.
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(container)
                .border(1.dp, if (isSelected) FlintPalette.Ember else FlintPalette.Divider, RoundedCornerShape(14.dp))
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
                style = labelStyle,
                color = labelColor,
                maxLines = 1,
            )
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val gap = 6.dp
        val widest = IsoDays.ALL.maxOf { day ->
            measurer.measure(AnnotatedString(IsoDays.shortLabel(day)), labelStyle, softWrap = false).size.width
        }
        // Each pill needs its widest label plus a little side room to read as a pill.
        val needed = (with(density) { widest.toDp() } + 8.dp) * IsoDays.ALL.size + gap * (IsoDays.ALL.size - 1)
        val rows = if (needed <= maxWidth) listOf(IsoDays.ALL) else IsoDays.ALL.chunked(4)
        val perRow = rows.maxOf { it.size }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            rows.forEach { days ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    days.forEach { day -> pill(day, Modifier.weight(1f)) }
                    // Keep pills the same width in a short last row.
                    repeat(perRow - days.size) { Spacer(Modifier.weight(1f)) }
                }
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
