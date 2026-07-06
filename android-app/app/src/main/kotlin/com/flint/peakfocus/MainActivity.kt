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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.feature.blocklist.BlocklistScreen
import com.flint.peakfocus.feature.onboarding.AccessibilityConsentScreen
import com.flint.peakfocus.feature.settings.SettingsScreen
import com.flint.peakfocus.feature.stats.StatsScreen
import com.flint.peakfocus.permissions.AccessibilityPermission
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

    // On every return to the app: reconcile the Path B fallback service with the current
    // grants. (Rule publishing needs no resume hook — each writer owns its own holder lane.)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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
            FlintTab.Home -> HomeScreen(contentModifier, onEnableBlocking = { showConsent = true })
            FlintTab.Blocklist -> BlocklistScreen(contentModifier)
            FlintTab.Stats -> StatsScreen(contentModifier)
            FlintTab.Settings -> SettingsScreen(contentModifier)
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier, onEnableBlocking: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BlocklistStore(context) }
    var accessibilityOn by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(store.blockedPackages) }
    var apps by remember { mutableStateOf(listOf<AppEntry>()) }
    var query by rememberSaveable { mutableStateOf("") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = AccessibilityPermission.isEnabled(context, A11Y_SERVICE_CLASS)
                blocked = store.blockedPackages
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        store.load()
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Column(modifier) {
        Column(Modifier.padding(20.dp)) {
            Text("Flint", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground)
            Text("PEAK FOCUS", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            StatusCard(accessibilityOn, onEnable = onEnableBlocking)
            Spacer(Modifier.height(16.dp))
            ScheduleCard(store)
            Spacer(Modifier.height(16.dp))
            Text("Block these apps", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            AppSearchField(query = query, onQueryChange = { query = it })
        }
        val shown = filterApps(apps, query)
        LazyColumn(Modifier.weight(1f)) {
            if (shown.isEmpty() && apps.isNotEmpty()) {
                item {
                    Text(
                        text = "No apps match “${query.trim()}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                    )
                }
            }
            items(shown, key = { it.packageName }) { app ->
                AppRow(app, app.packageName in blocked) {
                    store.toggle(app.packageName)
                    blocked = store.blockedPackages
                }
            }
        }
    }
}

/** Filter box for the app list. The list is long; scrolling to Signal shouldn't be a chore. */
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
private fun StatusCard(accessibilityOn: Boolean, onEnable: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (accessibilityOn) "Blocking is ON" else "Blocking is OFF",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (accessibilityOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (accessibilityOn) "Flint blocks the apps you switch on below."
                    else "Turn on the Flint accessibility service to start blocking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!accessibilityOn) {
                Button(onClick = onEnable) { Text("Enable") }
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
                        }
                    }) { Text("Start ${formatMinutes(startMin)}") }
                    Button(onClick = {
                        pickTime(context, endMin) {
                            endMin = it; store.scheduleEndMin = it
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
