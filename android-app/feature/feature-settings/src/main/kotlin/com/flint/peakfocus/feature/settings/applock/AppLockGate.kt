@file:OptIn(ExperimentalComposeUiApi::class)

package com.flint.peakfocus.feature.settings.applock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.BoneButton
import com.flint.peakfocus.core.common.ui.EmberButton
import com.flint.peakfocus.core.common.ui.EmberLandscape
import com.flint.peakfocus.core.common.ui.FlintMark
import com.flint.peakfocus.core.common.ui.FlintSectionTitle
import com.flint.peakfocus.core.common.ui.QuietButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app-open PIN gate (F03, Android): the iOS `PINGateView` + `LockedRecoveryView` pair.
 *
 * [content] is always composed, so a lock that happens later keeps every `rememberSaveable`
 * value (selected tab, drafts) alive. While the gate is visible the content is invisible to
 * TalkBack ([clearAndSetSemantics]) and undrawn (`alpha = 0f`) in the same composition pass —
 * never a second window that could appear a frame late — and this gate draws on top of it with
 * opaque paint and a pointer-input consumer so nothing behind it can be tapped.
 *
 * The PIN protects private app content only (ADR-012 §7, PC-50). [onOpenRecovery] is the
 * Emergency Pass / protection-recovery surface the block screens already open without a PIN;
 * this gate never adds a direct Accessibility-settings hand-off (ADR-007). Its only OS route is
 * Flint's own App info page, which the OS always allows.
 */
@Composable
fun AppLockGate(
    controller: AppLockController,
    onOpenRecovery: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state by controller.state.collectAsState()
    val gateVisible = state.gateVisible
    var recoveryOpen by remember { mutableStateOf(false) }
    var recoveryAtForgot by remember { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Every newly shown gate starts at the PIN, never on a stale recovery panel, and a field that
    // was focused behind it must not keep the keyboard up over the gate.
    LaunchedEffect(gateVisible) {
        recoveryOpen = false
        recoveryAtForgot = false
        if (gateVisible) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (gateVisible) 0f else 1f }
                .then(if (gateVisible) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            content()
        }
        if (gateVisible) {
            PinGateScreen(
                controller = controller,
                state = state,
                recoveryOpen = recoveryOpen,
                recoveryAtForgot = recoveryAtForgot,
                onOpenRecoveryPanel = { forgot ->
                    recoveryOpen = true
                    recoveryAtForgot = forgot
                },
                onCloseRecoveryPanel = { recoveryOpen = false },
                onOpenRecovery = onOpenRecovery,
            )
        }
    }

    // Composed after the content so the gate's Back wins over every feature BackHandler beneath
    // it: an open recovery panel closes first, otherwise the task leaves the app.
    BackHandler(enabled = gateVisible) {
        if (recoveryOpen) {
            recoveryOpen = false
        } else {
            activity?.moveTaskToBack(true)
        }
    }
}

/**
 * The gate's own surface. It is a sibling above [AppLockGate]'s content, so touches land here
 * first; the container also consumes every pointer change so a stray event cannot fall through.
 *
 * Hardware-keyboard/D-pad focus is confined to the gate: the container is a focus group whose
 * enter/exit exits are cancelled, so traversal cannot reach the invisible content behind it.
 */
@Composable
private fun PinGateScreen(
    controller: AppLockController,
    state: AppLockState,
    recoveryOpen: Boolean,
    recoveryAtForgot: Boolean,
    onOpenRecoveryPanel: (forgot: Boolean) -> Unit,
    onCloseRecoveryPanel: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrongPin by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var announceLockout by remember { mutableStateOf(false) }
    val lockedOut = state.lockoutRemainingMs > 0

    lockoutTicker(remaining = { state.lockoutRemainingMs }, refresh = controller::refreshLockout)

    // Announce the lockout once when it starts; the per-second countdown stays silent.
    LaunchedEffect(lockedOut) {
        if (lockedOut) {
            announceLockout = true
            delay(2_000)
            announceLockout = false
        } else {
            announceLockout = false
        }
    }

    fun submit() {
        if (pin.isEmpty() || state.busy || lockedOut) return
        wrongPin = false
        unavailable = false
        scope.launch {
            when (controller.unlock(pin)) {
                PinAttempt.Success -> pin = ""
                is PinAttempt.Wrong -> {
                    pin = ""
                    wrongPin = true
                }
                is PinAttempt.LockedOut -> {
                    pin = ""
                    controller.refreshLockout()
                }
                PinAttempt.Unavailable -> {
                    pin = ""
                    unavailable = true
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FlintPalette.Ground)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .focusGroup()
            .focusProperties {
                enter = { FocusRequester.Cancel }
                exit = { FocusRequester.Cancel }
            },
    ) {
        EmberLandscape(horizonOffset = 20f, orbCenter = Offset(195f, 470f), orbRadius = 64f)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .then(if (recoveryOpen) Modifier.clearAndSetSemantics {} else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            FlintMark(Modifier.size(44.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                "Flint",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 44.sp, letterSpacing = (-1.0).sp),
                color = FlintPalette.Bone,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Enter PIN",
                style = MaterialTheme.typography.titleMedium,
                color = FlintPalette.Mist,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))

            if (state.pinStatus == PinStatus.Unreadable) {
                Text(
                    "Flint can't read its saved PIN. Your blocks keep running.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlintPalette.Mist,
                    textAlign = TextAlign.Center,
                )
            } else {
                OutlinedTextField(
                    value = pin,
                    // Never trimmed or filtered: the exact typed PIN is verified or refused.
                    onValueChange = { typed ->
                        if (typed.length <= AppPinPolicy.MAX_ENTRY_LENGTH) pin = typed
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("PIN") },
                    singleLine = true,
                    enabled = !state.busy,
                    isError = wrongPin,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                if (wrongPin) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Incorrect PIN",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlintPalette.Danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (lockedOut) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        lockoutText(state.lockoutRemainingMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlintPalette.Danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            if (announceLockout) liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (unavailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        unavailableText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlintPalette.Danger,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.md))
                EmberButton(
                    text = "Unlock",
                    onClick = { submit() },
                    enabled = pin.isNotEmpty() && !state.busy && !lockedOut,
                )
            }

            Spacer(Modifier.height(FlintSpacing.md))
            // A quiet text action that carries its own spoken click label; `QuietButton` has no
            // label parameter and the shared kit stays untouched for one screen.
            RecoveryEntry(
                text = "Emergency Pass & recovery",
                onClickLabel = "Open recovery without your PIN",
                onClick = { onOpenRecoveryPanel(false) },
            )
            Spacer(Modifier.height(FlintSpacing.xs))
            QuietButton(
                text = "Forgot PIN?",
                onClick = { onOpenRecoveryPanel(true) },
            )
            Spacer(Modifier.height(FlintSpacing.lg))
        }

        if (recoveryOpen) {
            LockedRecoveryPanel(
                startAtForgot = recoveryAtForgot,
                onOpenRecovery = onOpenRecovery,
                onClose = onCloseRecoveryPanel,
            )
        }
    }
}

/**
 * The locked recovery panel: on-device recovery that never needs the PIN (ADR-012 §7, PC-50).
 * It shows no private content — no rule or session names, apps, sites or history — and it is an
 * overlay inside the gate, not a dialog or activity, so a re-lock discards it.
 */
@Composable
private fun LockedRecoveryPanel(
    startAtForgot: Boolean,
    onOpenRecovery: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // "Forgot PIN?" opens the same panel at the section that answers it.
    LaunchedEffect(startAtForgot) {
        if (startAtForgot) {
            withFrameNanos { }
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(FlintPalette.Ground)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(FlintSpacing.md),
        ) {
            Text(
                "While Flint is locked",
                style = MaterialTheme.typography.headlineSmall,
                color = FlintPalette.Bone,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Emergency Pass and protection recovery never need your PIN.",
                style = MaterialTheme.typography.bodyLarge,
                color = FlintPalette.SoftBone,
            )
            EmberButton(text = "Open Emergency Pass & recovery", onClick = onOpenRecovery)
            Text(
                "Recovery opens outside the PIN. It lists your active commitments so you can " +
                    "choose what to pause.",
                style = MaterialTheme.typography.bodySmall,
                color = FlintPalette.Mist,
            )

            FlintSectionTitle(
                "Turn Flint off without your PIN",
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Android Settings always works. Turn off Flint under Settings › Accessibility, " +
                    "or uninstall Flint from its App info page. Flint can't prevent either, and " +
                    "neither needs your PIN.",
                style = MaterialTheme.typography.bodyMedium,
                color = FlintPalette.Mist,
            )
            BoneButton(text = "Open Flint's App info", onClick = { openAppInfo(context) })

            FlintSectionTitle("Forgot your PIN?", modifier = Modifier.semantics { heading() })
            Text(
                "Flint can't show or reset your PIN; it keeps only a scrambled copy on this " +
                    "device. To remove it, clear Flint's storage from its App info page " +
                    "(Storage › Clear storage) or uninstall Flint. That also deletes all of " +
                    "Flint's rules, sessions and history on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = FlintPalette.Mist,
            )

            Spacer(Modifier.height(FlintSpacing.sm))
            QuietButton(text = "Back to PIN", onClick = onClose)
            Spacer(Modifier.height(FlintSpacing.lg))
        }
    }
}

/** A quiet text action with a spoken click label (the shared kit's QuietButton has none). */
@Composable
private fun RecoveryEntry(
    text: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = FlintPalette.Bone,
            textAlign = TextAlign.Center,
        )
    }
}

/** Recomputes the lockout about once a second while one is running, and not at all otherwise. */
@Composable
internal fun lockoutTicker(remaining: () -> Long, refresh: () -> Unit) {
    val active = remaining() > 0
    LaunchedEffect(active) {
        while (remaining() > 0) {
            delay(1_000)
            refresh()
        }
    }
}

/** "Too many attempts. Try again in m:ss." — milliseconds round up so 0:00 never shows. */
internal fun lockoutText(remainingMs: Long): String {
    val totalSeconds = ((remainingMs + 999L) / 1_000L).coerceAtLeast(0L)
    return "Too many attempts. Try again in ${totalSeconds / 60}:${
        (totalSeconds % 60).toString().padStart(2, '0')
    }."
}

/** Shown when a verification cannot run at all; blocks keep running either way. */
internal const val unavailableText =
    "Flint couldn't check your PIN. Your blocks keep running. Use Emergency Pass & recovery below."

/** Flint's own App info page: the OS route that always works, never Accessibility Settings. */
internal fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The hosting Activity through any ContextWrapper chain (null outside an Activity). */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
