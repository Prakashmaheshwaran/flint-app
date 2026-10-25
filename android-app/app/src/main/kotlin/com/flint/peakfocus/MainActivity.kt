package com.flint.peakfocus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.ui.EmberButton
import com.flint.peakfocus.core.common.ui.EmberLandscape
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.FlintMark
import com.flint.peakfocus.core.common.ui.FlintTabBar
import com.flint.peakfocus.core.common.ui.FlintTabGlyph
import com.flint.peakfocus.core.common.ui.flintSwitchColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flint.peakfocus.blocking.resilience.PermissionHealthChecker
import com.flint.peakfocus.core.common.theme.FlintIcons
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.theme.rememberFlintMark
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.FlintSkeletonRow
import com.flint.peakfocus.core.common.ui.FlintStatusDot
import com.flint.peakfocus.core.common.ui.FlintTimeField
import com.flint.peakfocus.core.common.ui.formatMinuteOfDayText
import com.flint.peakfocus.core.common.ui.parseMinuteOfDayText
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.feature.blocklist.BlocklistScreen
import com.flint.peakfocus.feature.blocklist.TimerScreen
import com.flint.peakfocus.feature.onboarding.AccessibilityConsentScreen
import com.flint.peakfocus.feature.onboarding.SetupGuidance
import com.flint.peakfocus.feature.onboarding.SetupPlan
import com.flint.peakfocus.feature.onboarding.SetupStep
import com.flint.peakfocus.feature.settings.SettingsScreen
import com.flint.peakfocus.feature.stats.StatsScreen
import com.flint.peakfocus.permissions.AccessibilityPermission
import com.flint.peakfocus.permissions.BatteryOptimization
import com.flint.peakfocus.permissions.OverlayPermission
import com.flint.peakfocus.permissions.UsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** One launchable app for the quick block list: identity plus a small pre-decoded icon. */
data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap? = null)

/** Screens pushed over the tabs. */
private enum class Detail { QuickBlock, Account }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ember & Mist is dark-only: light system-bar icons over the night sky.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            FlintTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlintApp()
                }
            }
        }
    }
}

@Composable
private fun FlintApp() {
    val context = LocalContext.current
    val haptics = rememberFlintHaptics()
    val prefs = remember { context.getSharedPreferences("flint_onboarding", Context.MODE_PRIVATE) }
    var welcomed by rememberSaveable { mutableStateOf(prefs.getBoolean(KEY_WELCOMED, false)) }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var detail by rememberSaveable { mutableStateOf<Detail?>(null) }
    var health by remember { mutableStateOf(PermissionHealthChecker.check(context)) }
    val store = remember { BlocklistStore(context) }
    var blockedCount by remember { mutableStateOf(store.blockedPackages.size) }

    // Result is deliberately unused: the FGS runs with or without the grant — the permission
    // only controls whether its ongoing notification is visible on Android 13+.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    // On every return to the app: re-sync the engine's rule list (a freshness pass), reconcile
    // the Path B fallback service with the current grants, and re-read enforcement health —
    // Today may have sent the user to a Settings surface, and OEMs revoke grants silently.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ActiveRulesBridge.republish(context)
                PathBServiceGate.sync(context)
                maybeRequestPathBNotificationPermission(context, notifPermissionLauncher)
                health = PermissionHealthChecker.check(context)
                blockedCount = store.blockedPackages.size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        store.load() // publishes the legacy projection under its own holder source key
        blockedCount = store.blockedPackages.size
    }

    // Hoisted so the launchable-apps query (and its skeleton pass) runs once per activity
    // composition, not once per visit to the quick block list.
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) } // null = still loading
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    var tab by rememberSaveable { mutableStateOf(FlintTabGlyph.Today) }
    val stateHolder = rememberSaveableStateHolder()
    // Back pops a pushed screen, then walks to Today before leaving the app. Feature-level
    // handlers (rule editors, pickers) compose later, so they win whenever they are enabled.
    BackHandler(enabled = !showConsent && welcomed && (detail != null || tab != FlintTabGlyph.Today)) {
        if (detail != null) detail = null else tab = FlintTabGlyph.Today
    }

    val onSetupStep: (SetupStep) -> Unit = { step ->
        val handOff = settingsHandOff(context, step)
        if (handOff == null) {
            showConsent = true
        } else {
            context.startActivity(handOff.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    val consentSlidePx = with(LocalDensity.current) { FlintSpacing.lg.roundToPx() }
    AnimatedContent(
        targetState = when {
            !welcomed -> Stage.Welcome
            showConsent -> Stage.Consent
            else -> Stage.Tabs
        },
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            (fadeIn(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)) +
                slideInVertically(
                    tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
                ) { consentSlidePx }) togetherWith
                fadeOut(tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard))
        },
        label = "stage",
    ) { stage ->
        when (stage) {
            Stage.Welcome -> WelcomeScreen(onBegin = {
                prefs.edit().putBoolean(KEY_WELCOMED, true).apply()
                welcomed = true
            })
            // ADR-007 (Play gate): full-screen prominent disclosure. The Accept button below is
            // the ONLY route to the accessibility-settings hand-off — no tab bar, no shortcut.
            Stage.Consent -> AccessibilityConsentScreen(
                onAccept = {
                    showConsent = false
                    context.startActivity(
                        AccessibilityPermission.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onDecline = { showConsent = false },
            )
            Stage.Tabs -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val systemBars = WindowInsets.systemBars.asPaddingValues()
                val plan = remember(health) { SetupGuidance.plan(health) }
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        FlintTabBar(
                            selected = tab,
                            onSelect = { destination ->
                                if (tab != destination) {
                                    haptics.tick()
                                    tab = destination
                                }
                            },
                            modifier = Modifier.padding(top = FlintSpacing.md),
                        )
                    },
                ) { safePadding ->
                    // Own insets once, outside feature navigation. Every picker/editor gets
                    // the same viewport; no branch can put controls beneath the measured
                    // tab bar. Consume before IME padding so bottom insets are not doubled.
                    Box(
                        Modifier.fillMaxSize()
                            .padding(safePadding)
                            .consumeWindowInsets(safePadding)
                            .imePadding()
                            .clipToBounds(),
                    ) {
                        // Only the selected tab is composed, so outgoing picker BackHandlers
                        // cannot remain active over another tab.
                        stateHolder.SaveableStateProvider(tab.name) {
                            when (tab) {
                                FlintTabGlyph.Today -> TimerScreen(
                                    headline = plan.headline,
                                    // Today keeps one sentence; Settings carries the full explanation.
                                    subline = plan.body.substringBefore(". ").removeSuffix(".") + ".",
                                    setupLabel = plan.nextStep?.ctaLabel,
                                    onSetup = { plan.nextStep?.let(onSetupStep) },
                                )
                                FlintTabGlyph.Rules -> BlocklistScreen(
                                    quickBlockSummary = if (blockedCount == 0) "Apps blocked whenever Flint is on"
                                    else "$blockedCount ${if (blockedCount == 1) "app" else "apps"} always blocked",
                                    onQuickBlock = { detail = Detail.QuickBlock },
                                )
                                FlintTabGlyph.Insights -> StatsScreen(Modifier.fillMaxSize())
                                FlintTabGlyph.You -> SettingsScreen(
                                    Modifier.fillMaxSize(),
                                    onAccount = if (BuildConfig.DEBUG) {
                                        { detail = Detail.Account }
                                    } else null,
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = detail != null,
                    enter = slideInHorizontally(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)) { it / 4 } +
                        fadeIn(tween(FlintMotion.DurationMedium)),
                    exit = slideOutHorizontally(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)) { it / 4 } +
                        fadeOut(tween(FlintMotion.DurationShort)),
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (detail) {
                            Detail.QuickBlock -> QuickBlockScreen(
                                store = store,
                                apps = apps,
                                contentPadding = systemBars,
                                onBack = { detail = null },
                                onChanged = { blockedCount = store.blockedPackages.size },
                            )
                            Detail.Account -> ConnectedDevelopmentScreen(
                                onBack = { detail = null },
                                modifier = Modifier.fillMaxSize().padding(systemBars),
                            )
                            null -> Unit
                        }
                    }
                }
            }
        }
    }
}

private enum class Stage { Welcome, Consent, Tabs }

private const val KEY_WELCOMED = "welcomed"

/** E01 — first run: the mark, the promise, one ember action. */
@Composable
private fun WelcomeScreen(onBegin: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        EmberLandscape(horizonOffset = 20f, orbCenter = Offset(195f, 480f), orbRadius = 64f)
        Column(
            Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
                .padding(horizontal = FlintSpacing.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(110.dp))
            FlintMark(Modifier.size(40.dp))
            Spacer(Modifier.height(14.dp))
            Text("Flint", style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, letterSpacing = (-0.8).sp))
            Spacer(Modifier.height(14.dp))
            Text(
                "Make time for what matters.",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
                color = FlintPalette.SoftBone,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "No account  ·  No analytics  ·  Every feature free",
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            EmberButton("Begin", onBegin)
            Spacer(Modifier.height(18.dp))
            Text("Open source · built in the open", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = FlintPalette.Stone)
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Direct Settings hand-off for a setup step. Accessibility deliberately returns null:
 * its only route is the prominent-disclosure consent screen in [FlintApp] (ADR-007).
 */
private fun settingsHandOff(context: Context, step: SetupStep): Intent? = when (step) {
    SetupStep.ENABLE_ACCESSIBILITY -> null
    SetupStep.GRANT_USAGE_ACCESS -> UsageAccess.settingsIntent()
    SetupStep.GRANT_OVERLAY -> OverlayPermission.settingsIntent(context)
    SetupStep.REQUEST_BATTERY_EXEMPTION -> BatteryOptimization.requestIntent(context)
}

/**
 * The always-on quick block list (the legacy blocklist store): apps blocked whenever Flint
 * is on, optionally only during set hours. Reached from Rules.
 */
@Composable
private fun QuickBlockScreen(
    store: BlocklistStore,
    apps: List<AppEntry>?, // null = still loading; owned by FlintApp
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    var blocked by remember { mutableStateOf(store.blockedPackages) }
    var appQuery by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = FlintSpacing.gutter,
            top = contentPadding.calculateTopPadding() + FlintSpacing.sm,
            end = FlintSpacing.gutter,
            bottom = contentPadding.calculateBottomPadding() + FlintSpacing.gutter,
        ),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(FlintGlyphs.back, contentDescription = "Back to Rules", modifier = Modifier.size(20.dp))
                }
                Text("Rules", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(FlintSpacing.sm))
            FlintScreenHeader("Quick block list", eyebrow = "${blocked.size} selected")
            Spacer(Modifier.height(FlintSpacing.lg))
        }
        item(key = "schedule") {
            ScheduleCard(store)
            Spacer(Modifier.height(FlintSpacing.md))
        }
        item(key = "apps-label") {
            FlintSectionLabel("Block these apps")
            Spacer(Modifier.height(FlintSpacing.sm))
            AppSearchField(query = appQuery, onQueryChange = { appQuery = it })
            Spacer(Modifier.height(FlintSpacing.sm))
        }
        when (val list = apps) {
            null -> items(count = 6, key = { "skeleton-$it" }) {
                FlintSkeletonRow(Modifier.animateItem())
            }
            else ->
                if (list.isEmpty()) {
                    item(key = "empty-apps") { EmptyAppsState() }
                } else {
                    val shown = filterApps(list, appQuery)
                    if (shown.isEmpty()) {
                        item(key = "no-app-matches") {
                            Text(
                                text = "No apps match “${appQuery.trim()}”",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(FlintSpacing.lg),
                            )
                        }
                    } else {
                        items(shown, key = { it.packageName }) { app ->
                            AppRow(
                                app,
                                app.packageName in blocked,
                                modifier = Modifier.animateItem(),
                            ) {
                                store.toggle(app.packageName)
                                blocked = store.blockedPackages
                                onChanged()
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun AppSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search apps") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
    )
}



@Composable
private fun EmptyAppsState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = FlintSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = rememberFlintMark(),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(FlintSpacing.xl),
        )
        Spacer(Modifier.height(FlintSpacing.sm))
        Text(
            "No launchable apps found.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    isBlocked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val haptics = rememberFlintHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(
                value = isBlocked,
                role = Role.Switch,
                onValueChange = { checked ->
                    if (checked) haptics.toggleOn() else haptics.toggleOff()
                    onToggle()
                },
            )
            .padding(vertical = FlintSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
    ) {
        if (app.icon != null) {
            Image(
                bitmap = app.icon,
                contentDescription = null, // decorative; the row's label names the app
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
            )
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    app.label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground)
            if (app.label != app.packageName) {
                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Row-level toggleable carries the state + Switch role for TalkBack; the Switch
        // itself is display-only so there is exactly one interactive target.
        Switch(checked = isBlocked, onCheckedChange = null, colors = flintSwitchColors())
    }
}

@Composable
private fun ScheduleCard(store: BlocklistStore) {
    val context = LocalContext.current
    val haptics = rememberFlintHaptics()
    var enabled by remember { mutableStateOf(store.scheduleEnabled) }
    var startMin by remember { mutableStateOf(store.scheduleStartMin) }
    var endMin by remember { mutableStateOf(store.scheduleEndMin) }

    FlintCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Only block during set hours",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Outside the window, blocked apps are allowed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, colors = flintSwitchColors(), onCheckedChange = {
                if (it) haptics.toggleOn() else haptics.toggleOff()
                enabled = it; store.scheduleEnabled = it
                ActiveRulesBridge.republish(context)
            })
        }
        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)) +
                fadeIn(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard)),
            exit = shrinkVertically(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)) +
                fadeOut(tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard)),
        ) {
            // The kit field speaks canonical "HH:mm" text; the store keeps minute-of-day
            // ints, so the exchange bridges through format/parseMinuteOfDayText.
            Row(
                Modifier.fillMaxWidth().padding(top = FlintSpacing.cardGap),
                horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
            ) {
                FlintTimeField(
                    label = "Start",
                    valueText = formatMinuteOfDayText(startMin),
                    onValueText = { text ->
                        val picked = parseMinuteOfDayText(text) ?: return@FlintTimeField
                        startMin = picked; store.scheduleStartMin = picked
                        ActiveRulesBridge.republish(context)
                    },
                    modifier = Modifier.weight(1f),
                )
                FlintTimeField(
                    label = "End",
                    valueText = formatMinuteOfDayText(endMin),
                    onValueText = { text ->
                        val picked = parseMinuteOfDayText(text) ?: return@FlintTimeField
                        endMin = picked; store.scheduleEndMin = picked
                        ActiveRulesBridge.republish(context)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Android 13+ shows a foreground service's ongoing notification only if POST_NOTIFICATIONS
 * is granted — declared in the manifest but (previously) never requested, so Path B ran
 * invisibly. Asked **once**, and only when Path B is actually in play (usage access granted,
 * a11y service off — the same condition [PathBServiceGate] starts the service under). A
 * decline is respected: no re-prompt on later resumes. Enforcement never depends on the
 * grant; only the notification's visibility does.
 */
private fun maybeRequestPathBNotificationPermission(
    context: Context,
    launcher: ManagedActivityResultLauncher<String, Boolean>,
) {
    if (Build.VERSION.SDK_INT < 33) return
    val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return
    val prefs = context.getSharedPreferences("flint_permissions", Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return
    val pathBInPlay = UsageAccess.isGranted(context) &&
        !AccessibilityPermission.isEnabled(context, A11Y_SERVICE_CLASS)
    if (!pathBInPlay) return
    prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
}

private const val KEY_NOTIF_ASKED = "post_notifications_asked"

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) {
                null
            } else {
                // Decoded once, small (40dp rows): ~200 apps ≈ a few MB, never full launcher art.
                val icon = runCatching {
                    resolveInfo.loadIcon(pm).toBitmap(APP_ICON_PX, APP_ICON_PX).asImageBitmap()
                }.getOrNull()
                AppEntry(pkg, resolveInfo.loadLabel(pm).toString(), icon)
            }
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private const val APP_ICON_PX = 96
