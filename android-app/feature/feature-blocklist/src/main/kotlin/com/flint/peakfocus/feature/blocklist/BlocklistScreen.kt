package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit

/** Where inside the feature the user currently is. Internal navigation only — no nav graph. */
private sealed interface BlocklistDestination {
    data object Overview : BlocklistDestination

    /** [ruleId] null = author a new rule. */
    data class EditRule(val ruleId: String?) : BlocklistDestination

    /** Author a new rule prefilled from a [RoutinePresets] entry. */
    data class NewRuleFromPreset(val presetId: String) : BlocklistDestination

    /** [packageName] null = pick the app first. */
    data class EditLimit(val packageName: String?) : BlocklistDestination
}

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

    // Editor state (which rule, half-built drafts) intentionally lives in composition, not
    // SavedState — process death drops back to the overview, never to a corrupt half-draft.
    var destination by remember {
        mutableStateOf<BlocklistDestination>(BlocklistDestination.Overview)
    }

    BackHandler(enabled = destination != BlocklistDestination.Overview) {
        destination = BlocklistDestination.Overview
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val dest = destination) {
            is BlocklistDestination.Overview -> BlocklistOverview(
                rulesState = rulesState,
                limitsState = limitsState,
                onNewRule = { destination = BlocklistDestination.EditRule(null) },
                onNewRuleFromPreset = {
                    destination = BlocklistDestination.NewRuleFromPreset(it)
                },
                onEditRule = { destination = BlocklistDestination.EditRule(it) },
                onToggleRule = blocklistViewModel::setRuleEnabled,
                onNewLimit = { destination = BlocklistDestination.EditLimit(null) },
                onEditLimit = { destination = BlocklistDestination.EditLimit(it) },
            )

            is BlocklistDestination.EditRule -> {
                val existing = (rulesState as? RulesUiState.Ready)
                    ?.rules?.firstOrNull { it.id == dest.ruleId }
                RuleEditorScreen(
                    initialDraft = existing?.let(::ruleDraftOf) ?: newRuleDraft(defaultBreakLevel),
                    appsState = appsState,
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

            is BlocklistDestination.NewRuleFromPreset -> RuleEditorScreen(
                // An unknown id can't come from our own chips, but fall back to a blank
                // draft rather than crash — same defensive posture as saveRule's gate.
                initialDraft = RoutinePresets.byId(dest.presetId)?.let(::presetDraft)
                    ?: newRuleDraft(defaultBreakLevel),
                appsState = appsState,
                onSave = { draft ->
                    blocklistViewModel.saveRule(draft)
                    destination = BlocklistDestination.Overview
                },
                onDelete = null,
                onClose = { destination = BlocklistDestination.Overview },
            )

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
        }
    }
}

@Composable
private fun BlocklistOverview(
    rulesState: RulesUiState,
    limitsState: LimitsUiState,
    onNewRule: () -> Unit,
    onNewRuleFromPreset: (String) -> Unit,
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onNewLimit: () -> Unit,
    onEditLimit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            text = "Blocking",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "RULES, SCHEDULES & LIMITS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        SectionLabel("Block rules")
        Spacer(Modifier.height(8.dp))
        when (rulesState) {
            is RulesUiState.Loading -> LoadingRow()
            is RulesUiState.Ready -> if (rulesState.rules.isEmpty()) {
                EmptyCard(
                    "No block rules yet. A rule picks apps or websites and decides when " +
                        "they're off-limits.",
                )
            } else {
                rulesState.rules.forEach { rule ->
                    RuleCard(
                        rule = rule,
                        onClick = { onEditRule(rule.id) },
                        onToggle = { onToggleRule(rule.id, it) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = onNewRule, modifier = Modifier.fillMaxWidth()) {
            Text("New block rule")
        }
        Spacer(Modifier.height(24.dp))

        SectionLabel("Start from a preset")
        Spacer(Modifier.height(8.dp))
        RoutinePresets.ALL.forEach { preset ->
            PresetCard(preset = preset, onClick = { onNewRuleFromPreset(preset.id) })
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("Daily app limits")
        Spacer(Modifier.height(8.dp))
        when (limitsState) {
            is LimitsUiState.Loading -> LoadingRow()
            is LimitsUiState.Ready -> if (limitsState.rows.isEmpty()) {
                EmptyCard(
                    "No app limits yet. Cap how long — or how many times — an app gets " +
                        "used each day.",
                )
            } else {
                limitsState.rows.forEach { row ->
                    LimitCard(row = row, onClick = { onEditLimit(row.packageName) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onNewLimit, modifier = Modifier.fillMaxWidth()) {
            Text("New app limit")
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One tappable preset routine: name, tagline, and the schedule line it would prefill —
 * built with the same [scheduleSummary] the rule cards use, so the wording stays identical.
 */
@Composable
private fun PresetCard(preset: RoutinePreset, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = preset.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = scheduleSummary(
                    Schedule(
                        daysOfWeek = preset.days,
                        startMinuteOfDay = preset.startMinuteOfDay,
                        endMinuteOfDay = preset.endMinuteOfDay,
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            BreakLevelBadge(preset.breakLevel)
        }
    }
}

@Composable
private fun RuleCard(rule: BlockRule, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = targetsSummary(rule.targets),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = scheduleSummary(rule.schedule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                BreakLevelBadge(rule.breakLevel)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun BreakLevelBadge(level: BreakLevel) {
    val hardcore = level == BreakLevel.HARDCORE
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (hardcore) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = breakLevelLabel(level),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (hardcore) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LimitCard(row: AppLimitRow, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = limitSummary(row.timeLimit, row.openLimit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// --- Previews (fake data only; no ViewModel) ---

@Preview(showBackground = true)
@Composable
private fun BlocklistOverviewPreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            BlocklistOverview(
                rulesState = RulesUiState.Ready(
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
                ),
                limitsState = LimitsUiState.Ready(
                    listOf(
                        AppLimitRow(
                            packageName = "com.google.android.youtube",
                            label = "YouTube",
                            timeLimit = TimeLimit("com.google.android.youtube", 45),
                            openLimit = OpenLimit("com.google.android.youtube", 5, BreakLevel.HARDCORE),
                        ),
                    ),
                ),
                onNewRule = {},
                onNewRuleFromPreset = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onNewLimit = {},
                onEditLimit = {},
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
                onNewRuleFromPreset = {},
                onEditRule = {},
                onToggleRule = { _, _ -> },
                onNewLimit = {},
                onEditLimit = {},
            )
        }
    }
}
