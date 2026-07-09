package com.flint.peakfocus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flint.peakfocus.blocking.resilience.PermissionHealthChecker
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.feature.blocklist.BlocklistScreen
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
import kotlinx.coroutines.withContext

data class AppEntry(val packageName: String, val label: String)

/** The app's four top-level destinations. State-switched — same pattern the shell already
 *  used for the consent flow; no navigation library in the version catalog (A-SCAFFOLD-owned). */
private enum class FlintTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Blocklist("Blocklist", Icons.Filled.Lock),
    Stats("Stats", Icons.Filled.DateRange),
    Settings("Settings", Icons.Filled.Settings),
}

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
    var showConsent by rememberSaveable { mutableStateOf(false) }

    // On every return to the app: re-sync the engine's rule list (legacy writers may have
    // clobbered it) and reconcile the Path B fallback service with the current grants.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ActiveRulesBridge.republish(context)
                PathBServiceGate.sync(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showConsent) {
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
        return
    }

    var tab by rememberSaveable { mutableStateOf(FlintTab.Home) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Token-sourced: FlintTheme sets no surfaceContainer role, so pin the bar to the
            // brand surface rather than M3's baseline default.
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                FlintTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = { tab = destination },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.fillMaxSize().padding(innerPadding)
        when (tab) {
            FlintTab.Home -> HomeScreen(
                contentModifier,
                onSetupStep = { step ->
                    val handOff = settingsHandOff(context, step)
                    if (handOff == null) {
                        showConsent = true
                    } else {
                        context.startActivity(handOff.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
            FlintTab.Blocklist -> BlocklistScreen(contentModifier)
            FlintTab.Stats -> StatsScreen(contentModifier)
            FlintTab.Settings -> SettingsScreen(contentModifier)
        }
    }
}

/**
 * Settings hand-off for a setup step, or null when the step may not be handed off directly.
 * [SetupStep.ENABLE_ACCESSIBILITY] is that case: the prominent-disclosure consent screen owns
 * the route to Accessibility settings (Play policy, ADR-007), so there is deliberately no
 * second one here. Only ever called from the status card's button — Flint never auto-redirects.
 */
private fun settingsHandOff(context: Context, step: SetupStep): Intent? = when (step) {
    SetupStep.ENABLE_ACCESSIBILITY -> null
    SetupStep.GRANT_USAGE_ACCESS -> UsageAccess.settingsIntent()
    SetupStep.GRANT_OVERLAY -> OverlayPermission.settingsIntent(context)
    SetupStep.REQUEST_BATTERY_EXEMPTION -> BatteryOptimization.requestIntent(context)
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier, onSetupStep: (SetupStep) -> Unit) {
    val context = LocalContext.current
    val store = remember { BlocklistStore(context) }
    var health by remember { mutableStateOf(PermissionHealthChecker.check(context)) }
    var blocked by remember { mutableStateOf(store.blockedPackages) }
    var apps by remember { mutableStateOf(listOf<AppEntry>()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Re-check every grant, not just the accessibility one: the user may be back
                // from any of the hand-offs the card offers, and OEMs revoke grants silently.
                health = PermissionHealthChecker.check(context)
                blocked = store.blockedPackages
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        store.load()
        ActiveRulesBridge.republish(context) // load() clobbers the holder — restore DataStore rules
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Column(modifier) {
        Column(Modifier.padding(20.dp)) {
            Text("Flint", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground)
            Text("PEAK FOCUS", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            StatusCard(remember(health) { SetupGuidance.plan(health) }, onSetupStep)
            Spacer(Modifier.height(16.dp))
            ScheduleCard(store)
            Spacer(Modifier.height(16.dp))
            Text("Block these apps", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.packageName }) { app ->
                AppRow(app, app.packageName in blocked) {
                    store.toggle(app.packageName)
                    blocked = store.blockedPackages
                    ActiveRulesBridge.republish(context)
                }
            }
        }
    }
}

/**
 * Blocking health, straight from [SetupGuidance] — every word, and whether there is anything
 * left to ask for. It reports "on" whenever *either* path can enforce, so a user running on the
 * Path B fallback is no longer told blocking is off and pushed at a grant they don't need.
 */
@Composable
private fun StatusCard(plan: SetupPlan, onSetupStep: (SetupStep) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                plan.headline,
                style = MaterialTheme.typography.titleMedium,
                color = if (plan.enforcingNow) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                plan.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.nextStep?.let { step ->
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSetupStep(step) }, modifier = Modifier.fillMaxWidth()) {
                    Text(step.ctaLabel)
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, isBlocked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = isBlocked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun ScheduleCard(store: BlocklistStore) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(store.scheduleEnabled) }
    var startMin by remember { mutableStateOf(store.scheduleStartMin) }
    var endMin by remember { mutableStateOf(store.scheduleEndMin) }

    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
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
                    enabled = it; store.scheduleEnabled = it
                    ActiveRulesBridge.republish(context)
                })
            }
            if (enabled) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = {
                        pickTime(context, startMin) {
                            startMin = it; store.scheduleStartMin = it
                            ActiveRulesBridge.republish(context)
                        }
                    }) { Text("Start ${formatMinutes(startMin)}") }
                    Button(onClick = {
                        pickTime(context, endMin) {
                            endMin = it; store.scheduleEndMin = it
                            ActiveRulesBridge.republish(context)
                        }
                    }) { Text("End ${formatMinutes(endMin)}") }
                }
            }
        }
    }
}

private fun pickTime(context: Context, currentMin: Int, onPicked: (Int) -> Unit) {
    android.app.TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        currentMin / 60, currentMin % 60, true,
    ).show()
}

private fun formatMinutes(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

private fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) null
            else AppEntry(pkg, resolveInfo.loadLabel(pm).toString())
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
