package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.ui.FlintNavPanel
import com.flint.peakfocus.core.common.ui.FlintAppTile
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.FlintCircleButton
import com.flint.peakfocus.core.common.ui.FlintStatusPill
import com.flint.peakfocus.core.common.ui.flintSwitchColors
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.theme.rememberFlintMark
import com.flint.peakfocus.core.common.ui.FlintBadge
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.FlintSkeletonRow
import com.flint.peakfocus.core.common.ui.FlintStatusDot
import com.flint.peakfocus.core.common.ui.flintLoading
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.common.ui.rememberReducedMotion
import com.flint.peakfocus.blocking.overlay.TargetProtectionReader
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit
import com.flint.peakfocus.core.model.StorageState
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import kotlinx.coroutines.launch

/** Where inside the feature the user currently is. Internal navigation only — no nav graph.
 *  [visit] makes every editor open unique: without it, re-opening an equal destination inside
 *  the ~300ms pop window aliases the still-exiting AnimatedContent slot and resurrects its
 *  half-typed remembered draft. */
private sealed interface BlocklistDestination {
    data object Overview : BlocklistDestination

    /** [ruleId] null = author a new rule; [preset] pre-fills the draft (templates row). */
    data class EditRule(
        val ruleId: String?,
        val preset: RoutinePreset? = null,
        val visit: Int = 0,
    ) : BlocklistDestination

    /** [packageName] null = pick the app first. */
    data class EditLimit(val packageName: String?, val visit: Int = 0) : BlocklistDestination

    data class EditSleep(val visit: Int = 0) : BlocklistDestination

    /** Read-only: picked apps and sites, then lane C's per-target protection for one. */
    data object AppsAndSites : BlocklistDestination
    data class TargetDetail(val picked: PickedTarget) : BlocklistDestination
}

/** Editor push distance (LIST-07): content enters from a 30dp end inset, not full-width. */
private val EditorSlideDistance = 30.dp

/** Template cards ride a horizontal rail — fixed width so descriptions wrap consistently. */
private val PresetCardWidth = 220.dp

private fun <T> editorTween() =
    tween<T>(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)

/** SpringSettle's physics re-typed for list placement (the token is Float-typed). */
private val itemPlacementSpec: FiniteAnimationSpec<IntOffset> = spring(
    dampingRatio = FlintMotion.SpringSettle.dampingRatio,
    stiffness = FlintMotion.SpringSettle.stiffness,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/** The overview's shared row motion: placement on spring physics, fades on the medium tween. */
private fun LazyItemScope.animatedRow(): Modifier = Modifier.animateItem(
    fadeInSpec = editorTween(),
    placementSpec = itemPlacementSpec,
    fadeOutSpec = editorTween(),
)

/** LazyColumn index of the templates label when the rules section shows the first-run hero
 *  (header, rules label, hero — templates come fourth). Target of the hero's template CTA. */
private const val EMPTY_TEMPLATES_INDEX = 3

/**
 * Blocklist authoring — the feature's single public entry point, for the A-VERIFY integrator
 * to wire into MainActivity navigation (this module never touches the nav graph or app shell).
 *
 * One screen, three internal layers: an overview of block rules and daily app limits, a rule
 * editor (apps, websites, allow-list mode, schedule, break level), and a per-app limit editor
 * (time + open limits). All reads/writes flow through core-datastore's observable stores via
 * [BlocklistViewModel]; the system back button collapses editors back to the overview.
 */
/**
 * The Rules tab. [contentPadding] clears the status bar and the floating tab bar;
 * [quickBlockSummary]/[onQuickBlock] surface the always-on quick block list kept by the app.
 */
@Composable
fun BlocklistScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    quickBlockSummary: String? = null,
    onQuickBlock: (() -> Unit)? = null,
    /** Lane C's read-only per-target reader; Apps and sites is shown only when supplied. */
    targetProtection: TargetProtectionReader? = null,
) {
    val context = LocalContext.current
    val blocklistViewModel: BlocklistViewModel =
        viewModel(factory = remember(context) { BlocklistViewModel.factory(context) })

    val rulesState by blocklistViewModel.rules.collectAsStateWithLifecycle()
    val limitsState by blocklistViewModel.limits.collectAsStateWithLifecycle()
    val appsState by blocklistViewModel.installedApps.collectAsStateWithLifecycle()
    val defaultBreakLevel by blocklistViewModel.defaultBreakLevel.collectAsStateWithLifecycle()
    val groups by blocklistViewModel.groups.collectAsStateWithLifecycle()
    val saveState by blocklistViewModel.editorSave.collectAsStateWithLifecycle()
    val commitmentStorage by blocklistViewModel.commitmentState.collectAsStateWithLifecycle()
    val waitRemaining by blocklistViewModel.waitRemaining.collectAsStateWithLifecycle()
    val commitmentState = (commitmentStorage as? StorageState.Ready)?.value

    // Editor state (which rule, half-built drafts) intentionally lives in composition, not
    // SavedState — process death drops back to the overview, never to a corrupt half-draft.
    var destination by remember {
        mutableStateOf<BlocklistDestination>(BlocklistDestination.Overview)
    }
    var editorVisits by remember { mutableStateOf(0) }

    LaunchedEffect(destination) { blocklistViewModel.clearSaveFeedback() }

    BackHandler(enabled = destination != BlocklistDestination.Overview) {
        if (!saveState.busy) destination = when (destination) {
            is BlocklistDestination.TargetDetail -> BlocklistDestination.AppsAndSites
            else -> BlocklistDestination.Overview
        }
    }

    val slideDistance = with(LocalDensity.current) { EditorSlideDistance.roundToPx() }
    val reducedMotion = rememberReducedMotion()

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (reducedMotion && (initialState is BlocklistDestination.EditSleep || targetState is BlocklistDestination.EditSleep)) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else if (initialState is BlocklistDestination.Overview) {
                    // Push: editor slides in from the end while the overview fades away.
                    (
                        slideInHorizontally(
                            animationSpec = editorTween(),
                            initialOffsetX = { slideDistance },
                        ) + fadeIn(editorTween())
                        ) togetherWith fadeOut(editorTween())
                } else {
                    // Pop: editor slides back out toward the end, overview fades in beneath.
                    fadeIn(editorTween()) togetherWith (
                        slideOutHorizontally(
                            animationSpec = editorTween(),
                            targetOffsetX = { slideDistance },
                        ) + fadeOut(editorTween())
                        )
                }
            },
            label = "editorPushPop",
        ) { dest ->
            when (dest) {
                is BlocklistDestination.Overview -> BlocklistOverview(
                    rulesState = rulesState,
                    limitsState = limitsState,
                    groups = groups,
                    onNewRule = {
                        destination = BlocklistDestination.EditRule(null, visit = ++editorVisits)
                    },
                    onNewRuleFromPreset = {
                        destination =
                            BlocklistDestination.EditRule(null, preset = it, visit = ++editorVisits)
                    },
                    onEditRule = {
                        destination = BlocklistDestination.EditRule(it, visit = ++editorVisits)
                    },
                    onToggleRule = blocklistViewModel::setRuleEnabled,
                    onNewLimit = {
                        destination = BlocklistDestination.EditLimit(null, visit = ++editorVisits)
                    },
                    onEditLimit = {
                        destination = BlocklistDestination.EditLimit(it, visit = ++editorVisits)
                    },
                    onEditSleep = {
                        destination = BlocklistDestination.EditSleep(visit = ++editorVisits)
                    },
                    onToggleSleep = blocklistViewModel::setSleepEnabled,
                    saveError = saveState.error ?: if (commitmentStorage is StorageState.Unavailable) EDITOR_STORAGE_UNAVAILABLE_MESSAGE else null,
                    commitmentState = commitmentState,
                    saveResult = saveState.result,
                    onDismissMigrationNotice = blocklistViewModel::dismissMigrationNotice,
                    contentPadding = contentPadding,
                    quickBlockSummary = quickBlockSummary,
                    onQuickBlock = onQuickBlock,
                    onAppsAndSites = targetProtection?.let { { destination = BlocklistDestination.AppsAndSites } },
                )

                BlocklistDestination.AppsAndSites -> {
                    val labels = (appsState as? InstalledAppsUiState.Ready)?.apps?.associate { it.packageName to it.label }.orEmpty()
                    AppsAndSitesList(
                        state = pickedTargets(rulesState, limitsState) { labels[it] },
                        onOpen = { destination = BlocklistDestination.TargetDetail(it) },
                        onBack = { destination = BlocklistDestination.Overview },
                    )
                }

                is BlocklistDestination.TargetDetail -> targetProtection?.let { reader ->
                    AppDetailRoute(dest.picked, reader, onBack = { destination = BlocklistDestination.AppsAndSites })
                } ?: LaunchedEffect(Unit) { destination = BlocklistDestination.Overview }

                is BlocklistDestination.EditRule -> {
                    val initialRules = rememberEditorSnapshot(dest, rulesState as? RulesUiState.Ready)
                    if (initialRules == null) {
                        StorageEditorUnavailable(onClose = { destination = BlocklistDestination.Overview }, loading = rulesState is RulesUiState.Loading)
                        return@AnimatedContent
                    }
                    // Snapshotted once per destination: the exiting editor must keep rendering
                    // the draft it opened with — recomputing from live rulesState mid-pop
                    // flashes a blank "New block rule" form the instant a delete's DataStore
                    // emission removes the rule while the pop animation is still playing.
                    val existing = remember(dest) {
                        initialRules.rules.firstOrNull { it.id == dest.ruleId }
                    }
                    val initialDraft = remember(dest) {
                        existing?.let(::ruleDraftOf)
                            ?: dest.preset?.let(::draftFrom)
                            ?: newRuleDraft(defaultBreakLevel)
                    }
                    RuleEditorScreen(
                        initialDraft = initialDraft,
                        appsState = appsState,
                        groups = groups,
                        onSaveGroup = blocklistViewModel::saveGroup,
                        onDeleteGroup = blocklistViewModel::deleteGroup,
                        onSave = { draft ->
                            blocklistViewModel.saveRule(draft) {
                                if (destination == dest) destination = BlocklistDestination.Overview
                            }
                        },
                        onDelete = if (existing == null) {
                            null
                        } else {
                            {
                                blocklistViewModel.deleteRule(existing.id) {
                                    if (destination == dest) destination = BlocklistDestination.Overview
                                }
                            }
                        },
                        onClose = { if (!saveState.busy) destination = BlocklistDestination.Overview },
                        saveState = saveState,
                    )
                }

                is BlocklistDestination.EditLimit -> LimitEditorFlow(
                    packageName = dest.packageName,
                    appsState = appsState,
                    limitsState = limitsState,
                    defaultBreakLevel = defaultBreakLevel,
                    onSaveLimits = { pkg, time, open ->
                        blocklistViewModel.saveLimits(pkg, time, open) {
                            if (destination == dest) destination = BlocklistDestination.Overview
                        }
                    },
                    onClearLimits = { pkg ->
                        blocklistViewModel.clearLimits(pkg) {
                            if (destination == dest) destination = BlocklistDestination.Overview
                        }
                    },
                    onClose = { if (!saveState.busy) destination = BlocklistDestination.Overview },
                    saveState = saveState,
                )

                is BlocklistDestination.EditSleep -> {
                    val initialRules = rememberEditorSnapshot(dest, rulesState as? RulesUiState.Ready)
                    if (initialRules == null) {
                        StorageEditorUnavailable(onClose = { destination = BlocklistDestination.Overview }, loading = rulesState is RulesUiState.Loading)
                        return@AnimatedContent
                    }
                    // Same snapshot rationale as EditRule: the exiting editor keeps the
                    // config it opened with even if a save's emission lands mid-pop.
                    val initial = remember(dest) {
                        sleepConfigFrom(initialRules.rules) ?: SleepConfig.DEFAULT
                    }
                    SleepEditorScreen(
                        initial = initial,
                        appsState = appsState,
                        onSave = { config ->
                            blocklistViewModel.saveSleep(config) {
                                if (destination == dest) destination = BlocklistDestination.Overview
                            }
                        },
                        onClose = { if (!saveState.busy) destination = BlocklistDestination.Overview },
                        saveState = saveState,
                    )
                }
            }
        }
    }
    commitmentState?.let { EditorCommitmentDialog(it, waitRemaining, saveState.busy, saveState.error,
        blocklistViewModel::confirmChange, blocklistViewModel::cancelChange) }
}

@OptIn(ExperimentalFoundationApi::class) // rememberSnapFlingBehavior on older foundation lines.
@Composable
private fun BlocklistOverview(
    rulesState: RulesUiState,
    limitsState: LimitsUiState,
    groups: List<AppGroup> = emptyList(),
    onNewRule: () -> Unit,
    onNewRuleFromPreset: (RoutinePreset) -> Unit = {},
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onNewLimit: () -> Unit,
    onEditLimit: (String) -> Unit,
    onEditSleep: () -> Unit,
    onToggleSleep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    quickBlockSummary: String? = null,
    onQuickBlock: (() -> Unit)? = null,
    onAppsAndSites: (() -> Unit)? = null,
    saveError: String? = null,
    saveResult: String? = null,
    commitmentState: EditorCommitmentState? = null,
    onDismissMigrationNotice: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberFlintHaptics()
    // One-shot Block Now sessions are transient and live on Today — editing one here would
    // strip its expiry on save and quietly make it permanent. Sleep rules have their own
    // section + editor below; the generic editor would corrupt their fixed ids and
    // paired-rule invariants. Null while the store is still loading.
    val authoredRules = (rulesState as? RulesUiState.Ready)?.rules?.filterNot {
        it.id.startsWith(BlockRule.SESSION_ID_PREFIX) || it.id.startsWith(SLEEP_RULE_PREFIX)
    }
    val ruleStatuses = (rulesState as? RulesUiState.Ready)?.statuses ?: emptyMap()
    val limitRows = (limitsState as? LimitsUiState.Ready)?.rows
    val nowMinute = localMinuteOfDay(System.currentTimeMillis())
    val activeRule = authoredRules?.firstOrNull { ruleStatuses[it.id]?.activeNow == true }
    // Allow-list rules name the apps left open, not the ones resting: never show those as resting.
    val activeApps = activeRule?.targets?.takeUnless { it.allowListMode }?.apps?.toList().orEmpty()
    val sleepConfig = (rulesState as? RulesUiState.Ready)?.let { sleepConfigFrom(it.rules) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + FlintSpacing.gutter,
        ),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Rules", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(FlintPalette.EmberTop, FlintPalette.Ember)))
                        .clickable(onClick = onNewRule)
                        .semantics { contentDescription = "New block rule" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FlintGlyphs.plus, contentDescription = null, tint = FlintPalette.OnEmber, modifier = Modifier.size(20.dp))
                }
            }
            ErrorMessages(listOfNotNull(saveError), Modifier.padding(horizontal = 20.dp))
            saveResult?.let { Text(it, Modifier.padding(horizontal = 20.dp)) }
            commitmentState?.let { state ->
                Column(Modifier.padding(horizontal = 20.dp)) {
                    if (state.migrationNotice) {
                        Text("Existing occurrences keep their previous exit rights until they end. New occurrences use the displayed tier.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onDismissMigrationNotice) { Text("Got it") }
                    }
                    QueuedEditorChanges(state.queued)
                }
            }
        }

        if (activeRule != null && activeApps.isNotEmpty()) {
            item(key = "resting-now") {
                Column(Modifier.padding(top = 22.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Resting now · ${activeApps.size} ${if (activeApps.size == 1) "app" else "apps"}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        val left = scheduleMinutesLeft(activeRule.schedule, nowMinute)
                        FlintStatusPill(
                            text = activeRule.name + (left?.let { " · ${shortDuration(it)} left" } ?: ""),
                            emphasized = true,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(activeApps, key = { it.packageName }) { app ->
                            Column(
                                Modifier.width(52.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                FlintAppTile(
                                    packageName = app.packageName,
                                    label = app.label,
                                    size = 52.dp,
                                    cornerRadius = 15.dp,
                                    resting = true,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    app.label ?: app.packageName,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = FlintPalette.Mist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "schedules-header") {
            SectionHeader(
                title = "Schedules",
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 26.dp),
                onClick = onNewRule,
            )
        }
        when {
            rulesState is RulesUiState.Unavailable -> item(key = "rules-unavailable") {
                ErrorMessages(listOf("Couldn't load. Try again."), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            authoredRules == null -> item(key = "rules-loading") {
                LoadingRows(
                    modifier = animatedRow()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = FlintSpacing.cardGap),
                )
            }

            authoredRules.isEmpty() -> item(key = "rules-empty") {
                EmptyCard(
                    heading = "Nothing blocked yet",
                    message = "A rule picks apps or websites and decides when they're " +
                        "off-limits.",
                    modifier = animatedRow().padding(horizontal = 20.dp).padding(top = 10.dp),
                ) {
                    Button(onClick = onNewRule, modifier = Modifier.fillMaxWidth()) {
                        Text("New block rule")
                    }
                    TextButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(EMPTY_TEMPLATES_INDEX) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start from a template")
                    }
                }
            }

            else -> item(key = "schedules-rail") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(authoredRules, key = { it.id }) { rule ->
                        ScheduleCard(
                            rule = rule,
                            status = ruleStatuses[rule.id],
                            nowMinute = nowMinute,
                            onClick = { onEditRule(rule.id) },
                            onToggle = { enabled ->
                                if (enabled) haptics.toggleOn() else haptics.toggleOff()
                                onToggleRule(rule.id, enabled)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        if (authoredRules?.isEmpty() == true) {
            item(key = "templates-label") {
                Text(
                    "Start from a template",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 10.dp),
                )
            }
            item(key = "templates-rail") {
                val railState = rememberLazyListState()
                LazyRow(
                    state = railState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    flingBehavior = rememberSnapFlingBehavior(railState),
                ) {
                    items(ROUTINE_PRESETS, key = { it.name }) { preset ->
                        PresetCard(preset = preset, onClick = { onNewRuleFromPreset(preset) })
                    }
                }
            }
        }

        item(key = "quick-block") {
            if (onQuickBlock != null) {
                FlintNavPanel(
                    title = "Quick block list",
                    subtitle = quickBlockSummary,
                    onClick = onQuickBlock,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 6.dp),
                )
            }
        }

        item(key = "limits-header") {
            SectionHeader(
                title = "Limits",
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 26.dp),
                onClick = onNewLimit,
                trailing = "New limit",
            )
        }
        when {
            limitsState is LimitsUiState.Unavailable -> item(key = "limits-unavailable") {
                ErrorMessages(listOf("Couldn't load. Try again."), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            limitRows == null -> item(key = "limits-loading") {
                LoadingRows(
                    modifier = animatedRow()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = FlintSpacing.cardGap),
                )
            }

            limitRows.isEmpty() -> item(key = "limits-empty") {
                EmptyCard(
                    heading = "No app limits yet",
                    message = "Set a daily time budget, or choose how many 5-minute access credits you can use each day.",
                    modifier = animatedRow()
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp),
                )
            }

            else -> item(key = "limits-rail") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(limitRows, key = { it.packageName }) { row ->
                        LimitCard(
                            row = row,
                            onClick = { onEditLimit(row.packageName) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        // Always offer Sleep once rules load: a null config is the set-up state, and it is
        // the only way into the Sleep editor for someone who has never configured it.
        if (rulesState is RulesUiState.Ready) {
            val config = sleepConfig
            item(key = "sleep-label") {
                SectionHeader(
                    title = "Sleep",
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 26.dp),
                    onClick = onEditSleep,
                    trailing = "Edit",
                )
            }
            item(key = "sleep-card") {
                SleepCard(
                    config = config,
                    onClick = onEditSleep,
                    onToggle = { enabled ->
                        if (enabled) haptics.toggleOn() else haptics.toggleOff()
                        onToggleSleep(enabled)
                    },
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 10.dp),
                )
            }
        }

        if (groups.isNotEmpty()) {
            item(key = "groups-label") {
                Text(
                    "Groups",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 26.dp, bottom = 12.dp),
                )
            }
            groups.chunked(3).forEachIndexed { index, rowGroups ->
                item(key = "groups-row-$index") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowGroups.forEach { group ->
                            GroupFolder(group = group, modifier = Modifier.weight(1f))
                        }
                        repeat(3 - rowGroups.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        if (authoredRules?.isEmpty() != true) {
            item(key = "templates-label") {
                Text(
                    "Start from a template",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 10.dp),
                )
            }
            item(key = "templates-rail") {
                val railState = rememberLazyListState()
                LazyRow(
                    state = railState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    flingBehavior = rememberSnapFlingBehavior(railState),
                ) {
                    items(ROUTINE_PRESETS, key = { it.name }) { preset ->
                        PresetCard(preset = preset, onClick = { onNewRuleFromPreset(preset) })
                    }
                }
            }
        }

        if (onAppsAndSites != null) {
            item(key = "apps-and-sites") {
                FlintNavPanel(
                    title = stringResource(R.string.targets_title),
                    subtitle = stringResource(R.string.targets_entry_description),
                    onClick = onAppsAndSites,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(top = 22.dp),
                )
            }
        }
    }
}

/** The v3 section heading with an optional trailing action ("Schedules ›", "New limit"). */
@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick) else Modifier,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (onClick != null) {
            Icon(FlintGlyphs.chevronRight, contentDescription = null, tint = FlintPalette.Mist, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = FlintPalette.Mist,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

/** M13's rule card: calendar → lock header, time-left/next pill, name, targets and the switch. */
@Composable
private fun ScheduleCard(
    rule: BlockRule,
    status: RuleLiveStatus?,
    nowMinute: Int,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = status?.activeNow == true && rule.enabled
    // A finished one-time rule has no future window to arm: its switch would be a lie.
    val finished = status?.oneTimePhase == OneTimePhase.FINISHED
    val left = if (active) scheduleMinutesLeft(rule.schedule, nowMinute) else null
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .width(196.dp)
            .clip(shape)
            .background(
                if (active) {
                    Brush.verticalGradient(
                        listOf(
                            FlintPalette.Ember.copy(alpha = 0.16f),
                            FlintPalette.Ember.copy(alpha = 0.04f),
                            FlintPalette.Card,
                        ),
                    )
                } else {
                    SolidColor(if (rule.enabled) FlintPalette.Card else FlintPalette.Night)
                },
            )
            .border(1.dp, if (active) FlintPalette.Ember.copy(alpha = 0.35f) else FlintPalette.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(FlintPalette.BoneFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(FlintGlyphs.calendar, contentDescription = null, tint = FlintPalette.Bone, modifier = Modifier.size(16.dp))
            }
            Icon(FlintGlyphs.chevronRight, contentDescription = null, tint = FlintPalette.Stone, modifier = Modifier.size(14.dp))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (active) FlintPalette.Ember.copy(alpha = 0.18f) else FlintPalette.BoneFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    FlintGlyphs.lock,
                    contentDescription = null,
                    tint = if (active) FlintPalette.Ember else FlintPalette.Bone,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        when {
            finished -> FlintStatusPill("Finished", emphasized = false)
            !rule.enabled -> FlintStatusPill("Off", emphasized = false)
            active -> FlintStatusPill("${left?.let { shortDuration(it) + " left" } ?: "Active now"}", emphasized = true)
            status?.nextChange != null -> FlintStatusPill(
                status.nextChange.replace("Starts ", "Next · "),
                emphasized = false,
            )
            else -> FlintStatusPill("Always on", emphasized = false)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                rule.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (rule.enabled) FlintPalette.Bone else FlintPalette.Mist,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                scheduleSummary(rule.schedule),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = FlintPalette.Mist,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (rule.targets.allowListMode) "Allow" else "Block",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = FlintPalette.Mist,
            )
            rule.targets.apps.take(2).forEach { app ->
                FlintAppTile(
                    packageName = app.packageName,
                    label = app.label,
                    size = 20.dp,
                    cornerRadius = 6.dp,
                )
            }
            val more = rule.targets.apps.size - 2
            if (more > 0) {
                Text(
                    "+$more",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = FlintPalette.Bone,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!finished) {
                Switch(
                    colors = flintSwitchColors(),
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier
                        .scale(0.78f)
                        .semantics { contentDescription = "${rule.name} rule" },
                )
            }
        }
    }
}

/** M13's limit card: app + configured budget, with an honest empty meter (no usage data here). */
/** One section label item: the same lg beat above every eyebrow, the sm beat under it. */
@Composable
private fun SectionLabelItem(text: String) {
    Column(Modifier.padding(horizontal = FlintSpacing.gutter)) {
        Spacer(Modifier.height(FlintSpacing.lg))
        FlintSectionLabel(text)
        Spacer(Modifier.height(FlintSpacing.sm))
    }
}

/** The one loading idiom app-wide: pulsing skeleton rows where the cards will land. */
@Composable
private fun LoadingRows(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().flintLoading()) {
        FlintSkeletonRow()
        FlintSkeletonRow()
    }
}

@Composable
private fun PresetCard(preset: RoutinePreset, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlintCard(modifier = modifier.width(PresetCardWidth), onClick = onClick) {
        Text(
            text = preset.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(FlintSpacing.xs))
        Text(
            text = scheduleSummary(preset.schedule),
            style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FlintSpacing.xs))
        Text(
            text = preset.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FlintSpacing.sm))
        FlintBadge(
            text = breakLevelLabel(preset.breakLevel),
            emphasized = preset.breakLevel == BreakLevel.HARDCORE,
        )
    }
}

/** Empty-state card: the untinted tri-color mark in a tonal circle over a heading, caption,
 *  and (for the first-run rules hero) the section's own actions. */
@Composable
private fun EmptyCard(
    heading: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    FlintCard(modifier = modifier, contentPadding = PaddingValues(FlintSpacing.lg)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(FlintSpacing.xl * 2)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = rememberFlintMark(),
                    contentDescription = null,
                    // Unspecified keeps the mark's three facets instead of flattening them.
                    tint = Color.Unspecified,
                    modifier = Modifier.size(FlintSpacing.xl),
                )
            }
            Spacer(Modifier.height(FlintSpacing.md))
            Text(
                text = heading,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(FlintSpacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actions != null) {
                Spacer(Modifier.height(FlintSpacing.md))
                actions()
            }
        }
    }
}

@Composable
internal fun SleepCard(
    config: SleepConfig?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = config?.enabled == true
    val reducedMotion = rememberReducedMotion()
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        animationSpec = if (reducedMotion) snap() else tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
        label = "sleepCardContainer",
    )
    val titleColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = if (reducedMotion) snap() else tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
        label = "sleepCardTitle",
    )
    FlintCard(modifier = modifier, onClick = onClick, containerColor = containerColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sleep_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                )
                Spacer(Modifier.height(FlintSpacing.xs))
                if (config == null) {
                    Text(
                        text = stringResource(R.string.sleep_not_set_up),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = sleepSummaryText(config),
                        style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(FlintSpacing.sm))
                    FlintBadge(
                        text = stringResource(R.string.sleep_tier_value, stringResource(sleepPresentation(config).tier)),
                        emphasized = sleepPresentation(config).emphasizeTier,
                    )
                }
            }
            Spacer(Modifier.width(FlintSpacing.cardGap))
            if (config == null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val ruleDescription = stringResource(R.string.sleep_rule_description)
                Switch(
                    colors = flintSwitchColors(),
                    checked = config.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = ruleDescription },
                )
            }
        }
    }
}

/** M13's limit card: app + configured budget, with an honest empty meter (no usage data here). */
@Composable
private fun LimitCard(row: AppLimitRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .width(176.dp)
            .clip(shape)
            .background(FlintPalette.Card)
            .border(1.dp, FlintPalette.Hairline, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlintAppTile(
                packageName = row.packageName,
                label = row.displayName,
                size = 32.dp,
                cornerRadius = 10.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    row.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    limitSummary(row.timeLimit, row.openLimit),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = FlintPalette.Mist,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.openLimit != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(row.openLimit.dailyOpens.coerceIn(1, 5)) {
                    Box(
                        Modifier.weight(1f).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(FlintPalette.Bone.copy(alpha = 0.1f)),
                    )
                }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FlintPalette.Bone.copy(alpha = 0.1f)),
            )
        }
    }
}

/** M13's folder-style group tile: a 2x2 grid of real icons, then the name and app count. */
@Composable
private fun GroupFolder(group: AppGroup, modifier: Modifier = Modifier) {
    val apps = group.apps.toList()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(FlintPalette.BoneFaint.copy(alpha = 0.9f))
                .border(1.dp, FlintPalette.Hairline, RoundedCornerShape(24.dp))
                .padding(10.dp),
        ) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) { rowIndex ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) { columnIndex ->
                            val app = apps.getOrNull(rowIndex * 2 + columnIndex)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (app == null) FlintPalette.BoneFaint else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (app != null) {
                                    FlintAppTile(
                                        packageName = app.packageName,
                                        label = app.label,
                                        size = 32.dp,
                                        cornerRadius = 8.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            group.name,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            "${group.apps.size} ${if (group.apps.size == 1) "app" else "apps"}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = FlintPalette.Mist,
            textAlign = TextAlign.Center,
        )
    }
}

// --- Previews (fake data only; no ViewModel) ---

private fun previewRulesState() = RulesUiState.Ready(
    listOf(
        BlockRule(
            id = "1",
            name = "Social media",
            targets = BlockTargets(
                apps = setOf(
                    AppRef("com.instagram.android", "Instagram"),
                    AppRef("com.zhiliaoapp.musically", "TikTok"),
                ),
                domains = setOf(DomainRef("reddit.com")),
            ),
            schedule = Schedule(
                daysOfWeek = IsoDays.WEEKDAYS,
                startMinuteOfDay = 9 * 60,
                endMinuteOfDay = 17 * 60,
            ),
            breakLevel = BreakLevel.HARDER,
        ),
        BlockRule(
            id = "2",
            name = "Brick phone nights",
            targets = BlockTargets(
                apps = setOf(AppRef("org.thoughtcrime.securesms", "Signal")),
                allowListMode = true,
            ),
            schedule = Schedule(
                daysOfWeek = emptySet(),
                startMinuteOfDay = 22 * 60,
                endMinuteOfDay = 6 * 60,
            ),
            breakLevel = BreakLevel.HARDCORE,
            enabled = false,
        ),
    ),
)

private fun previewLimitsState() = LimitsUiState.Ready(
    listOf(
        AppLimitRow(
            packageName = "com.google.android.youtube",
            label = "YouTube",
            timeLimit = TimeLimit("com.google.android.youtube", 45),
            openLimit = OpenLimit("com.google.android.youtube", 5, BreakLevel.HARDCORE),
        ),
    ),
)

@Preview(showBackground = true)
@Composable
private fun BlocklistOverviewPreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            BlocklistOverview(
                rulesState = previewRulesState(),
                limitsState = previewLimitsState(),
                onNewRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onNewLimit = {},
                onEditLimit = {},
                onEditSleep = {},
                onToggleSleep = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BlocklistOverviewDarkPreview() {
    FlintTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            BlocklistOverview(
                rulesState = previewRulesState(),
                limitsState = previewLimitsState(),
                onNewRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onNewLimit = {},
                onEditLimit = {},
                onEditSleep = {},
                onToggleSleep = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BlocklistOverviewEmptyPreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            BlocklistOverview(
                rulesState = RulesUiState.Ready(emptyList()),
                limitsState = LimitsUiState.Ready(emptyList()),
                onNewRule = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onNewLimit = {},
                onEditLimit = {},
                onEditSleep = {},
                onToggleSleep = {},
            )
        }
    }
}
