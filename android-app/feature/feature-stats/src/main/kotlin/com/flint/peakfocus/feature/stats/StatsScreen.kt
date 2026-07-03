package com.flint.peakfocus.feature.stats

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.UsageStat
import com.flint.peakfocus.permissions.UsageAccess

private val ChartHeight = 120.dp

/**
 * Screen-time reports: today's total, a 7-day bar comparison, and today's top apps by
 * foreground time. Public entry point for the A-VERIFY integrator to wire into MainActivity
 * navigation — this module does not touch the nav graph itself.
 *
 * Handles the Usage Access special grant: when missing, shows a friendly explainer whose
 * button hands off to Settings ([UsageAccess.settingsIntent]), and re-checks on ON_RESUME —
 * the same pattern MainActivity uses for the accessibility grant — so coming back from
 * Settings updates the screen immediately.
 */
@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val statsViewModel: StatsViewModel =
        viewModel(factory = remember(context) { StatsViewModel.factory(context) })
    val state by statsViewModel.uiState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statsViewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = state) {
            is StatsUiState.Loading -> LoadingState()
            is StatsUiState.PermissionRequired -> UsageAccessPrompt(
                onOpenSettings = {
                    context.startActivity(
                        UsageAccess.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            is StatsUiState.Ready -> StatsReport(s.report)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Friendly no-permission state; explains the grant in Flint's local-only, no-telemetry voice. */
@Composable
private fun UsageAccessPrompt(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "See where your time goes",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "To show screen-time reports, Flint needs Android's Usage Access. It reads " +
                "which apps were in the foreground and for how long — nothing else.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Like everything in Flint, this stays on your device. No account, no " +
                "analytics — nothing leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "On the next screen, find “Flint” under Usage access and switch it on, then " +
                "come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Allow usage access")
        }
    }
}

@Composable
private fun StatsReport(report: UsageReport) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            text = "Screen time",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "LAST 7 DAYS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        TodayCard(report.todayTotalMillis)
        Spacer(Modifier.height(16.dp))
        WeekChartCard(report)
        Spacer(Modifier.height(16.dp))
        TopAppsCard(report.topAppsToday)
    }
}

@Composable
private fun TodayCard(todayTotalMillis: Long) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Today",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = DurationFormat.format(todayTotalMillis),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "of screen time so far",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Day-by-day comparison: plain Compose boxes scaled against the busiest day — no chart library. */
@Composable
private fun WeekChartCard(report: UsageReport) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Last 7 days",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DurationFormat.format(report.weekTotalMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (report.weekTotalMillis == 0L) {
                Text(
                    text = "No usage recorded yet. Use your phone for a bit, then check back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val maxDayMillis = report.days.maxOf { it.totalMillis }.coerceAtLeast(1L)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    report.days.forEach { day ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(ChartHeight),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                val fraction = day.totalMillis.toFloat() / maxDayMillis
                                val barHeight =
                                    if (day.totalMillis <= 0L) 2.dp
                                    else (ChartHeight * fraction).coerceAtLeast(3.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(barHeight)
                                        .background(
                                            color = if (day.isToday) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            shape = RoundedCornerShape(4.dp),
                                        ),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = day.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (day.isToday) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Text(
                    text = "Daily average",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = DurationFormat.format(report.dailyAverageMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun TopAppsCard(topApps: List<UsageStat>) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Top apps today",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            if (topApps.isEmpty()) {
                Text(
                    text = "Nothing recorded yet today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val maxMillis = topApps.maxOf { it.foregroundMillis }.coerceAtLeast(1L)
                topApps.forEach { stat -> TopAppRow(stat, maxMillis) }
            }
        }
    }
}

@Composable
private fun TopAppRow(stat: UsageStat, maxMillis: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stat.label ?: stat.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (stat.label != null) {
                    Text(
                        text = stat.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = DurationFormat.format(stat.foregroundMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(6.dp))
        val fraction = (stat.foregroundMillis.toFloat() / maxMillis).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(2.dp),
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondary,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

// --- Previews (fake data only; no ViewModel) ---

private fun previewReport(): UsageReport {
    val hour = 3_600_000L
    val minute = 60_000L
    return UsageReport(
        days = listOf(
            DaySummary("Fri", 4 * hour + 12 * minute, isToday = false),
            DaySummary("Sat", 6 * hour + 40 * minute, isToday = false),
            DaySummary("Sun", 5 * hour + 5 * minute, isToday = false),
            DaySummary("Mon", 3 * hour + 30 * minute, isToday = false),
            DaySummary("Tue", 2 * hour + 55 * minute, isToday = false),
            DaySummary("Wed", 4 * hour, isToday = false),
            DaySummary("Thu", 1 * hour + 47 * minute, isToday = true),
        ),
        todayTotalMillis = 1 * hour + 47 * minute,
        weekTotalMillis = 28 * hour + 9 * minute,
        topAppsToday = listOf(
            UsageStat("com.instagram.android", "Instagram", 42 * minute),
            UsageStat("com.google.android.youtube", "YouTube", 31 * minute),
            UsageStat("com.android.chrome", "Chrome", 18 * minute),
            UsageStat("org.thoughtcrime.securesms", null, 9 * minute),
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun StatsReportPreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            StatsReport(previewReport())
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun UsageAccessPromptPreview() {
    FlintTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            UsageAccessPrompt(onOpenSettings = {})
        }
    }
}
