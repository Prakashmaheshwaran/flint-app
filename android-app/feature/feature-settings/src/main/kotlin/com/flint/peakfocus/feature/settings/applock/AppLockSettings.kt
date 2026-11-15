@file:OptIn(ExperimentalComposeUiApi::class)

package com.flint.peakfocus.feature.settings.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintRow
import kotlinx.coroutines.launch

/**
 * Display state plus the controller actions for the "App lock" settings section.
 *
 * The section itself is stateless: `SettingsScreen` turns the controller's [AppLockState] into
 * this snapshot, and tests/previews can build one over an in-memory controller.
 */
class AppLockSettingsUi internal constructor(
    val pinStatus: PinStatus,
    val busy: Boolean,
    val lockoutRemainingMs: Long,
    val epoch: Int,
    val locked: Boolean,
    val savePin: suspend (String) -> Boolean,
    val authorizeChange: suspend (String) -> PinAttempt,
    val removePin: suspend (String) -> PinAttempt,
    val cancelChange: () -> Unit,
    val refreshLockout: () -> Unit,
) {
    companion object {
        /** Snapshot of [controller] for one composition. */
        fun from(controller: AppLockController, state: AppLockState = controller.state.value): AppLockSettingsUi =
            AppLockSettingsUi(
                pinStatus = state.pinStatus,
                busy = state.busy,
                lockoutRemainingMs = state.lockoutRemainingMs,
                epoch = state.epoch,
                locked = state.locked,
                savePin = controller::savePin,
                authorizeChange = controller::authorizeChange,
                removePin = controller::removePin,
                cancelChange = controller::cancelChange,
                refreshLockout = controller::refreshLockout,
            )
    }
}

private enum class AppLockDialog { None, Set, Change, Remove }

/**
 * "App PIN" row and its set/change/remove flows (the Android port of the iOS App lock rows).
 *
 * Setting a PIN from Off needs nothing; changing or removing one always asks for the current PIN
 * in this foreground visit — the controller refuses an unauthorized replace, so the dialog is a
 * consequence of that rule, not a substitute for it. Re-locking (`epoch` change, `locked`) throws
 * away any open dialog and its typed digits: they live in plain `remember`, never in saved state.
 */
@Composable
fun AppLockSettingsSection(ui: AppLockSettingsUi) {
    var dialog by remember { mutableStateOf(AppLockDialog.None) }
    var seenEpoch by remember { mutableStateOf(ui.epoch) }

    lockoutTicker(remaining = { ui.lockoutRemainingMs }, refresh = ui.refreshLockout)

    LaunchedEffect(ui.epoch, ui.locked, ui.pinStatus) {
        val invalidated = ui.epoch != seenEpoch
        seenEpoch = ui.epoch
        if (invalidated || ui.locked || ui.pinStatus == PinStatus.Unreadable) {
            if (dialog != AppLockDialog.None) {
                dialog = AppLockDialog.None
                ui.cancelChange()
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        FlintRow(
            title = "App PIN",
            subtitle = "Ask for a PIN when Flint opens. It hides your rules and history, never " +
                "Emergency Pass or recovery.",
            value = when (ui.pinStatus) {
                PinStatus.On -> "On"
                PinStatus.Off -> "Off"
                PinStatus.Unreadable -> "Can't read"
            },
        )
        when (ui.pinStatus) {
            PinStatus.Off -> OutlinedButton(
                onClick = { dialog = AppLockDialog.Set },
                enabled = !ui.locked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Set PIN")
            }

            PinStatus.On -> {
                OutlinedButton(
                    onClick = {
                        ui.cancelChange()
                        dialog = AppLockDialog.Change
                    },
                    enabled = !ui.locked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Change PIN")
                }
                Spacer(Modifier.height(FlintSpacing.xs))
                OutlinedButton(
                    onClick = {
                        ui.cancelChange()
                        dialog = AppLockDialog.Remove
                    },
                    enabled = !ui.locked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Remove PIN")
                }
            }

            PinStatus.Unreadable -> Text(
                "Flint can't read its saved PIN. To remove it, clear Flint's storage in Android " +
                    "Settings; that deletes all Flint data.",
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
            )
        }
    }

    when (dialog) {
        AppLockDialog.None -> Unit

        AppLockDialog.Set -> SetPinDialog(
            onCancel = {
                ui.cancelChange()
                dialog = AppLockDialog.None
            },
            onSave = { candidate -> ui.savePin(candidate) },
            onSaved = { dialog = AppLockDialog.None },
        )

        AppLockDialog.Change -> PinChallengeDialog(
            title = "Change PIN",
            ui = ui,
            submit = { current -> ui.authorizeChange(current) },
            onSuccess = { dialog = AppLockDialog.Set },
            onCancel = {
                ui.cancelChange()
                dialog = AppLockDialog.None
            },
        )

        AppLockDialog.Remove -> PinChallengeDialog(
            title = "Remove PIN",
            ui = ui,
            submit = { current -> ui.removePin(current) },
            onSuccess = { dialog = AppLockDialog.None },
            onCancel = { dialog = AppLockDialog.None },
        )
    }
}

/** New-PIN entry only: the current PIN, when there is one, was proved by the caller before. */
@Composable
private fun SetPinDialog(
    onCancel: () -> Unit,
    onSave: suspend (String) -> Boolean,
    onSaved: () -> Unit,
) {
    // Plain remember on purpose: a typed PIN must never reach the saved-instance Bundle.
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val canSave = AppPinPolicy.isValidNewPin(pin) && pin == confirmation && !saving

    fun save() {
        if (!canSave) return
        saving = true
        saveFailed = false
        scope.launch {
            val saved = onSave(pin)
            saving = false
            if (saved) {
                pin = ""
                confirmation = ""
                onSaved()
            } else {
                saveFailed = true
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Set PIN") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm),
            ) {
                Text(
                    "Choose 4–6 digits (0–9), then enter them again to confirm.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlintPalette.Mist,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { typed ->
                        if (typed.length <= AppPinPolicy.MAX_ENTRY_LENGTH) {
                            pin = typed
                            saveFailed = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New PIN") },
                    singleLine = true,
                    enabled = !saving,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { typed ->
                        if (typed.length <= AppPinPolicy.MAX_ENTRY_LENGTH) {
                            confirmation = typed
                            saveFailed = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    enabled = !saving,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                if (pin.isNotEmpty() && !AppPinPolicy.isValidNewPin(pin)) {
                    DialogError("Use 4–6 digits (0–9), with no spaces.")
                }
                if (confirmation.isNotEmpty() && pin != confirmation) {
                    DialogError("PINs don’t match. Enter the same PIN twice.")
                }
                if (saveFailed) {
                    DialogError("Couldn’t save your PIN. Please try again.")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { save() }) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onCancel) { Text("Cancel") }
        },
    )
}

/** Current-PIN challenge before a change or a removal; matches the gate's wording and throttle. */
@Composable
private fun PinChallengeDialog(
    title: String,
    ui: AppLockSettingsUi,
    submit: suspend (String) -> PinAttempt,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var wrongPin by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lockedOut = ui.lockoutRemainingMs > 0
    val canSubmit = pin.isNotEmpty() && !checking && !ui.busy && !lockedOut

    fun verify() {
        if (!canSubmit) return
        checking = true
        wrongPin = false
        unavailable = false
        scope.launch {
            when (val result = submit(pin)) {
                PinAttempt.Success -> {
                    pin = ""
                    checking = false
                    onSuccess()
                    return@launch
                }
                is PinAttempt.Wrong -> {
                    pin = ""
                    wrongPin = true
                }
                is PinAttempt.LockedOut -> {
                    pin = ""
                    ui.refreshLockout()
                }
                PinAttempt.Unavailable -> {
                    pin = ""
                    unavailable = true
                }
            }
            checking = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!checking) onCancel() },
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm),
            ) {
                Text(
                    "Enter your current PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlintPalette.Mist,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { typed ->
                        if (typed.length <= AppPinPolicy.MAX_ENTRY_LENGTH) {
                            pin = typed
                            wrongPin = false
                            unavailable = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Current PIN") },
                    singleLine = true,
                    enabled = !checking,
                    isError = wrongPin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { verify() }),
                )
                if (wrongPin) {
                    DialogError("Incorrect PIN")
                }
                if (lockedOut) {
                    Text(
                        lockoutText(ui.lockoutRemainingMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlintPalette.Danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (unavailable) {
                    Text(
                        "Flint couldn't check your PIN. Please try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlintPalette.Danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = { verify() }) { Text("Continue") }
        },
        dismissButton = {
            TextButton(enabled = !checking, onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun DialogError(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = FlintPalette.Danger,
        modifier = Modifier
            .padding(top = 2.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
