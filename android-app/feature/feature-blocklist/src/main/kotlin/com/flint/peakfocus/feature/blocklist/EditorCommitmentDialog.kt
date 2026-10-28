package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.QueuedEditorChange
import java.text.DateFormat
import java.util.Date

@Composable
internal fun EditorCommitmentDialog(state: EditorCommitmentState, remainingMs: Long?, busy: Boolean,
    error: String?, onConfirm: (Long) -> Unit, onCancel: (Long) -> Unit) {
    val pending = state.pending ?: return
    val waiting = pending.outcome == CommitmentPolicy.Outcome.WAIT
    val deferred = pending.outcome == CommitmentPolicy.Outcome.DEFER
    val end = pending.change.keys.mapNotNull { state.causes[it]?.interval?.wallEndEpochMs }.maxOrNull()
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel(pending.id) },
        title = { Text(pending.change.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pending.expected.rules.forEach { rule -> Text("${rule.name} · ${breakLevelLabel(rule.breakLevel)}") }
                pending.expected.time?.let { Text("Time Limit · ${breakLevelLabel(it.breakLevel)} · ${it.dailyMinutes} minutes per day") }
                pending.expected.open?.let { Text("Open Limit · ${breakLevelLabel(it.breakLevel)} · ${it.dailyOpens} daily credits") }
                pending.change.upserts.forEach { rule ->
                    val window = rule.expiresAtEpochMs?.let { "Until ${editorEndLabel(it)}" } ?: scheduleSummary(rule.schedule)
                    Text("Save ${rule.name} · ${breakLevelLabel(rule.breakLevel)} · $window · ${targetsSummary(rule.targets)}")
                }
                pending.change.limits?.let { limits ->
                    Text("Save: ${limitSummary(limits.time, limits.open)}")
                }
                if (end != null) Text("Current occurrence ends ${editorEndLabel(end)}.")
                if (state.queued.any { it.change.keys.intersect(pending.change.keys).isNotEmpty() }) {
                    Text("Confirming replaces the earlier queued change for this item.")
                }
                Text(when {
                    deferred -> "Protected until ${end?.let(::editorEndLabel) ?: "this occurrence ends"}. Save for after this occurrence. The effective protection stays in place."
                    waiting && remainingMs == null -> "Protection state unavailable. Restart this request to check the wait."
                    waiting && remainingMs != null && remainingMs > 0 -> "Available in ${(remainingMs + 59_999) / 60_000} minutes. Confirm the change when the wait finishes."
                    waiting -> "The wait is complete. Confirm to apply this change."
                    pending.outcome == CommitmentPolicy.Outcome.ALLOW_AFTER_DISCLOSURE -> "Hardcore prevents ordinary early changes in Flint until the occurrence ends. An available Emergency Pass provides recovery. Confirm these new rights."
                    else -> "Confirm this change to the saved protection."
                })
                Text("Emergency Pass status is available in recovery. OS permission and recovery controls stay reachable.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pending.id) }, enabled = !busy && (!waiting || remainingMs == 0L)) {
            Text(if (deferred) "Save for after this occurrence" else "Confirm")
        } },
        dismissButton = { TextButton(onClick = { onCancel(pending.id) }, enabled = !busy) { Text("Cancel") } },
    )
}

internal fun editorEndLabel(epochMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMs))

@Composable
internal fun QueuedEditorChanges(changes: List<QueuedEditorChange>) {
    changes.forEach { queued ->
        val end = queued.boundaries.values.maxOf { it.wallEndEpochMs }
        Text("Queued separately: ${queued.change.label}. Applies after ${editorEndLabel(end)}.", style = MaterialTheme.typography.bodySmall)
    }
}
