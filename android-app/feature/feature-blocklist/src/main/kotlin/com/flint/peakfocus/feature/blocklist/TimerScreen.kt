package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.BoneButton
import com.flint.peakfocus.core.common.ui.EmberButton
import com.flint.peakfocus.core.common.ui.EmberLandscape
import com.flint.peakfocus.core.common.ui.FlintChip
import com.flint.peakfocus.core.common.ui.FlintChipText
import com.flint.peakfocus.core.common.ui.FlintChoiceCard
import com.flint.peakfocus.core.common.ui.FlintCircleButton
import com.flint.peakfocus.core.common.ui.FlintEyebrow
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.FlintMark
import com.flint.peakfocus.core.common.ui.FlintNavPanel
import com.flint.peakfocus.core.common.ui.GhostButton
import com.flint.peakfocus.core.common.ui.QuietButton
import com.flint.peakfocus.core.common.ui.SunArc
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Today: the ember on the horizon, the state of blocking, and the one way into a focus timer.
 * A running timer takes the whole screen (the sun arc); finishing shows "The ember has set."
 * Leaving this screen does not stop a timer. Existing Flint break tiers still apply.
 *
 * [headline]/[subline] describe enforcement (from the app's setup guidance); [setupLabel] and
 * [onSetup] offer the single next setup step when blocking needs one.
 */
@Composable
fun TimerScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    headline: String? = null,
    subline: String? = null,
    setupLabel: String? = null,
    onSetup: () -> Unit = {},
) {
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

    var starting by rememberSaveable { mutableStateOf(false) }
    var pickingApps by rememberSaveable { mutableStateOf(false) }
    var cancellingId by remember { mutableStateOf<String?>(null) }
    var viewingId by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf<BlockRule?>(null) }
    var lastSession by remember { mutableStateOf<BlockRule?>(null) }
    LaunchedEffect(state.sessions) {
        val running = state.sessions.firstOrNull()
        if (running != null) {
            starting = false
            pickingApps = false
            lastSession = running
        } else {
            // A session that ran out its clock (not one ended early) gets its moment.
            val previous = lastSession
            if (previous != null && previous.isExpired(state.nowEpochMs)) finished = previous
            lastSession = null
        }
    }

    val running = state.sessions.firstOrNull()
    when {
        pickingApps -> {
            BackHandler { pickingApps = false }
            TimerAppPicker(
                appsState = state.appsState,
                initiallySelected = state.draft.apps.map { it.packageName }.toSet(),
                onConfirm = { viewModel.selectApps(it); pickingApps = false },
                onCancel = { pickingApps = false },
                modifier = modifier,
            )
        }
        running != null -> SessionScene(
            session = running,
            others = state.sessions.size - 1,
            nowEpochMs = state.nowEpochMs,
            enabled = !state.busy && state.rulesError == null,
            contentPadding = contentPadding,
            onViewApps = { viewingId = running.id },
            onEnd = {
                if (canEndNativeTimer(running)) viewModel.requestExit(running.id) else cancellingId = running.id
            },
            canEnd = canEndNativeTimer(running) || canCancelTimer(running),
            modifier = modifier,
        )
        finished != null -> CompleteScene(
            session = finished!!,
            contentPadding = contentPadding,
            onDone = { finished = null },
            onAgain = { finished = null; starting = true },
            modifier = modifier,
        )
        else -> TodayScene(
            state = state,
            headline = headline,
            subline = subline,
            setupLabel = setupLabel,
            onSetup = onSetup,
            contentPadding = contentPadding,
            onPreset = { id -> viewModel.selectPreset(id); starting = true },
            onStart = { starting = true },
            onRetry = viewModel::retryRules,
            onDismiss = viewModel::dismissFeedback,
            modifier = modifier,
        )
    }

    if (starting && running == null && !pickingApps) {
        StartFocusSheet(
            state = state,
            onDismiss = { starting = false },
            onPickApps = { pickingApps = true },
            onDuration = viewModel::selectDuration,
            onPreset = viewModel::selectPreset,
            onBreakLevel = viewModel::selectBreakLevel,
            onRetryApps = viewModel::retryApps,
            onStart = viewModel::start,
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
            title = { Text(if (session.targets.allowListMode) "Allowed apps and sites" else "Resting apps and sites") },
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

/**
 * The timer's "What rests" picker. [initiallySelected] is the confirmed draft; the screen's
 * header, footer and Add label all derive from the pending selection, so this wrapper must not
 * supply a selection count as [AppPickerScreen.eyebrow].
 */
@Composable
internal fun TimerAppPicker(
    appsState: InstalledAppsUiState,
    initiallySelected: Set<String>,
    onConfirm: (List<InstalledApp>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppPickerScreen(
        appsState = appsState,
        title = "What rests",
        multiSelect = true,
        initiallySelected = initiallySelected,
        onConfirm = onConfirm,
        onCancel = onCancel,
        modifier = modifier.statusBarsPadding(),
    )
}

/** E03 — Today. */
@Composable
private fun TodayScene(
    state: TimerUiState,
    headline: String?,
    subline: String?,
    setupLabel: String?,
    onSetup: () -> Unit,
    contentPadding: PaddingValues,
    onPreset: (String) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize()) {
        EmberLandscape()
        BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = FlintSpacing.gutter),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        FlintEyebrow(remember { todayLabel() }, Modifier.weight(1f))
                        FlintMark(Modifier.size(20.dp))
                    }
                    Text(
                        (headline ?: "Make time for what matters").let { if (it.endsWith(".")) it else "$it." },
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize * (31f / 30f)),
                        modifier = Modifier.padding(top = 26.dp),
                    )
                    if (subline != null) {
                        Text(
                            subline,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize * (14f / 13f)),
                            color = FlintPalette.Mist,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth().padding(top = 64.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(timerDurationLabel(state.draft.durationMinutes), style = MaterialTheme.typography.displaySmall.merge(FlintNumerals))
                        FlintEyebrow("Your next session")
                    }
                }

                Column(Modifier.padding(top = 32.dp)) {
                    if (state.busy || (!state.rulesLoaded && state.rulesError == null)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = FlintPalette.Ember, trackColor = FlintPalette.BoneFill)
                    }
                    val notice = state.rulesError ?: state.error ?: state.message
                    if (notice != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(notice, style = MaterialTheme.typography.bodySmall,
                                color = if (state.message != null && state.error == null && state.rulesError == null) FlintPalette.Mist else FlintPalette.Danger,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = if (state.rulesError != null) onRetry else onDismiss) {
                                Text(if (state.rulesError != null) "Refresh" else "Dismiss", color = FlintPalette.Bone)
                            }
                        }
                    }
                    if (setupLabel != null) {
                        QuietButton(setupLabel, onSetup, outlined = true, color = FlintPalette.Ember, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        todayPresets.forEach { preset ->
                            FlintChip(onClick = { onPreset(preset.id) }, enabled = state.canEdit) {
                                FlintChipText(preset.name)
                                FlintChipText(timerDurationLabel(preset.minutes), color = FlintPalette.Mist)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    EmberButton("Start focus", onStart, enabled = state.rulesLoaded && state.rulesError == null)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** E04 — the Start focus sheet: how long, what rests, how hard it is to leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartFocusSheet(
    state: TimerUiState,
    onDismiss: () -> Unit,
    onPickApps: () -> Unit,
    onDuration: (Int) -> Unit,
    onPreset: (String) -> Unit,
    onBreakLevel: (BreakLevel) -> Unit,
    onRetryApps: () -> Unit,
    onStart: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // This dialog is outside the tab scaffold's viewport. After returning from
        // search with the IME open it can expand to the top, so reserve its own
        // status-bar inset rather than placing the header/Close under system UI.
        modifier = Modifier.statusBarsPadding(),
        sheetState = sheetState,
        containerColor = FlintPalette.Sheet,
        contentColor = FlintPalette.Bone,
        scrimColor = Color(0xFF07080B).copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(40.dp, 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(FlintPalette.Bone.copy(alpha = 0.2f)),
            )
        },
    ) {
        val minutes = state.draft.durationMinutes
        val choices = timerDurationChoices
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FlintSpacing.gutter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Start focus", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                FlintCircleButton(onClick = onDismiss, contentDescription = "Close", size = 44.dp, filled = true) {
                    Icon(FlintGlyphs.close, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                FlintCircleButton(
                    onClick = { choices.lastOrNull { it < minutes }?.let(onDuration) },
                    contentDescription = "Less time",
                    enabled = state.canEdit && choices.any { it < minutes },
                ) { Icon(FlintGlyphs.minus, contentDescription = null, modifier = Modifier.size(18.dp)) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                    val big = if (minutes < 60 || minutes % 60 != 0) minutes.toString() else (minutes / 60).toString()
                    val unit = if (minutes < 60 || minutes % 60 != 0) "min" else if (minutes == 60) "hour" else "hours"
                    Text(if (minutes >= 60 && minutes % 60 != 0) timerDurationLabel(minutes) else big,
                        style = MaterialTheme.typography.displayMedium.merge(FlintNumerals))
                    if (!(minutes >= 60 && minutes % 60 != 0)) {
                        Text(unit, style = MaterialTheme.typography.bodyMedium, color = FlintPalette.Mist,
                            modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
                    }
                }
                FlintCircleButton(
                    onClick = { choices.firstOrNull { it > minutes }?.let(onDuration) },
                    contentDescription = "More time",
                    enabled = state.canEdit && choices.any { it > minutes },
                ) { Icon(FlintGlyphs.plus, contentDescription = null, modifier = Modifier.size(18.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 45, 60, 90).forEach { choice ->
                    FlintChip(
                        onClick = { onDuration(choice) },
                        selected = minutes == choice,
                        enabled = state.canEdit,
                        height = 44.dp,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "$choice minutes" },
                    ) { FlintChipText(choice.toString()) }
                }
            }

            FlintNavPanel(
                title = if (state.draft.apps.isEmpty()) "Choose what rests" else "Block ${state.draft.apps.size} ${if (state.draft.apps.size == 1) "app" else "apps"}",
                subtitle = when {
                    state.appsState is InstalledAppsUiState.Loading && state.appsError == null -> "Loading apps…"
                    state.draft.apps.isEmpty() -> "Pick the apps you want a break from"
                    else -> appSummary(state.draft.apps)
                },
                onClick = onPickApps,
                enabled = state.canEdit && state.appsState is InstalledAppsUiState.Ready,
                modifier = Modifier.padding(top = 28.dp),
            )
            state.appsError?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = FlintPalette.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetryApps, enabled = !state.busy) { Text("Retry", color = FlintPalette.Bone) }
                }
            }

            FlintEyebrow("Break level", Modifier.padding(top = 30.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp).selectableGroup()) {
                // Measure the actual scaled type, including Android's nonlinear font scaling.
                // Keep the three cards only while every complete level name fits its column.
                val measurer = rememberTextMeasurer()
                val titleStyle = MaterialTheme.typography.titleSmall
                val widestTitle = BreakLevel.entries.maxOf { level ->
                    measurer.measure(AnnotatedString(timerBreakLabel(level)), titleStyle, softWrap = false).size.width
                }
                val cardWidth = with(LocalDensity.current) { widestTitle.toDp() } + 24.dp + 1.dp
                val card: @Composable (BreakLevel, Modifier) -> Unit = { level, cardModifier ->
                    FlintChoiceCard(
                        title = timerBreakLabel(level),
                        hint = timerBreakHint(level),
                        selected = state.draft.breakLevel == level,
                        onClick = { onBreakLevel(level) },
                        enabled = state.canEdit,
                        modifier = cardModifier,
                    )
                }
                if (maxWidth >= cardWidth * BreakLevel.entries.size + 16.dp) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BreakLevel.entries.forEach { level -> card(level, Modifier.weight(1f).fillMaxHeight()) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BreakLevel.entries.forEach { level -> card(level, Modifier.fillMaxWidth()) }
                    }
                }
            }
            Text(
                timerBreakDisclosure(state.draft.breakLevel),
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
                modifier = Modifier.padding(top = 12.dp).heightIn(min = 38.dp),
            )

            FlintEyebrow("Longer breaks", Modifier.padding(top = 18.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                timerPresets.filter { it.detox }.forEach { preset ->
                    FlintChip(onClick = { onPreset(preset.id) }, selected = state.draft.presetId == preset.id,
                        enabled = state.canEdit, height = 36.dp) {
                        FlintChipText("${preset.name} · ${timerDurationLabel(preset.minutes)}")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            EmberButton(
                if (state.busy) "Starting…" else "Begin ${timerDurationLabel(minutes)}",
                onStart,
                enabled = state.canStart,
            )
            val hint = state.error ?: if (state.draft.apps.isEmpty()) "Choose what rests first." else null
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) FlintPalette.Danger else FlintPalette.Mist,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

/** E06 — a running timer: the ember travels its arc, breath rings around it. */
@Composable
private fun SessionScene(
    session: BlockRule,
    others: Int,
    nowEpochMs: Long,
    enabled: Boolean,
    canEnd: Boolean,
    contentPadding: PaddingValues,
    onViewApps: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    val deadline = session.expiresAtEpochMs
    val start = session.createdAtEpochMsOrNull()
    val progress = if (deadline != null && start != null && deadline > start) {
        ((nowEpochMs - start).toFloat() / (deadline - start)).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(modifier.fillMaxSize()) {
        EmberLandscape(horizonOffset = 8f, orbCenter = null)
        SunArc(progress)
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = FlintSpacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                FlintEyebrow(session.name, Modifier.weight(1f))
                TextButton(onClick = onViewApps) {
                    FlintEyebrow("${session.targets.apps.size} ${if (session.targets.apps.size == 1) "app" else "apps"} " +
                        if (session.targets.allowListMode) "open" else "resting")
                }
            }
            if (others > 0) FlintEyebrow("+$others more running", color = FlintPalette.Stone)
            if (deadline != null) {
                val remaining = timerCountdown(deadline - nowEpochMs)
                Text(
                    remaining,
                    style = MaterialTheme.typography.displayLarge.merge(FlintNumerals),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 26.dp).semantics { contentDescription = "$remaining remaining" },
                )
                Text(
                    "ends at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(deadline))}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize * (14f / 13f)),
                    color = FlintPalette.Mist,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Text("No end time", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 40.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Breathe with the light.", style = MaterialTheme.typography.titleLarge.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize * (17f / 22f)),
                color = FlintPalette.SoftBone, modifier = Modifier.padding(bottom = 64.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background28()
                    .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(FlintGlyphs.lock, contentDescription = null, tint = FlintPalette.Mist, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(timerBreakLabel(session.breakLevel), style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize * (14f / 15f)))
                    Text(if (canEndNativeTimer(session)) "Hold to end early" else timerBreakHint(session.breakLevel),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize * (12f / 13f)),
                        color = FlintPalette.Mist)
                }
                if (canEnd) GhostButton("End session", onEnd, enabled = enabled)
            }
        }
    }
}

/** E08 — the ember has set. */
@Composable
private fun CompleteScene(
    session: BlockRule,
    contentPadding: PaddingValues,
    onDone: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier,
) {
    val minutes = session.createdAtEpochMsOrNull()?.let { start ->
        session.expiresAtEpochMs?.let { ((it - start) / 60_000L).toInt() }
    }
    Box(modifier.fillMaxSize()) {
        EmberLandscape(horizonOffset = 8f, orbCenter = null, showsMist = false)
        SunArc(1f, complete = true)
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = FlintSpacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlintEyebrow("${session.name} · Complete", Modifier.padding(top = 52.dp))
            Text("The ember has set.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp))
            if (minutes != null && minutes > 0) {
                Text("${timerDurationLabel(minutes)}, start to finish.", style = MaterialTheme.typography.bodyMedium,
                    color = FlintPalette.Mist, modifier = Modifier.padding(top = 10.dp))
            }
            Spacer(Modifier.weight(1f))
            BoneButton("Done", onDone)
            QuietButton("Start another", onAgain, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        }
    }
}

private val todayPresets: List<TimerPreset>
    get() = listOf("deep-study", "reading", "workout").mapNotNull { id -> timerPresets.firstOrNull { it.id == id } }

private fun todayLabel(): String =
    SimpleDateFormat("EEEE · d MMMM", Locale.getDefault()).format(Date())

/** Timer rule ids carry no start time, so the arc derives it from the persisted rule when it can. */
private fun BlockRule.createdAtEpochMsOrNull(): Long? = timerStartEpochMs(this)

/** The session's floating control bar: 28dp capsule, translucent night, bone hairline. */
private fun Modifier.background28(): Modifier = this
    .clip(RoundedCornerShape(28.dp))
    .background(Color(0xE0181618))
    .border(1.dp, FlintPalette.Divider, RoundedCornerShape(28.dp))

private fun appLabel(app: AppRef): String = app.label?.takeIf { it.isNotBlank() } ?: app.packageName

private fun appSummary(apps: Set<AppRef>): String {
    val names = apps.sortedBy(::appLabel).take(3).joinToString(", ", transform = ::appLabel)
    return if (apps.size > 3) "$names and more" else names
}

private fun timerBreakLabel(level: BreakLevel): String = when (level) {
    BreakLevel.EASY -> "Easy"
    BreakLevel.HARDER -> "Harder"
    BreakLevel.HARDCORE -> "Hardcore"
}

private fun timerBreakHint(level: BreakLevel): String = when (level) {
    BreakLevel.EASY -> "Breaks any time"
    BreakLevel.HARDER -> "Breaks take friction"
    BreakLevel.HARDCORE -> "No breaks"
}

private fun timerBreakDisclosure(level: BreakLevel): String = when (level) {
    BreakLevel.EASY -> "Ordinary breaks are available. Hold to end this timer early."
    BreakLevel.HARDER -> "Each break waits a few minutes first. Hold to end this timer early."
    BreakLevel.HARDCORE -> "No ordinary breaks. Hold to end this timer early; other rules stay active."
}
