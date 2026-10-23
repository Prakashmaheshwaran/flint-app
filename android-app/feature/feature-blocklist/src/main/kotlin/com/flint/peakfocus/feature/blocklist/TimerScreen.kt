package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import java.text.DateFormat
import java.util.Date

/**
 * Dedicated finite Timer: choose apps and duration, then restore the persisted session on
 * every visit. Leaving this screen does not stop it. Existing Flint break tiers still apply.
 */
@Composable
fun TimerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: TimerViewModel = viewModel(factory = remember(context) { TimerViewModel.factory(context) })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                Lifecycle.Event.ON_RESUME -> viewModel.refreshClock()
                Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        viewModel.setVisible(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }

    var pickingApps by rememberSaveable { mutableStateOf(false) }
    var pickingDuration by rememberSaveable { mutableStateOf(false) }
    var cancellingId by remember { mutableStateOf<String?>(null) }
    var viewingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.sessions.isNotEmpty()) {
        if (state.sessions.isNotEmpty()) {
            pickingApps = false
            pickingDuration = false
        }
    }

    if (pickingApps) {
        BackHandler { pickingApps = false }
        AppPickerScreen(
            appsState = state.appsState,
            title = "Choose apps to block",
            eyebrow = "Timer",
            multiSelect = true,
            initiallySelected = state.draft.apps.map { it.packageName }.toSet(),
            onConfirm = { viewModel.selectApps(it); pickingApps = false },
            onCancel = { pickingApps = false },
            modifier = modifier,
        )
    } else {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FlintSpacing.gutter),
                verticalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
            ) {
                FlintScreenHeader("Timer", eyebrow = "Make time for what matters")
                if (state.busy || (!state.rulesLoaded && state.rulesError == null)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.rulesError?.let { message ->
                    TimerNotice(message, isError = true, action = "Refresh", onAction = viewModel::retryRules)
                }
                state.error?.let { message ->
                    TimerNotice(message, isError = true, action = "Dismiss", onAction = viewModel::dismissFeedback)
                }
                state.message?.let { message ->
                    TimerNotice(message, isError = false, action = "Dismiss", onAction = viewModel::dismissFeedback)
                }

                if (state.sessions.isNotEmpty()) {
                    state.sessions.forEach { session ->
                        RunningTimerCard(
                            session = session,
                            nowEpochMs = state.nowEpochMs,
                            enabled = !state.busy && state.rulesError == null,
                            onViewApps = { viewingId = session.id },
                            onCancel = {
                                if (canEndNativeTimer(session)) viewModel.requestExit(session.id)
                                else cancellingId = session.id
                            },
                        )
                    }
                } else if (state.rulesLoaded && state.rulesError == null) {
                    IdleTimer(
                        state = state,
                        onPickApps = { pickingApps = true },
                        onClearApps = viewModel::clearApps,
                        onRetryApps = viewModel::retryApps,
                        onPickDuration = { pickingDuration = true },
                        onPreset = viewModel::selectPreset,
                        onBreakLevel = viewModel::selectBreakLevel,
                        onStart = viewModel::start,
                    )
                } else if (state.rulesError == null) {
                    Text("Loading saved timers…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (pickingDuration && state.canEdit) {
        AlertDialog(
            onDismissRequest = { pickingDuration = false },
            title = { Text("Timer duration") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(timerDurationChoices, key = { it }) { minutes ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                viewModel.selectDuration(minutes)
                                pickingDuration = false
                            }.padding(vertical = FlintSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = state.draft.durationMinutes == minutes, onClick = null)
                            Text(timerDurationLabel(minutes), modifier = Modifier.padding(start = FlintSpacing.sm))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingDuration = false }) { Text("Close") } },
        )
    }

    state.exit?.let { confirmation -> TimerExitDialog(confirmation, viewModel) }
    state.sessions.firstOrNull { it.id == cancellingId && canCancelTimer(it) }?.let { session ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) cancellingId = null },
            title = { Text("End ${session.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
                    Text("This ends only this timer. Other block rules and limits will stay active.")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy && state.rulesError == null,
                    onClick = { viewModel.cancelSession(session.id) },
                ) { Text(if (state.busy) "Ending…" else "End timer") }
            },
            dismissButton = {
                TextButton(enabled = !state.busy, onClick = { cancellingId = null }) { Text("Keep focusing") }
            },
        )
    }
    state.sessions.firstOrNull { it.id == viewingId }?.let { session ->
        AlertDialog(
            onDismissRequest = { viewingId = null },
            title = { Text(if (session.targets.allowListMode) "Allowed apps and sites" else "Blocked apps and sites") },
            text = {
                Column {
                    Text("This selection stays fixed until the timer ends.", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(session.targets.apps.distinctBy { it.packageName }.sortedBy { appLabel(it) }, key = { it.packageName }) { app ->
                            Text(appLabel(app), Modifier.padding(vertical = FlintSpacing.sm))
                        }
                        items(session.targets.domains.sortedBy { it.domain }, key = { "domain:${it.domain}" }) { site ->
                            Text(site.domain, Modifier.padding(vertical = FlintSpacing.sm))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewingId = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun IdleTimer(
    state: TimerUiState,
    onPickApps: () -> Unit,
    onClearApps: () -> Unit,
    onRetryApps: () -> Unit,
    onPickDuration: () -> Unit,
    onPreset: (String) -> Unit,
    onBreakLevel: (BreakLevel) -> Unit,
    onStart: () -> Unit,
) {
    FlintCard {
        Text("Apps to block", style = MaterialTheme.typography.titleMedium)
        Text(
            if (state.draft.apps.isEmpty()) "Choose the apps you want a break from." else appSummary(state.draft.apps),
            style = MaterialTheme.typography.bodyMedium,
        )
        state.appsError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetryApps, enabled = !state.busy) { Text("Retry loading apps") }
        }
        OutlinedButton(
            onClick = onPickApps,
            enabled = state.canEdit && state.appsState is InstalledAppsUiState.Ready,
        ) {
            Text(
                if (state.appsState is InstalledAppsUiState.Loading && state.appsError == null) "Loading apps…"
                else "Choose apps",
            )
        }
        if (state.draft.apps.isNotEmpty()) {
            TextButton(onClick = onClearApps, enabled = state.canEdit) { Text("Clear selection") }
        }
    }
    FlintCard {
        Text("Your session", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onPickDuration, enabled = state.canEdit) {
            Text("Duration · ${timerDurationLabel(state.draft.durationMinutes)}")
        }
        Text("Flint break level", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm),
        ) {
            BreakLevel.entries.forEach { level ->
                FilterChip(
                    selected = state.draft.breakLevel == level,
                    onClick = { onBreakLevel(level) },
                    enabled = state.canEdit,
                    label = { Text(timerBreakLabel(level)) },
                )
            }
        }
        Text(timerBreakDisclosure(state.draft.breakLevel), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "Starting…" else "Start timer")
        }
        if (state.draft.apps.isEmpty()) {
            Text("Select at least one app to start.", style = MaterialTheme.typography.bodySmall)
        }
    }
    FlintSectionLabel("Focus presets")
    PresetRail(state, detox = false, onPreset)
    FlintSectionLabel("Digital detox durations")
    Text(
        "These presets set the duration only. Each uses your selected apps and Flint break level.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PresetRail(state, detox = true, onPreset)
}

@Composable
private fun PresetRail(state: TimerUiState, detox: Boolean, onPreset: (String) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm),
    ) {
        timerPresets.filter { it.detox == detox }.forEach { preset ->
            FilterChip(
                selected = state.draft.presetId == preset.id,
                onClick = { onPreset(preset.id) },
                enabled = state.canEdit,
                label = { Text("${preset.name} · ${timerDurationLabel(preset.minutes)}") },
            )
        }
    }
}

@Composable
private fun RunningTimerCard(
    session: BlockRule,
    nowEpochMs: Long,
    enabled: Boolean,
    onViewApps: () -> Unit,
    onCancel: () -> Unit,
) {
    FlintCard {
        Text(session.name, style = MaterialTheme.typography.titleMedium)
        val deadline = session.expiresAtEpochMs
        if (deadline != null) {
            val remaining = timerCountdown(deadline - nowEpochMs)
            Text(
                remaining,
                style = MaterialTheme.typography.displaySmall.merge(FlintNumerals),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = "$remaining remaining" },
            )
            Text(
                "Ends ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(deadline))}",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("This saved session has no end time.", style = MaterialTheme.typography.bodyMedium)
        }
        Text(timerBreakDisclosure(session.breakLevel, canEndNativeTimer(session)), style = MaterialTheme.typography.bodySmall)
        Text(
            "${session.targets.apps.size} apps · ${session.targets.domains.size} sites" +
                if (session.targets.allowListMode) " allowed" else " blocked",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onViewApps) { Text("View selection") }
        if (canEndNativeTimer(session) || canCancelTimer(session)) {
            OutlinedButton(onClick = onCancel, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("End timer")
            }
        }
        Text("Other rules and limits keep their own settings.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TimerNotice(message: String, isError: Boolean, action: String, onAction: () -> Unit) {
    FlintCard {
        Text(
            message,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onAction) { Text(action) }
    }
}

private fun appLabel(app: AppRef): String = app.label?.takeIf { it.isNotBlank() } ?: app.packageName

private fun appSummary(apps: Set<AppRef>): String {
    val names = apps.sortedBy(::appLabel).take(3).joinToString(", ", transform = ::appLabel)
    return if (apps.size > 3) "$names and ${apps.size - 3} more" else names
}

private fun timerBreakLabel(level: BreakLevel): String = when (level) {
    BreakLevel.EASY -> "Easy"
    BreakLevel.HARDER -> "Harder"
    BreakLevel.HARDCORE -> "Hardcore"
}

private fun timerBreakDisclosure(level: BreakLevel, nativeTimer: Boolean = true): String = when {
    !nativeTimer && level == BreakLevel.EASY -> "Easy — you can end this session at any time."
    !nativeTimer && level == BreakLevel.HARDER -> "Harder — no early stop. Breaks come with friction."
    !nativeTimer && level == BreakLevel.HARDCORE -> "Hardcore — no breaks, no early stop. Your weekly Emergency Pass is the only exit."
    level == BreakLevel.EASY -> "Easy — ordinary breaks are available. Hold to end this timer early."
    level == BreakLevel.HARDER -> "Harder — breaks come with friction. Hold to end this timer early."
    else -> "Hardcore — no ordinary breaks. Hold to end this timer early; other rules stay active."
}
