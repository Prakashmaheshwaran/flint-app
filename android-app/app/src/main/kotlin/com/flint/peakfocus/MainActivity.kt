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

/** One launchable app for the Home quick-blocklist: identity plus a small pre-decoded icon. */
data class AppEntry(val packageName: String, val label: String, val icon: ImageBitmap? = null)

/** APK-backed hub order. Insights and profile/settings remain reachable from Home. */
private enum class FlintTab(
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
) {
    Home("Home", Icons.Filled.Home, Icons.Outlined.Home),
    MyApps("My Apps", Icons.Filled.Lock, Icons.Outlined.Lock),
    Timer("Timer", FlintIcons.timer(), FlintIcons.timer()),
}

private enum class HomePage { Overview, Stats, Settings, Account }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var homePage by rememberSaveable { mutableStateOf(HomePage.Overview) }

    // Result is deliberately unused: the FGS runs with or without the grant — the permission
    // only controls whether its ongoing notification is visible on Android 13+.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    // On every return to the app: re-sync the engine's rule list (a freshness pass) and
    // reconcile the Path B fallback service with the current grants.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ActiveRulesBridge.republish(context)
                PathBServiceGate.sync(context)
                maybeRequestPathBNotificationPermission(context, notifPermissionLauncher)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hoisted out of HomeScreen so the launchable-apps query (and its skeleton pass) runs
    // once per activity composition, not once per visit to the Home tab.
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) } // null = still loading
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    var tab by rememberSaveable { mutableStateOf(FlintTab.Home) }
    val stateHolder = rememberSaveableStateHolder()
    // Back walks to Home before leaving the app. Feature-level handlers (rule editors,
    // pickers) compose later, so they win whenever they are enabled.
    BackHandler(enabled = !showConsent &&
        !(homePage == HomePage.Account && tab == FlintTab.Home) &&
        (tab != FlintTab.Home || homePage != HomePage.Overview)) {
        tab = FlintTab.Home
        homePage = HomePage.Overview
    }

    val consentSlidePx = with(LocalDensity.current) { FlintSpacing.lg.roundToPx() }
    AnimatedContent(
        targetState = showConsent,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            (fadeIn(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)) +
                slideInVertically(
                    tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
                ) { consentSlidePx }) togetherWith
                fadeOut(tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard))
        },
        label = "consent",
    ) { consenting ->
        if (consenting) {
            // ADR-007 (Play gate): full-screen prominent disclosure. The Accept button below is
            // the ONLY route to the accessibility-settings hand-off — no tab bar, no shortcut.
            AccessibilityConsentScreen(
                onAccept = {
                    showConsent = false
                    context.startActivity(
                        AccessibilityPermission.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onDecline = { showConsent = false },
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    // The FlintCard hairline idiom carried to the nav surface: a 1dp
                    // outlineVariant line above the bar instead of elevation.
                    Column {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                            FlintTab.entries.forEach { destination ->
                                val selected = tab == destination
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (destination == FlintTab.Home) homePage = HomePage.Overview
                                        if (tab != destination) {
                                            haptics.tick()
                                            tab = destination
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) destination.iconSelected
                                            else destination.iconUnselected,
                                            contentDescription = destination.label,
                                        )
                                    },
                                    label = { Text(destination.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                // Compose only the selected tab. Keeping an outgoing feature alive during
                // animation also keeps its picker/account BackHandler active over the next tab.
                Box(Modifier.fillMaxSize()) {
                    val destination = tab
                    // One saveable slot per tab: LazyColumn scroll positions and
                    // rememberSaveable state survive tab switches.
                    stateHolder.SaveableStateProvider(destination) {
                        val contentModifier = Modifier.fillMaxSize()
                        when (destination) {
                            // Home draws edge-to-edge; the scaffold insets merge into its
                            // LazyColumn contentPadding instead of clipping the container.
                            FlintTab.Home -> stateHolder.SaveableStateProvider(homePage) {
                                when (homePage) {
                                    HomePage.Overview -> HomeScreen(
                                        contentModifier,
                                        innerPadding = innerPadding,
                                        apps = apps,
                                        onTimer = { tab = FlintTab.Timer },
                                        onStats = { homePage = HomePage.Stats },
                                        onProfile = { homePage = HomePage.Settings },
                                        onSetupStep = { step ->
                                            val handOff = settingsHandOff(context, step)
                                            if (handOff == null) {
                                                showConsent = true
                                            } else {
                                                context.startActivity(
                                                    handOff.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            }
                                        },
                                    )
                                    HomePage.Stats -> HomeDetail(
                                        contentModifier.padding(innerPadding),
                                        onBack = { homePage = HomePage.Overview },
                                    ) { detailModifier -> StatsScreen(detailModifier) }
                                    HomePage.Account -> ConnectedDevelopmentScreen(
                                        onBack = { homePage = HomePage.Settings },
                                        modifier = contentModifier.padding(innerPadding),
                                    )
                                    HomePage.Settings -> HomeDetail(
                                        contentModifier.padding(innerPadding),
                                        onBack = { homePage = HomePage.Overview },
                                    ) { detailModifier ->
                                        SettingsScreen(
                                            detailModifier,
                                            onAccount = if (BuildConfig.DEBUG) {
                                                { homePage = HomePage.Account }
                                            } else null,
                                        )
                                    }
                                }
                            }
                            FlintTab.MyApps -> BlocklistScreen(contentModifier.padding(innerPadding))
                            FlintTab.Timer -> TimerScreen(contentModifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDetail(
    modifier: Modifier,
    onBack: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = FlintSpacing.sm)) {
            Text("Back to Home")
        }
        content(Modifier.fillMaxWidth().weight(1f))
    }
}

/**
 * Direct Settings hand-off for a Home setup step. Accessibility deliberately returns null:
 * its only route is the prominent-disclosure consent screen in [FlintApp] (ADR-007).
 */
private fun settingsHandOff(context: Context, step: SetupStep): Intent? = when (step) {
    SetupStep.ENABLE_ACCESSIBILITY -> null
    SetupStep.GRANT_USAGE_ACCESS -> UsageAccess.settingsIntent()
    SetupStep.GRANT_OVERLAY -> OverlayPermission.settingsIntent(context)
    SetupStep.REQUEST_BATTERY_EXEMPTION -> BatteryOptimization.requestIntent(context)
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues = PaddingValues(0.dp),
    apps: List<AppEntry>? = null, // null = still loading; owned by FlintApp
    onSetupStep: (SetupStep) -> Unit,
    onTimer: () -> Unit,
    onStats: () -> Unit,
    onProfile: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { BlocklistStore(context) }
    var health by remember { mutableStateOf(PermissionHealthChecker.check(context)) }
    var blocked by remember { mutableStateOf(store.blockedPackages) }
    var appQuery by rememberSaveable { mutableStateOf("") }

    // Block Now sessions live in the DataStore rules under the session id prefix; the engine
    // stops enforcing them at expiry on its own, so this state is purely presentational.
    val rulesStore = remember { FlintPreferences.blockRules(context) }
    val dataStoreRules by rulesStore.rules.collectAsState(initial = emptyList())
    var sessionNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val activeSession = dataStoreRules.firstOrNull {
        it.id.startsWith(BlockRule.SESSION_ID_PREFIX) && it.enabled && !it.isExpired(sessionNow)
    }
    LaunchedEffect(activeSession?.id) {
        if (activeSession == null) return@LaunchedEffect
        while (isActive) {
            delay(1_000L)
            sessionNow = System.currentTimeMillis()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // The card may have sent the user to any of three Settings surfaces, and OEMs
                // may revoke grants silently. Re-read the complete truth on every return.
                health = PermissionHealthChecker.check(context)
                blocked = store.blockedPackages
                sessionNow = System.currentTimeMillis()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        store.load() // publishes the legacy projection under its own holder source key
    }

    LazyColumn(
        modifier = modifier,
        // Edge-to-edge: scaffold insets fold into the scroll padding so content slides under
        // the translucent status bar and behind the nav bar instead of being clipped.
        contentPadding = PaddingValues(
            start = FlintSpacing.gutter,
            top = innerPadding.calculateTopPadding() + FlintSpacing.gutter,
            end = FlintSpacing.gutter,
            bottom = innerPadding.calculateBottomPadding() + FlintSpacing.gutter,
        ),
    ) {
        item(key = "header") {
            FlintScreenHeader("Flint", eyebrow = "Peak Focus")
            Row(horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
                OutlinedButton(onClick = onStats, modifier = Modifier.weight(1f)) { Text("Insights") }
                OutlinedButton(onClick = onProfile, modifier = Modifier.weight(1f)) { Text("Profile & settings") }
            }
            Spacer(Modifier.height(FlintSpacing.md))
        }
        item(key = "status") {
            StatusCard(
                plan = remember(health) { SetupGuidance.plan(health) },
                onSetupStep = onSetupStep,
            )
            Spacer(Modifier.height(FlintSpacing.cardGap))
        }
        item(key = "focus-session") {
            FocusSessionCard(
                session = activeSession,
                nowEpochMs = sessionNow,
                onOpenTimer = onTimer,
            )
            Spacer(Modifier.height(FlintSpacing.cardGap))
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

/** Home summarizes the persisted session; Timer owns creation and cancellation. */
@Composable
private fun FocusSessionCard(
    session: BlockRule?,
    nowEpochMs: Long,
    onOpenTimer: () -> Unit,
) {
    FlintCard {
        FlintSectionLabel("Focus timer")
        Spacer(Modifier.height(FlintSpacing.sm))
        if (session != null) {
            Text(
                session.expiresAtEpochMs?.let { formatCountdown(it - nowEpochMs) } ?: "Session active",
                style = MaterialTheme.typography.displaySmall.merge(FlintNumerals),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(session.name, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Choose apps and a duration for your next focus session.",
                style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(FlintSpacing.cardGap))
        Button(onClick = onOpenTimer, modifier = Modifier.fillMaxWidth()) {
            Text(if (session == null) "Set a timer" else "View timer")
        }
    }
}

/** "1h 24m" / "12m 30s" / "42s"; negatives clamp to "0s". */
private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
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
private fun StatusCard(plan: SetupPlan, onSetupStep: (SetupStep) -> Unit) {
    // The enforcement truth animates instead of snapping: Path A and Path B both light the
    // card, while the CTA expands below the explanation whenever one setup step remains.
    val haptics = rememberFlintHaptics()
    val statusColorSpec = tween<Color>(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard)
    val titleColor by animateColorAsState(
        targetValue = if (plan.enforcingNow) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        animationSpec = statusColorSpec,
        label = "statusTitleColor",
    )
    val dotColor by animateColorAsState(
        targetValue = if (plan.enforcingNow) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = statusColorSpec,
        label = "statusDotColor",
    )
    FlintCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
        ) {
            AnimatedVisibility(
                visible = plan.enforcingNow,
                enter = fadeIn(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard)) +
                    expandHorizontally(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)),
                exit = fadeOut(tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard)) +
                    shrinkHorizontally(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)),
            ) {
                Icon(
                    imageVector = rememberFlintMark(),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(40.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm),
                ) {
                    // Pulsing only while blocking is live — the screen's one quiet ember.
                    FlintStatusDot(color = dotColor, pulsing = plan.enforcingNow)
                    Text(
                        plan.headline,
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor,
                    )
                }
                Text(
                    plan.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val nextStep = plan.nextStep
        AnimatedVisibility(
            visible = nextStep != null,
            enter = fadeIn(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard)) +
                expandVertically(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)),
            exit = fadeOut(tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard)) +
                shrinkVertically(tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)),
        ) {
            if (nextStep != null) {
                Column {
                    Spacer(Modifier.height(FlintSpacing.md))
                    Button(
                        onClick = {
                            haptics.tick()
                            onSetupStep(nextStep)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(nextStep.ctaLabel)
                    }
                }
            }
        }
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
        Switch(checked = isBlocked, onCheckedChange = null)
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
            Switch(checked = enabled, onCheckedChange = {
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
