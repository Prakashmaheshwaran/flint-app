package com.flint.peakfocus.feature.blockscreen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.BreakLevel

/**
 * The shared, branded lock screen shown when Flint blocks the foreground app.
 *
 * Pure UI — no service logic, no persistence, no internal clocks — so the exact same surface
 * can be hosted by the accessibility overlay (a ComposeView in a `TYPE_ACCESSIBILITY_OVERLAY`
 * window) or by the full-screen `BlockActivity`. A-SERVICE-INTEGRATION owns the hosting.
 * Hosts should wrap it in [FlintTheme] (dark is the brand-primary look for block screens);
 * every color/typographic value here comes from the theme — nothing is hard-coded.
 *
 * Contract with the host:
 * - [state] is display data only. The host owns the clock: to tick the countdowns it re-renders
 *   with a fresh [BlockScreenState] (e.g. once per second while visible).
 * - [onDismiss] — user chose to leave ("Go back"). Host hides the surface and sends the user
 *   home (`GLOBAL_ACTION_HOME` / `finish()`).
 * - [onOpenFlint] — user tapped "Open Flint". Host launches the Flint app.
 * - [onBreakRequested] — user asked for a break: at [BreakLevel.EASY] by tapping
 *   "Take a break", at [BreakLevel.HARDER] by completing the hold-to-request gesture.
 *   Never invoked at [BreakLevel.HARDCORE]. Granting, timing, and cooldown accounting are
 *   host decisions; a HARDER cooldown comes back in via
 *   [BlockScreenState.breakWaitRemainingMillis].
 * - [onEmergencyPassRequested] — [BreakLevel.HARDCORE] only: user tapped the Emergency Pass
 *   CTA. The host owns pass accounting (one per week, free in Flint) and whether to grant it.
 *
 * Break-level behaviour:
 * - EASY — a visible "Take a break" button.
 * - HARDER — hold-to-request friction; while the host reports a pending cooldown
 *   ([BlockScreenState.breakWaitRemainingMillis] > 0) the control is replaced by a wait notice.
 * - HARDCORE — no bypass affordance at all; only the Emergency Pass CTA.
 */
@Composable
fun BlockScreen(
    state: BlockScreenState,
    onDismiss: () -> Unit,
    onOpenFlint: () -> Unit,
    onBreakRequested: () -> Unit,
    onEmergencyPassRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = blockScreenContent(state)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "PEAK FOCUS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = content.headline,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = content.reasonLine,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (state.remainingMillis != null) {
                Spacer(Modifier.height(20.dp))
                InfoPill(text = "Unblocks in ${formatDuration(state.remainingMillis)}")
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = content.encouragement,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Go back")
                }
                BreakActions(
                    affordance = content.breakAffordance,
                    onBreakRequested = onBreakRequested,
                    onEmergencyPassRequested = onEmergencyPassRequested,
                )
                TextButton(onClick = onOpenFlint, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Flint")
                }
            }
        }
    }
}

/** The break-level-aware action row beneath the primary "Go back" button. */
@Composable
private fun BreakActions(
    affordance: BreakAffordance,
    onBreakRequested: () -> Unit,
    onEmergencyPassRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (affordance) {
        BreakAffordance.TakeABreak -> OutlinedButton(
            onClick = onBreakRequested,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text("Take a break")
        }

        is BreakAffordance.HoldToRequest -> HoldToRequestBreakButton(
            holdMillis = affordance.holdMillis,
            onHoldCompleted = onBreakRequested,
            modifier = modifier.fillMaxWidth(),
        )

        is BreakAffordance.WaitBeforeBreak -> InfoPill(
            text = "Break available in ${formatDuration(affordance.remainingMillis)}",
            modifier = modifier,
        )

        BreakAffordance.EmergencyPassOnly -> Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Hardcore session — no breaks, no early stop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onEmergencyPassRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use an Emergency Pass")
            }
        }
    }
}

/**
 * HARDER-level friction: press and hold for [holdMillis] to fire [onHoldCompleted]. The fill
 * animation is driven purely by the press gesture (no free-running timer); releasing early
 * drains it back to zero.
 */
@Composable
private fun HoldToRequestBreakButton(
    holdMillis: Long,
    onHoldCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) {
            tween(durationMillis = holdMillis.toInt(), easing = LinearEasing)
        } else {
            tween(durationMillis = 200)
        },
        label = "holdToBreakProgress",
        finishedListener = { value ->
            if (value >= 1f) {
                pressed = false
                onHoldCompleted()
            }
        },
    )
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.primary, shape = shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
        )
        Text(
            text = if (pressed) "Keep holding…" else "Hold to request a break",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A calm rounded pill for passive info (countdowns, wait notices). */
@Composable
private fun InfoPill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: Previews — one per break-level state, plus the light-theme + label-fallback case.

@Preview(name = "Easy — free break", showBackground = true)
@Composable
private fun BlockScreenEasyPreview() {
    FlintTheme(darkTheme = true) {
        BlockScreen(
            state = BlockScreenState(
                packageName = "com.instagram.android",
                appLabel = "Instagram",
                reason = BlockScreenReason.MANUAL_SESSION,
                breakLevel = BreakLevel.EASY,
                remainingMillis = 1_440_000L, // 24m
            ),
            onDismiss = {},
            onOpenFlint = {},
            onBreakRequested = {},
            onEmergencyPassRequested = {},
        )
    }
}

@Preview(name = "Harder — hold to request", showBackground = true)
@Composable
private fun BlockScreenHarderHoldPreview() {
    FlintTheme(darkTheme = true) {
        BlockScreen(
            state = BlockScreenState(
                packageName = "com.google.android.youtube",
                appLabel = "YouTube",
                reason = BlockScreenReason.SCHEDULE,
                breakLevel = BreakLevel.HARDER,
                remainingMillis = 4_320_000L, // 1h 12m
            ),
            onDismiss = {},
            onOpenFlint = {},
            onBreakRequested = {},
            onEmergencyPassRequested = {},
        )
    }
}

@Preview(name = "Harder — break cooldown pending", showBackground = true)
@Composable
private fun BlockScreenHarderWaitPreview() {
    FlintTheme(darkTheme = true) {
        BlockScreen(
            state = BlockScreenState(
                packageName = "com.zhiliaoapp.musically",
                appLabel = "TikTok",
                reason = BlockScreenReason.TIME_LIMIT,
                breakLevel = BreakLevel.HARDER,
                breakWaitRemainingMillis = 272_000L, // 4m 32s
            ),
            onDismiss = {},
            onOpenFlint = {},
            onBreakRequested = {},
            onEmergencyPassRequested = {},
        )
    }
}

@Preview(name = "Hardcore — emergency pass only", showBackground = true)
@Composable
private fun BlockScreenHardcorePreview() {
    FlintTheme(darkTheme = true) {
        BlockScreen(
            state = BlockScreenState(
                packageName = "com.twitter.android",
                appLabel = "X",
                reason = BlockScreenReason.DEEP_FOCUS,
                breakLevel = BreakLevel.HARDCORE,
                remainingMillis = 2_820_000L, // 47m
            ),
            onDismiss = {},
            onOpenFlint = {},
            onBreakRequested = {},
            onEmergencyPassRequested = {},
        )
    }
}

@Preview(name = "Light theme — label fallback", showBackground = true)
@Composable
private fun BlockScreenLightFallbackPreview() {
    FlintTheme(darkTheme = false) {
        BlockScreen(
            state = BlockScreenState(
                packageName = "com.android.chrome",
                appLabel = null, // falls back to the package name
                reason = BlockScreenReason.OPEN_LIMIT,
                breakLevel = BreakLevel.EASY,
            ),
            onDismiss = {},
            onOpenFlint = {},
            onBreakRequested = {},
            onEmergencyPassRequested = {},
        )
    }
}
