package com.flint.peakfocus.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.history.FocusDay
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.FocusHistoryText
import com.flint.peakfocus.core.history.HistoryAvailability
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

internal data class FocusPeriodTotals(val focused: String, val completed: Int, val endedEarly: Int)

internal fun focusPeriodTotals(days: List<FocusDay>): FocusPeriodTotals = FocusPeriodTotals(
    if (days.none { it.focusedMs != null }) "No data" else FocusHistoryText.duration(days.sumOf { it.focusedMs ?: 0 }),
    days.sumOf { it.completed }, days.sumOf { it.endedEarly },
)

/** Independent of Usage Access: these are app-owned session events, never screen usage. */
@Composable
internal fun FocusSessionsSection() {
    if (LocalInspectionMode.current) { FlintSectionLabel("Focus sessions"); return }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    val history by remember(context) { FocusHistoryBridge.summaries(context) }.collectAsStateWithLifecycle(initialValue = null)
    var week by rememberSaveable { mutableStateOf(true) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val summary = history
    FlintCard {
        FlintSectionLabel("Focus sessions")
        Text(FocusHistoryText.DEFINITION, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        if (summary == null || summary.availability == HistoryAvailability.UNAVAILABLE) {
            Text(FocusHistoryText.today(summary), modifier = Modifier.padding(top = 12.dp))
            if (summary?.availability == HistoryAvailability.UNAVAILABLE) Button(enabled = !retrying, onClick = {
                retrying = true
                scope.launch { FocusHistoryBridge.retry(context); retrying = false }
            }) { Text(if (retrying) "Retrying…" else "Retry focus history") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !week, onClick = { week = false }, label = { Text("Day") })
                FilterChip(selected = week, onClick = { week = true }, label = { Text("Week") })
            }
            if (summary.availability == HistoryAvailability.EMPTY) Text("No focus sessions yet", modifier = Modifier.padding(vertical = 8.dp))
            val selected = summary.days.firstOrNull { it.date.toString() == selectedDate } ?: summary.days.lastOrNull()
            val shown = if (week) summary.days else listOfNotNull(selected)
            val totals = focusPeriodTotals(shown)
            Text(totals.focused, style = MaterialTheme.typography.headlineMedium)
            Text("${totals.completed} completed · ${totals.endedEarly} ended early", style = MaterialTheme.typography.bodyMedium)
            Text(if (week) FocusHistoryText.weekLabel(summary.days) else selected?.date?.format(DateTimeFormatter.ofPattern("EEE, MMM d")) ?: "Today",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            val maximum = maxOf(1L, summary.days.maxOfOrNull { it.focusedMs ?: 0 } ?: 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                summary.days.forEach { day ->
                    val label = day.date.format(DateTimeFormatter.ofPattern("EEEEE"))
                    val value = day.focusedMs?.let(FocusHistoryText::duration) ?: "No data"
                    Column(Modifier.weight(1f).selectable(selected = day == selected && !week, role = Role.Tab,
                        onClick = { selectedDate = day.date.toString(); week = false })
                        .semantics { contentDescription = "${day.date}, $value focused, ${day.completed} completed, ${day.endedEarly} ended early" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (day.focusedMs == null) "—" else value, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        Box(Modifier.height(72.dp), contentAlignment = Alignment.BottomCenter) {
                            // No bar at all for dates before historyStart. A true zero gets a small baseline.
                            day.focusedMs?.let { ms ->
                                Box(Modifier.width(16.dp).height(if (ms == 0L) 2.dp else maxOf(3f, 68f * ms / maximum).dp)
                                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small))
                            }
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (summary.streak > 0) Text("${summary.streak}-day completion streak", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            FocusHistoryText.note(summary, shown)?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
    Spacer(Modifier.height(FlintSpacing.cardGap))
    FlintSectionLabel("Screen usage")
    Spacer(Modifier.height(FlintSpacing.sm))
}
