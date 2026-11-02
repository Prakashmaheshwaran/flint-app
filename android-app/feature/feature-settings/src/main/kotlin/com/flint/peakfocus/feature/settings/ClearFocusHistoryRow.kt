package com.flint.peakfocus.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.FocusHistoryText
import kotlinx.coroutines.launch

@Composable
internal fun ClearFocusHistoryRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column {
        Text(FocusHistoryText.DEFINITION, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { confirming = true; message = null }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Clear focus history")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { if (!busy) confirming = false },
        title = { Text("Clear focus history?") },
        text = { Text("This clears recorded focus history on this device. A running session is kept so its completion can still be recorded.") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val success = FocusHistoryBridge.clear(context)
                    busy = false
                    if (success) confirming = false
                    message = if (success) "Focus history cleared" else "Couldn't clear focus history. Try again."
                }
            }) { Text(if (busy) "Clearing…" else "Clear history") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirming = false }) { Text("Cancel") } },
    )
}
