package com.flint.peakfocus.feature.blocklist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import com.flint.peakfocus.core.common.ui.FlintAppTile
import com.flint.peakfocus.core.common.ui.FlintChip
import com.flint.peakfocus.core.common.ui.FlintChipText
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintChoiceCard
import com.flint.peakfocus.core.common.ui.FlintCircleButton
import com.flint.peakfocus.core.common.ui.FlintEyebrow
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.FlintInfoPill
import com.flint.peakfocus.core.common.ui.FlintMark
import com.flint.peakfocus.core.common.ui.FlintNavPanel
import com.flint.peakfocus.core.common.ui.FlintRadioCard
import com.flint.peakfocus.core.common.ui.FlintSectionTitle
import com.flint.peakfocus.core.common.ui.FlintStatusPill
import com.flint.peakfocus.core.common.ui.GhostButton
import com.flint.peakfocus.core.common.ui.QuietButton
import com.flint.peakfocus.core.common.ui.SunArc
import com.flint.peakfocus.core.common.ui.TodayHero
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.history.FocusHistoryText
import com.flint.peakfocus.core.history.FocusSession
import com.flint.peakfocus.core.history.FocusSummary
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
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
    onOpenRules: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: TimerViewModel = viewModel(factory = remember(context) { TimerViewModel.factory(context) })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val historyViewModel: TimerHistoryViewModel = viewModel(factory = remember(context) { TimerHistoryViewModel.factory(context) })
    val history by historyViewModel.state.collectAsStateWithLifecycle()
    // Read-only rule projection for Today's "Now", "Up next" and protection pill. The same
    // Activity-scoped view model serves the Rules tab, so this adds no second store reader.
    val blocklistViewModel: BlocklistViewModel =
        viewModel(factory = remember(context) { BlocklistViewModel.factory(context) })
    val rulesState by blocklistViewModel.rules.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                Lifecycle.Event.ON_RESUME -> { viewModel.refreshClock(); viewModel.refreshHealth() }
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
    LaunchedEffect(state.sessions) {
        val running = state.sessions.firstOrNull()
        if (running != null) {
            starting = false
            pickingApps = false
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
            arming = state.arming,
            onRetryProtection = viewModel::retryArming,
            onSetup = onSetup,
            modifier = modifier,
            setupAvailable = setupLabel != null,
        )
        history.completion != null -> CompleteScene(
            session = history.completion!!,
            summary = history.summary,
            contentPadding = contentPadding,
            onDone = { historyViewModel.dismissCompletion(history.completion!!.id) },
            onAgain = { historyViewModel.dismissCompletion(history.completion!!.id); starting = true },
            modifier = modifier,
        )
        else -> TodayScene(
            state = state,
            history = history.summary,
            headline = headline,
            subline = subline,
            setupLabel = setupLabel,
            onSetup = onSetup,
            rulesState = rulesState,
            onOpenRules = onOpenRules,
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
            onRetryProtection = viewModel::retryStartProtection,
            onSetup = onSetup,
            setupAvailable = setupLabel != null,
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
            title = { Text("Selected apps and sites") },
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

/** M03 — Today. */
@Composable
private fun TodayScene(
    state: TimerUiState,
    history: FocusSummary?,
    headline: String?,
    subline: String?,
    setupLabel: String?,
    onSetup: () -> Unit,
    rulesState: RulesUiState,
    onOpenRules: () -> Unit,
    contentPadding: PaddingValues,
    onPreset: (String) -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val nowMinute = localMinuteOfDay(state.nowEpochMs)
    val ready = rulesState as? RulesUiState.Ready
    val authored = ready?.rules.orEmpty().filter {
        it.enabled &&
            !it.id.startsWith(BlockRule.SESSION_ID_PREFIX) &&
            !it.id.startsWith(SLEEP_RULE_PREFIX)
    }
    val statuses = ready?.statuses.orEmpty()
    val active = authored.firstOrNull { statuses[it.id]?.activeNow == true }
    // A one-time rule belongs to Today only on its own date (never a later date's start time).
    val today = java.time.Instant.ofEpochMilli(state.nowEpochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val upNext = authored
        .filter { statuses[it.id]?.nextChange?.startsWith("Starts") == true }
        .filter { rule -> rule.schedule?.date?.let { it == today } ?: true }
        .sortedBy { it.schedule?.startMinuteOfDay ?: 0 }
        .take(2)
    val nextLabel = upNext.firstOrNull()?.let { rule ->
        // A dated one-time start names its date; only a weekly window can imply "next at HH:MM".
        if (rule.schedule?.date != null) statuses[rule.id]?.nextChange
        else rule.schedule?.let { "Starts ${formatMinuteOfDay(it.startMinuteOfDay)}" }
            ?: statuses[rule.id]?.nextChange
    }?.removePrefix("Starts ")

    Box(modifier.fillMaxSize().background(FlintPalette.Ground)) {
        TodayHero(Modifier.align(Alignment.TopCenter))
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlintMark(Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Flint",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 20.sp,
                        letterSpacing = (-0.3).sp,
                    ),
                    modifier = Modifier.weight(1f),
                )
                // Fail closed: only a loaded rule set may claim "Protected · N rules".
                val pillText = when {
                    setupLabel != null -> "Setup needed"
                    rulesState is RulesUiState.Unavailable -> "Rules unavailable"
                    ready == null -> "Checking rules…"
                    else -> "Protected · ${authored.size} ${if (authored.size == 1) "rule" else "rules"}"
                }
                val healthy = setupLabel == null && ready != null
                FlintStatusPill(
                    text = pillText,
                    emphasized = healthy,
                    dot = healthy,
                    modifier = Modifier.clip(RoundedCornerShape(percent = 50)).clickable(onClick = onOpenRules),
                )
            }

            Text(
                "Spark your life.",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 30.sp,
                    fontFamily = com.flint.peakfocus.core.common.theme.FlintDisplay,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                    letterSpacing = (-0.3).sp,
                ),
                textAlign = TextAlign.Center,
                // The hero draws from the screen top (board coordinates: spark ends at 222dp,
                // tagline at 240dp); this sits below the status bar, so leave the spark room.
                modifier = Modifier.fillMaxWidth().padding(top = 136.dp),
            )
            Spacer(Modifier.height(20.dp))
            EmberButton(
                "Start focus",
                onClick = onStart,
                enabled = state.rulesLoaded && state.rulesError == null,
                minHeight = 52.dp,
                leadingIcon = FlintGlyphs.timer,
            )
            Spacer(Modifier.height(20.dp))
            TodayStatsRow(
                focused = FocusHistoryText.today(history),
                next = nextLabel,
                // Allow-list rules list the apps left open, so they are never "resting".
                restingApps = active?.targets?.takeUnless { it.allowListMode }?.apps?.toList().orEmpty(),
            )

            if (setupLabel != null || (headline != null && state.rulesError != null)) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(FlintPalette.BoneFaint)
                        .padding(14.dp),
                ) {
                    Text(
                        (headline ?: "Finish setup").let { if (it.endsWith(".")) it else "$it." },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (subline != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(subline, style = MaterialTheme.typography.bodySmall, color = FlintPalette.Mist)
                    }
                    if (setupLabel != null) {
                        Spacer(Modifier.height(10.dp))
                        QuietButton(setupLabel, onSetup, outlined = true, color = FlintPalette.EmberText)
                    }
                }
            }

            if (state.busy || (!state.rulesLoaded && state.rulesError == null)) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    color = FlintPalette.Ember,
                    trackColor = FlintPalette.BoneFill,
                )
            }
            val notice = state.rulesError ?: state.error ?: state.message
            if (notice != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.message != null && state.error == null && state.rulesError == null) FlintPalette.Mist else FlintPalette.Danger,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = if (state.rulesError != null) onRetry else onDismiss) {
                        Text(if (state.rulesError != null) "Refresh" else "Dismiss", color = FlintPalette.Bone)
                    }
                }
            }

            if (active != null) {
                Spacer(Modifier.height(16.dp))
                TodayNowCard(
                    rule = active,
                    remainingMinutes = scheduleMinutesLeft(active.schedule, nowMinute),
                    progress = scheduleProgress(active.schedule, nowMinute),
                    nowMinute = nowMinute,
                    onOpenRules = onOpenRules,
                )
            }

            if (upNext.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Up next", style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
                    Text(
                        "All rules",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = FlintPalette.Mist,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onOpenRules)
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                upNext.forEachIndexed { index, rule ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    TodayNextRow(rule = rule, nowMinute = nowMinute, onClick = onOpenRules)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Your next session · ${timerDurationLabel(state.draft.durationMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
            )
            Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** M03's three-stat row: two serif numbers plus the resting app tiles. */
@Composable
private fun TodayStatsRow(focused: String, next: String?, restingApps: List<AppRef>) {
    // Values may wrap at large font scales; each column spans the row so labels stay on one baseline.
    Row(Modifier.fillMaxWidth().padding(top = 2.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StatValue(focused)
            Spacer(Modifier.height(5.dp))
            StatLabel("FOCUSED")
        }
        Box(Modifier.width(1.dp).height(34.dp).background(FlintPalette.Divider))
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StatValue(next ?: "—")
            Spacer(Modifier.height(5.dp))
            StatLabel("NEXT")
        }
        Box(Modifier.width(1.dp).height(34.dp).background(FlintPalette.Divider))
        Column(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (restingApps.isEmpty()) {
                Text("—", style = MaterialTheme.typography.titleLarge.merge(FlintNumerals))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    restingApps.take(3).forEachIndexed { index, app ->
                        FlintAppTile(
                            packageName = app.packageName,
                            label = app.label,
                            size = 22.dp,
                            cornerRadius = 7.dp,
                            resting = true,
                            modifier = Modifier
                                .offset(x = (-5 * index).dp)
                                .zIndex((3 - index).toFloat()),
                        )
                    }
                    if (restingApps.size > 3) {
                        Text(
                            "+${restingApps.size - 3}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = FlintPalette.Mist,
                            modifier = Modifier.padding(start = 5.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            StatLabel("RESTING")
        }
    }
}

/**
 * A stat value: short values ("1h 30m", "9:00 AM", "—") use the serif numeral; longer history
 * states ("No focus sessions yet", "Focus history unavailable") are sentences, so they wrap in
 * body text instead of being clipped to one numeral line. Neither form is ever clipped.
 */
@Composable
private fun StatValue(text: String) {
    if (text.length <= STAT_NUMERAL_MAX_CHARS) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.merge(FlintNumerals),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    } else {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = FlintPalette.Mist,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private const val STAT_NUMERAL_MAX_CHARS = 8

@Composable
private fun StatLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp,
            letterSpacing = 1.6.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        ),
        color = FlintPalette.Mist,
        maxLines = 1,
    )
}

/** M03's "Now" rule card: ember wash border, live pill, window progress and resting tiles. */
@Composable
private fun TodayNowCard(
    rule: BlockRule,
    remainingMinutes: Int?,
    progress: Float?,
    nowMinute: Int,
    onOpenRules: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(FlintPalette.Card)
            .border(1.dp, FlintPalette.Ember.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(FlintPalette.EmberChip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(FlintGlyphs.lock, contentDescription = null, tint = FlintPalette.Ember, modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${rule.name} · now",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    nowCardSubtitle(rule, nowMinute),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = FlintPalette.Mist,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (remainingMinutes != null) {
                FlintInfoPill("${shortDuration(remainingMinutes)} left")
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(FlintPalette.Bone.copy(alpha = 0.1f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((progress ?: 0f).coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(FlintPalette.Ember),
            )
        }
        val apps = rule.targets.takeUnless { it.allowListMode }?.apps?.toList().orEmpty()
        if (apps.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                apps.take(5).forEach { app ->
                    FlintAppTile(
                        packageName = app.packageName,
                        label = app.label,
                        size = 36.dp,
                        cornerRadius = 11.dp,
                        resting = true,
                    )
                }
                if (apps.size > 5) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(FlintPalette.BoneFill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+${apps.size - 5}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = FlintPalette.Mist,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenRules)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("View", style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp))
                    Icon(FlintGlyphs.chevronRight, contentDescription = null, tint = FlintPalette.Bone, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** M03's "Up next" row: calendar/moon glyph, rule name + window, relative start. */
@Composable
private fun TodayNextRow(rule: BlockRule, nowMinute: Int, onClick: () -> Unit) {
    val start = rule.schedule?.startMinuteOfDay
    // "in 5h" is weekday math: a dated one-time start can be any distance ahead, so its row
    // names the date in the summary line instead of guessing a relative time.
    val relative = if (rule.schedule?.date != null) null else start?.let { minutesUntil(it, nowMinute) }?.let(::relativeStartLabel)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(FlintPalette.BoneFaint.copy(alpha = 0.7f))
            .border(1.dp, FlintPalette.Hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val startMinute = rule.schedule?.startMinuteOfDay
        Icon(
            if (startMinute == null || startMinute >= 18 * 60) FlintGlyphs.moon else FlintGlyphs.calendar,
            contentDescription = null,
            tint = FlintPalette.Mist,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(rule.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${scheduleSummary(rule.schedule)} · ${timerBreakLabel(rule.breakLevel)} · ${targetsSummary(rule.targets)}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = FlintPalette.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (relative != null) {
            Text(
                relative,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = FlintPalette.Mist,
                maxLines = 1,
            )
        }
    }
}

/** E04 — the Start focus sheet: how long, what rests, how hard it is to leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartFocusSheet(
    state: TimerUiState,
    onDismiss: () -> Unit,
    onPickApps: () -> Unit,
    onDuration: (Int) -> Unit,
    onPreset: (String) -> Unit,
    onBreakLevel: (BreakLevel) -> Unit,
    onRetryApps: () -> Unit,
    onStart: () -> Unit,
    onRetryProtection: () -> Unit,
    onSetup: () -> Unit,
    setupAvailable: Boolean = true,
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
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Start focus", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                FlintCircleButton(onClick = onDismiss, contentDescription = "Close", size = 44.dp, filled = true) {
                    Icon(FlintGlyphs.close, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                FlintCircleButton(
                    onClick = { choices.lastOrNull { it < minutes }?.let(onDuration) },
                    contentDescription = "Less time",
                    size = 52.dp,
                    filled = false,
                    enabled = state.canEdit && choices.any { it < minutes },
                ) { Icon(FlintGlyphs.minus, contentDescription = null, modifier = Modifier.size(20.dp)) }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val big = if (minutes < 60 || minutes % 60 != 0) minutes.toString() else (minutes / 60).toString()
                    Text(
                        if (minutes >= 60 && minutes % 60 != 0) timerDurationLabel(minutes) else big,
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 92.sp).merge(FlintNumerals),
                        maxLines = 1,
                    )
                    if (!(minutes >= 60 && minutes % 60 != 0)) {
                        FlintEyebrow("Minutes")
                    }
                }
                FlintCircleButton(
                    onClick = { choices.firstOrNull { it > minutes }?.let(onDuration) },
                    contentDescription = "More time",
                    size = 52.dp,
                    enabled = state.canEdit && choices.any { it > minutes },
                ) { Icon(FlintGlyphs.plus, contentDescription = null, modifier = Modifier.size(20.dp)) }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                timerPresets.filterNot { it.detox }.forEach { preset ->
                    val selected = state.draft.presetId == preset.id && minutes == preset.minutes
                    FlintChip(
                        onClick = { onPreset(preset.id) },
                        selected = selected,
                        enabled = state.canEdit,
                        height = 38.dp,
                    ) {
                        FlintChipText(preset.name)
                        FlintChipText(timerDurationLabel(preset.minutes), color = if (selected) FlintPalette.Mist else FlintPalette.Mist)
                    }
                }
            }

            val draftApps = state.draft.apps.toList()
            FlintCard(
                modifier = Modifier.padding(top = 20.dp),
                onClick = if (state.canEdit && state.appsState is InstalledAppsUiState.Ready) onPickApps else null,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (draftApps.isEmpty()) "Choose what rests"
                            else "${draftApps.size} ${if (draftApps.size == 1) "app" else "apps"} resting",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            when {
                                state.appsState is InstalledAppsUiState.Loading && state.appsError == null -> "Loading apps…"
                                draftApps.isEmpty() -> "Tap to choose the apps you want a break from"
                                else -> "Tap to change apps"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = FlintPalette.Mist,
                        )
                    }
                    Icon(FlintGlyphs.chevronRight, contentDescription = null, tint = FlintPalette.Mist, modifier = Modifier.size(16.dp))
                }
                if (draftApps.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        draftApps.take(6).forEach { app ->
                            FlintAppTile(packageName = app.packageName, label = app.label, size = 36.dp, cornerRadius = 11.dp)
                        }
                        if (draftApps.size > 6) {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(FlintPalette.BoneFill),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "+${draftApps.size - 6}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                    color = FlintPalette.Mist,
                                )
                            }
                        }
                    }
                }
            }
            state.appsError?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = FlintPalette.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetryApps, enabled = !state.busy) { Text("Retry", color = FlintPalette.Bone) }
                }
            }

            FlintSectionTitle("Break level", Modifier.padding(top = 22.dp))
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BreakLevel.entries.forEach { level ->
                    FlintRadioCard(
                        title = timerBreakLabel(level),
                        hint = timerBreakHint(level),
                        selected = state.draft.breakLevel == level,
                        onClick = { onBreakLevel(level) },
                        enabled = state.canEdit,
                        tag = when (level) {
                            BreakLevel.EASY -> "Flexible"
                            BreakLevel.HARDER -> "Recommended"
                            BreakLevel.HARDCORE -> "Free in Flint"
                        },
                    )
                }
            }
            Text(
                timerBreakDisclosure(state.draft.breakLevel),
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
                modifier = Modifier.padding(top = 10.dp).heightIn(min = 38.dp),
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
        }
        // Keep refusal/recovery together with Begin, outside the scrolling editor.
        // A newly inserted error must never fall below the visible viewport.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = FlintSpacing.gutter)
                .navigationBarsPadding().padding(top = 8.dp, bottom = 16.dp),
        ) {
            val reason = state.error ?: state.startPrerequisite
            if (reason != null) {
                val announcement = if (reason == "Checking protection…") reason else "Not armed. $reason"
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announcement
                }) {
                    if (reason != "Checking protection…") {
                        Text("Not armed.", style = MaterialTheme.typography.titleSmall, color = FlintPalette.Danger)
                    }
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = FlintPalette.Mist)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onRetryProtection, enabled = !state.busy) { Text("Retry") }
                    if (setupAvailable) TextButton(onClick = onSetup, enabled = !state.busy) { Text("Check setup") }
                }
            }
            EmberButton(
                if (state.busy) "Starting…" else "Begin ${timerDurationLabel(minutes)}",
                onStart,
                enabled = state.canStart && state.startPrerequisite == null,
            )
            val hint = if (state.draft.apps.isEmpty()) "Choose what rests first." else null
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
internal fun SessionScene(
    session: BlockRule,
    others: Int,
    nowEpochMs: Long,
    enabled: Boolean,
    canEnd: Boolean,
    contentPadding: PaddingValues,
    onViewApps: () -> Unit,
    onEnd: () -> Unit,
    arming: TimerArmingState,
    onRetryProtection: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier,
    setupAvailable: Boolean = true,
) {
    val deadline = session.expiresAtEpochMs
    val start = session.createdAtEpochMsOrNull()
    val armed = arming is TimerArmingState.Armed && arming.ruleId == session.id
    val progress = if (deadline != null && start != null && deadline > start) {
        ((nowEpochMs - start).toFloat() / (deadline - start)).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(modifier.fillMaxSize()) {
        EmberLandscape(horizonOffset = 8f, orbCenter = null)
        SunArc(progress, Modifier.alpha(if (armed) 1f else 0.3f))
        Column(
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = FlintSpacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                FlintStatusPill(text = session.name, emphasized = true, dot = true)
                Spacer(Modifier.weight(1f))
                FlintStatusPill(
                    text = timerBreakLabel(session.breakLevel),
                    emphasized = session.breakLevel == BreakLevel.HARDCORE,
                )
            }
            if (others > 0) {
                Spacer(Modifier.height(6.dp))
                FlintEyebrow("+$others more configured", color = FlintPalette.Stone)
            }
            TimerArmingNotice(arming, onRetryProtection, onSetup, enabled, setupAvailable)
            if (deadline != null) {
                val remaining = timerCountdown(deadline - nowEpochMs)
                if (!armed) Text("Saved timer plan", style = MaterialTheme.typography.bodySmall, color = FlintPalette.Mist,
                    modifier = Modifier.padding(top = 18.dp))
                Text(
                    remaining,
                    color = if (armed) FlintPalette.Bone else FlintPalette.Mist,
                    style = MaterialTheme.typography.displayLarge.merge(FlintNumerals),
                    maxLines = 1,
                    modifier = Modifier.padding(top = if (armed) 22.dp else 8.dp).semantics {
                        contentDescription = if (armed) "$remaining remaining" else "$remaining remaining in saved plan"
                    },
                )
                Text(
                    "ends at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(deadline))}" +
                        (start?.let { " · ${sessionLengthLabel(it, deadline)} session" } ?: ""),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                    color = FlintPalette.Mist,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Text("No end time", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 40.dp))
            }
            Spacer(Modifier.weight(1f))
            if (armed) {
                Text(
                    "Breathe with the light.",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 17.sp),
                    color = FlintPalette.SoftBone,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
            SessionRestCard(
                session = session,
                armed = armed,
                canEnd = canEnd,
                enabled = enabled,
                onEnd = onEnd,
                onViewApps = onViewApps,
            )
        }
    }
}

/** M06's floating rest card: resting tiles, the lock line and the one exit affordance. */
@Composable
private fun SessionRestCard(
    session: BlockRule,
    armed: Boolean,
    canEnd: Boolean,
    enabled: Boolean,
    onEnd: () -> Unit,
    onViewApps: () -> Unit,
) {
    val apps = session.targets.apps.toList()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(FlintPalette.Card.copy(alpha = 0.92f))
            .border(1.dp, FlintPalette.Divider, RoundedCornerShape(26.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (apps.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                apps.take(5).forEach { app ->
                    FlintAppTile(
                        packageName = app.packageName,
                        label = app.label,
                        size = 32.dp,
                        cornerRadius = 10.dp,
                        resting = true,
                    )
                }
                if (apps.size > 5) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(FlintPalette.BoneFill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+${apps.size - 5}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                            color = FlintPalette.Mist,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${apps.size} ${if (apps.size == 1) "app" else "apps"} ${if (armed) "resting" else "selected"}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = FlintPalette.Mist,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onViewApps)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FlintPalette.Hairline))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Locked until it ends", style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp))
                Text(
                    if (session.breakLevel == BreakLevel.HARDCORE) "Flint can’t be deleted while Hardcore runs."
                    else timerExitHint(session),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = FlintPalette.Mist,
                )
            }
            if (canEnd) GhostButton("End session", onEnd, enabled = enabled)
        }
    }
}

/** "45 min session" / "1h 30m session" from the persisted start and deadline. */
private fun sessionLengthLabel(start: Long, deadline: Long): String {
    val minutes = ((deadline - start) / 60_000L).toInt().coerceAtLeast(1)
    return if (minutes >= 60) {
        if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"
    } else {
        "$minutes min"
    }
}

/** Coverage feedback uses only the backend's actual observation; retries do not restart a Timer. */
@Composable
internal fun TimerArmingNotice(arming: TimerArmingState, onRetry: () -> Unit, onSetup: () -> Unit,
                               enabled: Boolean = true, setupAvailable: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (arming) {
            TimerArmingState.Idle -> Text("Protection status unavailable", style = MaterialTheme.typography.titleSmall)
            is TimerArmingState.Applying -> {
                Text("Applying…", style = MaterialTheme.typography.titleSmall)
                Text("Timer saved. Waiting for the protection service to confirm it.", style = MaterialTheme.typography.bodySmall)
            }
            is TimerArmingState.Checking -> {
                Text("Checking protection…", style = MaterialTheme.typography.titleSmall)
                Text("Waiting for a fresh service check.", style = MaterialTheme.typography.bodySmall)
            }
            is TimerArmingState.Armed -> {
                Text("Armed", style = MaterialTheme.typography.titleSmall)
                Text("Service checked at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(arming.checkedAt.wallEpochMs))}",
                    style = MaterialTheme.typography.bodySmall)
            }
            is TimerArmingState.Degraded -> {
                Text(if (arming.committed) "Protection degraded" else "Not armed", style = MaterialTheme.typography.titleSmall)
                Text(arming.reason, style = MaterialTheme.typography.bodySmall)
                if (arming.committed) Text("The Timer keeps its saved end time.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRetry, enabled = enabled) { Text("Retry protection") }
                if (setupAvailable) TextButton(onClick = onSetup, enabled = enabled) { Text("Check setup") }
                }
            }
        }
    }
}

/** E08 — the ember has set. */
@Composable
private fun CompleteScene(
    session: FocusSession,
    summary: FocusSummary?,
    contentPadding: PaddingValues,
    onDone: () -> Unit,
    onAgain: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize()) {
        EmberLandscape(horizonOffset = 8f, orbCenter = null, showsMist = false)
        SunArc(1f, complete = true)
        // Board: title up top, stats and actions low beneath the ridges, clear of the set sun.
        // At least screen-tall so the spacer can push them down; still scrolls at large type.
        BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding)) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlintStatusPill(
                "${session.name} · Complete".uppercase(),
                emphasized = false,
                modifier = Modifier.padding(top = 54.dp),
            )
            Text(
                "The ember has set.",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                "${FocusHistoryText.duration(session.focusedMs)} focused",
                style = MaterialTheme.typography.bodyMedium,
                color = FlintPalette.Mist,
                modifier = Modifier.padding(top = 10.dp),
            )
            session.breaks?.let { Text(FocusHistoryText.breaks(it), color = FlintPalette.Mist, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.weight(1f).heightIn(min = 26.dp))
            CompleteStatsRow(session = session, summary = summary)
            FocusHistoryText.note(summary, resources = LocalContext.current.resources)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = FlintPalette.Mist,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            BoneButton("Done", onDone)
            QuietButton("Go again", onAgain, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        }
        }
    }
}

/** M08's three quiet stat tiles — only the facts this session actually recorded. */
@Composable
private fun CompleteStatsRow(session: FocusSession, summary: FocusSummary?) {
    val stats = buildList {
        add("TODAY" to FocusHistoryText.today(summary))
        session.opensIntercepted?.let { add("STOPPED" to it.toString()) }
        session.breaks?.let { add("BREAKS" to it.toString()) }
    }.take(3)
    if (stats.isEmpty()) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stats.forEach { (label, value) ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(FlintPalette.Card.copy(alpha = 0.7f))
                    .border(1.dp, FlintPalette.Divider, RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(value, style = MaterialTheme.typography.titleLarge.merge(FlintNumerals), maxLines = 1)
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                    color = FlintPalette.Mist,
                    maxLines = 1,
                )
            }
        }
    }
}

private val todayPresets: List<TimerPreset>
    get() = listOf("deep-study", "reading", "workout").mapNotNull { id -> timerPresets.firstOrNull { it.id == id } }

private fun todayLabel(): String =
    SimpleDateFormat("EEEE · d MMMM", Locale.getDefault()).format(Date())

internal fun localMinuteOfDay(epochMs: Long): Int {
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMs }
    return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
}

/** Minutes until [target] minute-of-day, wrapping into tomorrow. */
private fun minutesUntil(target: Int, now: Int): Int = ((target - now) % 1440 + 1440) % 1440

/** Minutes left in [schedule]'s active window, or null when the rule is not inside one. */
internal fun scheduleMinutesLeft(schedule: Schedule?, now: Int): Int? {
    val s = schedule ?: return null
    val start = s.startMinuteOfDay
    val end = s.endMinuteOfDay
    if (start !in 0..1439 || end !in 0..1439 || start == end) return null
    return when {
        start < end -> if (now in start until end) end - now else null
        now >= start -> 1440 - now + end
        now < end -> end - now
        else -> null
    }
}

/** Fraction of the active schedule window elapsed, for the Now card's hairline meter. */
private fun scheduleProgress(schedule: Schedule?, now: Int): Float? {
    val left = scheduleMinutesLeft(schedule, now) ?: return null
    val s = schedule ?: return null
    val start = s.startMinuteOfDay
    val end = s.endMinuteOfDay
    val total = if (start < end) end - start else 1440 - start + end
    if (total <= 0) return null
    return ((total - left).toFloat() / total).coerceIn(0f, 1f)
}

internal fun shortDuration(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

private fun relativeStartLabel(minutes: Int): String =
    if (minutes < 60) "in ${minutes}m" else "in ${minutes / 60}h"

private fun nowCardSubtitle(rule: BlockRule, now: Int): String {
    val parts = mutableListOf(timerBreakLabel(rule.breakLevel))
    val end = rule.schedule?.endMinuteOfDay
    if (end != null) parts += "until ${formatMinuteOfDay(end)}"
    val apps = rule.targets.apps.size
    if (apps > 0) parts += "$apps ${if (apps == 1) "app" else "apps"} resting"
    return parts.joinToString(" · ")
}

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
    BreakLevel.EASY -> "Stop any time."
    BreakLevel.HARDER -> "No early stop. Breaks wait a few minutes."
    BreakLevel.HARDCORE -> "No breaks, no stop, can’t delete Flint until it ends."
}

private fun timerBreakDisclosure(level: BreakLevel): String = when (level) {
    BreakLevel.EASY -> "Ordinary breaks are available. Hold to end this timer early."
    BreakLevel.HARDER -> "Early changes wait 1, 5, then 15 minutes. After the wait, hold for 2 seconds to end this Timer."
    BreakLevel.HARDCORE -> "No ordinary breaks or hold-to-end. This Timer ends at expiry, or with an available Emergency Pass."
}

private fun timerExitHint(rule: BlockRule): String = when {
    canEndNativeTimer(rule) && !rule.id.startsWith(com.flint.peakfocus.core.datastore.CONTRACT_TIMER_ID_PREFIX) -> "Legacy Timer: hold to end early"
    rule.breakLevel == BreakLevel.HARDER -> "Wait, then hold to end early"
    rule.breakLevel == BreakLevel.HARDCORE -> "Expiry or an available Emergency Pass"
    else -> "Hold to end early"
}
