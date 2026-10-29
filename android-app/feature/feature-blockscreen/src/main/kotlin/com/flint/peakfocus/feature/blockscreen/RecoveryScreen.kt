package com.flint.peakfocus.feature.blockscreen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role

data class RecoveryChoice(val key: String, val name: String)
data class RecoveryConfirmation(val names: List<String>, val resumeAt: String, val nextPassAt: String)
data class RecoveryScreenState(
    val loading: Boolean,
    val unavailable: Boolean,
    val available: Boolean,
    val remainingRecoveryMillis: Long = 0,
    val resumeAt: String? = null,
    val nextPassAt: String? = null,
    val timeUncertain: Boolean = false,
    val previousAccessEnded: Boolean = false,
    val choices: List<RecoveryChoice> = emptyList(),
    val confirmation: RecoveryConfirmation? = null,
    val endConfirmation: List<String>? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val activeOrdinaryBreaks: Int = 0,
    val activeOpenCredits: Int = 0,
)

/** Independent in-Flint recovery. Navigation and preview never consume a pass. */
@Composable
fun RecoveryScreen(
    state: RecoveryScreenState,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onConfirm: () -> Unit,
    onCancelConfirmation: () -> Unit,
    onResume: () -> Unit,
    onPreviewEnd: (Set<String>, Boolean) -> Unit,
    onConfirmEnd: () -> Unit,
    onRetry: () -> Unit,
    onResumeBreaks: () -> Unit,
    onResumeCredits: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val allowed = state.choices.map { it.key }.toSet()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack) { Text("Back to Flint") }
            Text("Protection recovery", style = MaterialTheme.typography.headlineMedium)
            Text("One Emergency Pass becomes available seven days after you use it. It starts 60 minutes of recovery across all personal protection.")
            if (state.loading || state.saving) CircularProgressIndicator()
            if (state.unavailable) {
                Text("Protection state unavailable", color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry, enabled = !state.saving) { Text("Try again") }
            }
            if (state.previousAccessEnded) Text("Access ended after restart; no extra credit was spent.")
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.activeOrdinaryBreaks > 0) {
                Text("Active ordinary breaks: ${state.activeOrdinaryBreaks}. A new request cannot extend them.")
                OutlinedButton(onClick = onResumeBreaks, enabled = !state.saving) { Text("Resume protection for ordinary breaks") }
            }
            if (state.activeOpenCredits > 0) {
                Text("Active credit intervals: ${state.activeOpenCredits}. Ending access does not refund credits.")
                OutlinedButton(onClick = onResumeCredits, enabled = !state.saving) { Text("End credit access now") }
            }
            if (state.remainingRecoveryMillis > 0) {
                Text("Recovery ends in ${formatDuration(state.remainingRecoveryMillis)}", style = MaterialTheme.typography.titleLarge)
                state.resumeAt?.let { Text("Protection resumes: $it") }
                Text("Only commitments still naturally active will resume. Counters and the next pass time stay unchanged.")
                Button(onClick = onResume, enabled = !state.saving) { Text("Resume protection now") }
                if (state.choices.isNotEmpty()) {
                    Text("Choose commitments to end or disable before resuming:")
                    state.choices.forEach { choice ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = choice.key in selected,
                            enabled = !state.saving, role = Role.Checkbox,
                            onValueChange = { checked -> selected = if (checked) selected + choice.key else selected - choice.key })) {
                            Checkbox(checked = choice.key in selected, enabled = !state.saving, onCheckedChange = null)
                            Text(choice.name, Modifier.padding(top = 12.dp))
                        }
                    }
                    val selection = selected.intersect(allowed)
                    OutlinedButton(onClick = { onPreviewEnd(selection, false) }, enabled = selection.isNotEmpty() && !state.saving) {
                        Text("End or disable selected and resume")
                    }
                    TextButton(onClick = { onPreviewEnd(selection, true) }, enabled = selection.isNotEmpty() && !state.saving) {
                        Text("Delete selected templates and resume")
                    }
                }
            } else if (state.available) {
                Button(onClick = onPreview, enabled = !state.saving) { Text("Review Emergency Pass") }
            }
            state.nextPassAt?.let { Text("Next pass: $it") }
            if (state.timeUncertain) Text("Time is uncertain after a clock change or restart. Eligibility may be later than seven calendar days.")
        }
    }
    state.confirmation?.let { preview ->
        AlertDialog(onDismissRequest = { if (!state.saving) onCancelConfirmation() }, title = { Text("Confirm global recovery") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("End these active sessions and suspend these rule commitments:")
                    preview.names.forEach { Text("• $it") }
                    Text("Estimated protection resume: ${preview.resumeAt}")
                    Text("Estimated next pass: ${preview.nextPassAt}")
                    Text("New schedule occurrences are also suspended during recovery. Counters are kept; the pass is not refunded.")
                }
            },
            // A separate review action followed by this named final confirmation is the
            // accessible explicit-confirmation alternative to a two-second hold (ADR-012 §6).
            confirmButton = { TextButton(onClick = onConfirm, enabled = !state.saving) { Text("Confirm 60 minutes of recovery") } },
            dismissButton = { TextButton(onClick = onCancelConfirmation, enabled = !state.saving) { Text("Cancel") } })
    }
    state.endConfirmation?.let { names ->
        AlertDialog(onDismissRequest = { if (!state.saving) onCancelConfirmation() }, title = { Text("Confirm these commitments") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                names.forEach { Text("• $it") }
                Text("Only this selection changes. Counters and history are kept. Other active commitments resume now.")
            } },
            confirmButton = { TextButton(onClick = onConfirmEnd, enabled = !state.saving) { Text("Confirm selection and resume") } },
            dismissButton = { TextButton(onClick = onCancelConfirmation, enabled = !state.saving) { Text("Cancel") } })
    }
}
