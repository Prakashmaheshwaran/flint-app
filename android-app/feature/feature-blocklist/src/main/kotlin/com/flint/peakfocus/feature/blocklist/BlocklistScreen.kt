package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.theme.rememberFlintMark
import com.flint.peakfocus.core.common.ui.FlintBadge
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.FlintSkeletonRow
import com.flint.peakfocus.core.common.ui.FlintStatusDot
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit
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
@Composable
fun BlocklistScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blocklistViewModel: BlocklistViewModel =
        viewModel(factory = remember(context) { BlocklistViewModel.factory(context) })

    val rulesState by blocklistViewModel.rules.collectAsStateWithLifecycle()
    val limitsState by blocklistViewModel.limits.collectAsStateWithLifecycle()
    val appsState by blocklistViewModel.installedApps.collectAsStateWithLifecycle()
    val defaultBreakLevel by blocklistViewModel.defaultBreakLevel.collectAsStateWithLifecycle()
    val groups by blocklistViewModel.groups.collectAsStateWithLifecycle()

    // Editor state (which rule, half-built drafts) intentionally lives in composition, not
    // SavedState — process death drops back to the overview, never to a corrupt half-draft.
    var destination by remember {
        mutableStateOf<BlocklistDestination>(BlocklistDestination.Overview)
    }
    var editorVisits by remember { mutableStateOf(0) }

    BackHandler(enabled = destination != BlocklistDestination.Overview) {
        destination = BlocklistDestination.Overview
    }

    val slideDistance = with(LocalDensity.current) { EditorSlideDistance.roundToPx() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                if (initialState is BlocklistDestination.Overview) {
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
                )

                is BlocklistDestination.EditRule -> {
                    // Snapshotted once per destination: the exiting editor must keep rendering
                    // the draft it opened with — recomputing from live rulesState mid-pop
                    // flashes a blank "New block rule" form the instant a delete's DataStore
                    // emission removes the rule while the pop animation is still playing.
                    val existing = remember(dest) {
                        (rulesState as? RulesUiState.Ready)
                            ?.rules?.firstOrNull { it.id == dest.ruleId }
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
                            blocklistViewModel.saveRule(draft)
                            destination = BlocklistDestination.Overview
                        },
                        onDelete = if (existing == null) {
                            null
                        } else {
                            {
                                blocklistViewModel.deleteRule(existing.id)
                                destination = BlocklistDestination.Overview
                            }
                        },
                        onClose = { destination = BlocklistDestination.Overview },
                    )
                }

                is BlocklistDestination.EditLimit -> LimitEditorFlow(
                    packageName = dest.packageName,
                    appsState = appsState,
                    limitsState = limitsState,
                    defaultBreakLevel = defaultBreakLevel,
                    onSaveLimits = { pkg, time, open ->
                        blocklistViewModel.saveLimits(pkg, time, open)
                        destination = BlocklistDestination.Overview
                    },
                    onClearLimits = { pkg ->
                        blocklistViewModel.clearLimits(pkg)
                        destination = BlocklistDestination.Overview
                    },
                    onClose = { destination = BlocklistDestination.Overview },
                )

                is BlocklistDestination.EditSleep -> {
                    // Same snapshot rationale as EditRule: the exiting editor keeps the
                    // config it opened with even if a save's emission lands mid-pop.
                    val initial = remember(dest) {
                        (rulesState as? RulesUiState.Ready)
                            ?.rules?.let(::sleepConfigFrom)
                            ?: SleepConfig.DEFAULT
                    }
                    SleepEditorScreen(
                        initial = initial,
                        appsState = appsState,
                        onSave = { config ->
                            blocklistViewModel.saveSleep(config)
                            destination = BlocklistDestination.Overview
                        },
                        onClose = { destination = BlocklistDestination.Overview },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class) // rememberSnapFlingBehavior on older foundation lines.
@Composable
private fun BlocklistOverview(
    rulesState: RulesUiState,
    limitsState: LimitsUiState,
    onNewRule: () -> Unit,
    onNewRuleFromPreset: (RoutinePreset) -> Unit = {},
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onNewLimit: () -> Unit,
    onEditLimit: (String) -> Unit,
    onEditSleep: () -> Unit,
    onToggleSleep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberFlintHaptics()
    // One-shot Block Now sessions are transient and live on Home — editing one here would
    // strip its expiry on save and quietly make it permanent. Sleep rules have their own
    // section + editor below; the generic editor would corrupt their fixed ids and
    // paired-rule invariants. Null while the store is still loading.
    val authoredRules = (rulesState as? RulesUiState.Ready)?.rules?.filterNot {
        it.id.startsWith(BlockRule.SESSION_ID_PREFIX) || it.id.startsWith(SLEEP_RULE_PREFIX)
    }
    val ruleStatuses = (rulesState as? RulesUiState.Ready)?.statuses ?: emptyMap()
    val limitRows = (limitsState as? LimitsUiState.Ready)?.rows

    // The gutter rides each item, not the list, so the template rail can bleed edge-to-edge.
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = FlintSpacing.gutter),
    ) {
        item(key = "header") {
            FlintScreenHeader(
                title = "Blocking",
                eyebrow = "Rules, schedules & limits",
                modifier = Modifier.padding(horizontal = FlintSpacing.gutter),
            )
        }
        item(key = "rules-label") {
            SectionLabelItem("Block rules")
        }
        when {
            authoredRules == null -> item(key = "rules-loading") {
                LoadingRows(
                    modifier = animatedRow()
                        .padding(horizontal = FlintSpacing.gutter)
                        .padding(bottom = FlintSpacing.cardGap),
                )
            }

            authoredRules.isEmpty() -> item(key = "rules-empty") {
                EmptyCard(
                    heading = "Nothing blocked yet",
                    message = "A rule picks apps or websites and decides when they're " +
                        "off-limits.",
                    modifier = animatedRow().padding(horizontal = FlintSpacing.gutter),
                ) {
                    Button(onClick = onNewRule, modifier = Modifier.fillMaxWidth()) {
                        Text("New block rule")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(EMPTY_TEMPLATES_INDEX)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start from a template")
                    }
                }
            }

            else -> items(authoredRules, key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    status = ruleStatuses[rule.id],
                    onClick = { onEditRule(rule.id) },
                    onToggle = { enabled ->
                        if (enabled) haptics.toggleOn() else haptics.toggleOff()
                        onToggleRule(rule.id, enabled)
                    },
                    modifier = animatedRow()
                        .padding(horizontal = FlintSpacing.gutter)
                        .padding(bottom = FlintSpacing.cardGap),
                )
            }
        }
        // The hero embeds its own CTA; the standalone button belongs to the populated list.
        if (authoredRules?.isEmpty() != true) {
            item(key = "new-rule") {
                Button(
                    onClick = onNewRule,
                    modifier = animatedRow()
                        .fillMaxWidth()
                        .padding(horizontal = FlintSpacing.gutter),
                ) {
                    Text("New block rule")
                }
            }
        }

        item(key = "templates-label") {
            SectionLabelItem("Start from a template")
        }
        item(key = "templates-rail") {
            val railState = rememberLazyListState()
            LazyRow(
                state = railState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = FlintSpacing.gutter),
                horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
                flingBehavior = rememberSnapFlingBehavior(railState),
            ) {
                items(ROUTINE_PRESETS, key = { it.name }) { preset ->
                    PresetCard(preset = preset, onClick = { onNewRuleFromPreset(preset) })
                }
            }
        }

        item(key = "sleep-label") {
            SectionLabelItem("Sleep")
        }
        item(key = "sleep-card") {
            // Single card either way, so this section can keep a literal Crossfade.
            Crossfade(
                targetState = rulesState is RulesUiState.Loading,
                modifier = Modifier.padding(horizontal = FlintSpacing.gutter),
                animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
                label = "sleepSection",
            ) { loading ->
                if (loading) {
                    LoadingRows()
                } else {
                    SleepCard(
                        config = sleepConfigFrom(
                            (rulesState as? RulesUiState.Ready)?.rules.orEmpty(),
                        ),
                        onClick = onEditSleep,
                        onToggle = { enabled ->
                            if (enabled) haptics.toggleOn() else haptics.toggleOff()
                            onToggleSleep(enabled)
                        },
                    )
                }
            }
        }

        item(key = "limits-label") {
            SectionLabelItem("Daily app limits")
        }
        when {
            limitRows == null -> item(key = "limits-loading") {
                LoadingRows(
                    modifier = animatedRow()
                        .padding(horizontal = FlintSpacing.gutter)
                        .padding(bottom = FlintSpacing.cardGap),
                )
            }

            limitRows.isEmpty() -> item(key = "limits-empty") {
                EmptyCard(
                    heading = "No app limits yet",
                    message = "Cap how long — or how many times — an app gets used each day.",
                    modifier = animatedRow()
                        .padding(horizontal = FlintSpacing.gutter)
                        .padding(bottom = FlintSpacing.cardGap),
                )
            }

            else -> items(limitRows, key = { it.packageName }) { row ->
                LimitCard(
                    row = row,
                    onClick = { onEditLimit(row.packageName) },
                    modifier = animatedRow()
                        .padding(horizontal = FlintSpacing.gutter)
                        .padding(bottom = FlintSpacing.cardGap),
                )
            }
        }
        item(key = "new-limit") {
            OutlinedButton(
                onClick = onNewLimit,
                modifier = animatedRow()
                    .fillMaxWidth()
                    .padding(horizontal = FlintSpacing.gutter),
            ) {
                Text("New app limit")
            }
        }
    }
}

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
    Column(modifier = modifier.fillMaxWidth()) {
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
private fun RuleCard(
    rule: BlockRule,
    status: RuleLiveStatus?,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Disabled rules drop to the lowest surface + muted title so the Switch state reads at
    // a glance even before the thumb position registers; the colors ease so toggling reads
    // as the card powering down/up, not a repaint.
    val containerColor by animateColorAsState(
        targetValue = if (rule.enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
        label = "ruleCardContainer",
    )
    val titleColor by animateColorAsState(
        targetValue = if (rule.enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
        label = "ruleCardTitle",
    )
    FlintCard(modifier = modifier, onClick = onClick, containerColor = containerColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(FlintSpacing.xs))
                Text(
                    text = targetsSummary(rule.targets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = scheduleSummary(rule.schedule),
                    style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status?.activeNow == true) {
                    Spacer(Modifier.height(FlintSpacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlintStatusDot(MaterialTheme.colorScheme.primary, pulsing = true)
                        Spacer(Modifier.width(FlintSpacing.xs))
                        Text(
                            text = "Active now",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (status?.nextChange != null) {
                    Text(
                        text = status.nextChange,
                        style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintBadge(
                    text = breakLevelLabel(rule.breakLevel),
                    emphasized = rule.breakLevel == BreakLevel.HARDCORE,
                )
            }
            Spacer(Modifier.width(FlintSpacing.cardGap))
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                // TalkBack otherwise announces a bare "On/Off" with no owner.
                modifier = Modifier.semantics { contentDescription = "${rule.name} rule" },
            )
        }
    }
}

@Composable
private fun SleepCard(config: SleepConfig?, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val enabled = config?.enabled == true
    val containerColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
        label = "sleepCardContainer",
    )
    val titleColor by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
        label = "sleepCardTitle",
    )
    FlintCard(onClick = onClick, containerColor = containerColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Sleep mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                )
                Spacer(Modifier.height(FlintSpacing.xs))
                if (config == null) {
                    Text(
                        text = "Not set up — bedtime blocking with an optional morning window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = sleepSummary(config),
                        style = MaterialTheme.typography.bodySmall.merge(FlintNumerals),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(FlintSpacing.sm))
                    FlintBadge(
                        text = when (config.assist) {
                            SleepAssist.WIND_DOWN -> "Wind down"
                            SleepAssist.FULL_ASSIST -> "Full assist"
                        },
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
                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = "Sleep mode rule" },
                )
            }
        }
    }
}

@Composable
private fun LimitCard(row: AppLimitRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlintCard(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.label != null) {
                    Text(
                        text = row.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.xs))
                Text(
                    text = limitSummary(row.timeLimit, row.openLimit),
                    style = MaterialTheme.typography.bodyMedium.merge(FlintNumerals),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(FlintSpacing.sm))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
