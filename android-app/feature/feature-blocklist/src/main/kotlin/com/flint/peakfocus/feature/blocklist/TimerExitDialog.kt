package com.flint.peakfocus.feature.blocklist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Own visual treatment; phase durations and release semantics come from the APK trace. */
@Composable
internal fun TimerExitDialog(confirmation: TimerExitConfirmation, viewModel: TimerViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissExit,
        properties = DialogProperties(
            dismissOnBackPress = confirmation.canDismiss,
            dismissOnClickOutside = confirmation.canDismiss,
        ),
        title = { Text("Leave early?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("End ${confirmation.expected.name}? Other block rules and limits will stay active.")
                if (confirmation.phase == TimerExitPhase.WAITING) {
                    Text(confirmation.waitRemainingMs?.let { "Available in ${(it + 59_999) / 60_000} minutes. Then hold for 2 seconds to confirm." }
                        ?: "Wait unavailable. Cancel and start a new request; the Timer is still active.")
                } else {
                    Text("Hold for 2 seconds. Release before it fills to keep focusing.", style = MaterialTheme.typography.bodySmall)
                    HoldToEndControl(confirmation, viewModel)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = viewModel::dismissExit, enabled = confirmation.canDismiss) {
                Text("Never mind")
            }
        },
    )
}

@Composable
private fun HoldToEndControl(confirmation: TimerExitConfirmation, viewModel: TimerViewModel) {
    val phase = confirmation.phase
    val scale by animateFloatAsState(
        targetValue = if (phase == TimerExitPhase.HOLDING) 0.94f else 1f,
        animationSpec = tween(if (phase == TimerExitPhase.HOLDING) TIMER_EXIT_COLLAPSE_MS else TIMER_EXIT_FINISH_MS.toInt()),
        label = "Timer hold compression",
    )
    var keyboardOwnsHold by remember { mutableStateOf(false) }
    val description = when (phase) {
        TimerExitPhase.IDLE -> "Hold for 2 seconds. Accessibility activation starts a cancellable two-second countdown."
        TimerExitPhase.HOLDING -> "Confirming. Release, or choose Never mind, to cancel."
        else -> "Ending this timer."
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .graphicsLayer { scaleX = scale }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = description
                // Switch Access/TalkBack do not have to sustain a raw pointer. The same
                // cancellable duration still runs; no accessibility action deletes directly.
                onClick(label = "Start end-timer countdown") { viewModel.beginExitHold() }
            }
            .onKeyEvent { event ->
                if (event.key !in listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)) false
                else {
                    if (event.type == KeyEventType.KeyDown && !keyboardOwnsHold) {
                        keyboardOwnsHold = viewModel.beginExitHold()
                    } else if (event.type == KeyEventType.KeyUp && keyboardOwnsHold) {
                        keyboardOwnsHold = false
                        viewModel.releaseExitHold()
                    }
                    true
                }
            }
            .onFocusChanged {
                if (!it.isFocused && keyboardOwnsHold) {
                    keyboardOwnsHold = false
                    viewModel.releaseExitHold()
                }
            }
            .focusable()
            .pointerInput(viewModel) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    val ownsHold = viewModel.beginExitHold()
                    try {
                        waitForUpOrCancellation()?.consume()
                    } finally {
                        if (ownsHold) viewModel.releaseExitHold()
                    }
                }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (phase) {
                    TimerExitPhase.IDLE -> "Hold to leave"
                    TimerExitPhase.HOLDING -> "Keep holding…"
                    else -> "Ending…"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            LinearProgressIndicator(progress = { confirmation.progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}
