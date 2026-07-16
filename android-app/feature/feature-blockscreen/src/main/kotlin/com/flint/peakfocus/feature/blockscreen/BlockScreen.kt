package com.flint.peakfocus.feature.blockscreen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.theme.rememberFlintMark
import com.flint.peakfocus.core.common.ui.FlintBadge
import com.flint.peakfocus.core.common.ui.FlintInfoPill
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.launch

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
 *   with a fresh [BlockScreenState] (e.g. once per second while visible). A state for a *new*
 *   package crossfades the words in place — the glow and breathing mark never restart, so
 *   hosts that reuse the surface must swap state instead of recreating it.
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
 * - [nudge] — monotonic refusal counter. The host increments it each time it refuses an exit
 *   it owns (a swallowed Back press, a denied Hardcore break, a spent Emergency Pass); every
 *   change shakes the action column, pulses the mark one strong breath and plays the reject
 *   haptic — the block stays firm without playing dead.
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
    nudge: Int = 0,
) {
    val content = blockScreenContent(state)
    val glowColor = MaterialTheme.colorScheme.primary

    // One-shot entrance: the hero (glow + mark) reveals first, the copy staggers in after it,
    // and the exit buttons land last so the message registers before the exits do. The state
    // never returns to false, so the reveal runs exactly once per surface — in both hosts.
    val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
    val entranceTransition = rememberTransition(entrance, label = "blockEntrance")
    val heroIn = entranceTransition.animateFloat(
        transitionSpec = { tween(FlintMotion.DurationLong, easing = FlintMotion.EasingEmphasized) },
        label = "heroIn",
    ) { shown -> if (shown) 1f else 0f }

    // Mark and aura breathe as one organism: a single infinite fraction drives the glow's
    // alpha/radius and the mark's alpha/scale in phase.
    val breath = rememberInfiniteTransition(label = "emberBreath").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 4 * FlintMotion.DurationLong,
                easing = FlintMotion.EasingStandard,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "emberBreathFraction",
    )

    // Refusal feedback: each host nudge shakes the exits, pulses the mark one strong breath
    // and plays the reject haptic. The remembered baseline skips the first composition.
    val haptics = rememberFlintHaptics()
    val shake = remember { Animatable(0f) }
    val markPulse = remember { Animatable(0f) }
    val initialNudge = remember { nudge }
    LaunchedEffect(nudge) {
        if (nudge == initialNudge) return@LaunchedEffect // composing is not a refusal
        haptics.reject()
        launch {
            shake.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = FlintMotion.DurationMedium
                    0f at 0
                    -8f at FlintMotion.DurationMedium / 5
                    8f at FlintMotion.DurationMedium / 2
                    -4f at FlintMotion.DurationMedium * 3 / 4
                    0f at FlintMotion.DurationMedium
                },
            )
        }
        launch {
            markPulse.animateTo(
                1f,
                tween(FlintMotion.DurationShort, easing = FlintMotion.EasingEmphasized),
            )
            markPulse.animateTo(
                0f,
                tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
            )
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // The ember glow sits behind the mark (~35% height), not screen center. It
                    // breathes with the mark — alpha 0.06→0.12, radius 0.55→0.65 of
                    // maxDimension — and the entrance fraction grows it in from nothing.
                    val reveal = heroIn.value
                    if (size.minDimension > 0f && reveal > 0f) {
                        drawRect(
                            Brush.radialGradient(
                                0f to glowColor.copy(alpha = (0.06f + 0.06f * breath.value) * reveal),
                                1f to Color.Transparent,
                                center = Offset(size.width / 2f, size.height * 0.35f),
                                radius = size.maxDimension * (0.55f + 0.10f * breath.value) * reveal,
                            ),
                        )
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Hosts are raw overlay windows / an edge-to-edge Activity and none of
                    // them insets for us — the content dodges system bars here while the
                    // glow backdrop (the Box behind) stays full-bleed.
                    .safeDrawingPadding()
                    .padding(horizontal = FlintSpacing.gutter, vertical = FlintSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                BreathingMark(
                    reveal = { heroIn.value },
                    breath = { breath.value },
                    pulse = { markPulse.value },
                )
                Spacer(Modifier.height(FlintSpacing.md))
                EntranceSlot(entrance, step = 1) {
                    Text(
                        text = "Peak Focus".uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(FlintSpacing.md))
                // A new block reusing this surface swaps only the words: package-keyed, so
                // the 1s countdown re-renders update in place without re-animating.
                AnimatedContent(
                    targetState = state,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        ContentTransform(
                            targetContentEnter = fadeIn(
                                animationSpec = tween(
                                    FlintMotion.DurationMedium,
                                    easing = FlintMotion.EasingStandard,
                                ),
                            ),
                            initialContentExit = fadeOut(
                                animationSpec = tween(
                                    FlintMotion.DurationMedium,
                                    easing = FlintMotion.EasingStandard,
                                ),
                            ),
                            sizeTransform = SizeTransform(clip = false) { _, _ ->
                                tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)
                            },
                        )
                    },
                    contentAlignment = Alignment.TopCenter,
                    contentKey = { it.packageName },
                    label = "blockWords",
                ) { shown ->
                    BlockWords(state = shown, entrance = entrance)
                }
                Spacer(Modifier.weight(1f))
                EntranceSlot(entrance, step = 6, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Refusal shake, in dp (GraphicsLayerScope is a Density).
                            .graphicsLayer { translationX = shake.value.dp.toPx() },
                        verticalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text("Go back")
                        }
                        // Class-keyed: a ticking WaitBeforeBreak updates in place; only a
                        // different affordance kind animates, with the column height easing.
                        AnimatedContent(
                            targetState = content.breakAffordance,
                            modifier = Modifier.fillMaxWidth(),
                            transitionSpec = {
                                ContentTransform(
                                    targetContentEnter = fadeIn(
                                        animationSpec = tween(
                                            FlintMotion.DurationMedium,
                                            easing = FlintMotion.EasingEmphasized,
                                        ),
                                    ) + slideInVertically(
                                        animationSpec = tween(
                                            FlintMotion.DurationMedium,
                                            easing = FlintMotion.EasingEmphasized,
                                        ),
                                        initialOffsetY = { it / 6 },
                                    ),
                                    initialContentExit = fadeOut(
                                        animationSpec = tween(
                                            FlintMotion.DurationMedium,
                                            easing = FlintMotion.EasingEmphasized,
                                        ),
                                    ) + slideOutVertically(
                                        animationSpec = tween(
                                            FlintMotion.DurationMedium,
                                            easing = FlintMotion.EasingEmphasized,
                                        ),
                                        targetOffsetY = { -it / 6 },
                                    ),
                                    sizeTransform = SizeTransform(clip = false) { _, _ ->
                                        tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized)
                                    },
                                )
                            },
                            contentAlignment = Alignment.Center,
                            contentKey = { it::class },
                            label = "breakAffordance",
                        ) { affordance ->
                            BreakActions(
                                affordance = affordance,
                                onBreakRequested = onBreakRequested,
                                onEmergencyPassRequested = onEmergencyPassRequested,
                            )
                        }
                        TextButton(
                            onClick = onOpenFlint,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        ) {
                            Text("Open Flint")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Everything that names the block — headline, reason, cause badge, countdown, encouragement.
 * Split out so the package-keyed word swap leaves the aura and mark untouched. [entrance] is
 * the hoisted reveal state: on first composition the slots stagger in; on a word swap it has
 * already settled, so they render immediately and the crossfade alone carries the change.
 */
@Composable
private fun BlockWords(
    state: BlockScreenState,
    entrance: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
) {
    val content = blockScreenContent(state)
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EntranceSlot(entrance, step = 2) {
            Text(
                text = content.headline,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(FlintSpacing.cardGap))
        EntranceSlot(entrance, step = 3) {
            Text(
                text = content.reasonLine,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(FlintSpacing.md))
        EntranceSlot(entrance, step = 4) {
            FlintBadge(text = content.badge.text, emphasized = content.badge.emphasized)
        }
        EntranceSlot(entrance, step = 5) {
            // Presence is independent of the entrance: the pill collapses away when the end
            // becomes unknown and expands back when a countdown starts.
            AnimatedVisibility(
                visible = content.countdownLabel != null,
                enter = expandVertically(
                    animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
                ) + fadeIn(
                    animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
                ),
                exit = shrinkVertically(
                    animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
                ) + fadeOut(
                    animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
                ),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(FlintSpacing.lg))
                    // "0s" during the exit collapse is honest — the block just ended.
                    FlintInfoPill(
                        text = content.countdownLabel
                            ?: requireNotNull(countdownLabelFor(state.reason, 0L)),
                    )
                }
            }
        }
        Spacer(Modifier.height(FlintSpacing.lg))
        EntranceSlot(entrance, step = 5) {
            Text(
                text = content.encouragement,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One step of the one-shot entrance: fade + an 8dp rise, delayed by [step] half-short beats
 * so the copy reads top-to-bottom and the exits land last. After the reveal the slot is
 * inert — [entrance] never returns to false, so re-composed content shows immediately.
 */
@Composable
private fun EntranceSlot(
    entrance: MutableTransitionState<Boolean>,
    step: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val rise = with(LocalDensity.current) { FlintSpacing.sm.roundToPx() }
    val delay = step * (FlintMotion.DurationShort / 2)
    AnimatedVisibility(
        visibleState = entrance,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = FlintMotion.DurationMedium,
                delayMillis = delay,
                easing = FlintMotion.EasingEmphasized,
            ),
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = FlintMotion.DurationMedium,
                delayMillis = delay,
                easing = FlintMotion.EasingEmphasized,
            ),
            initialOffsetY = { rise },
        ),
        label = "entranceSlot",
    ) {
        content()
    }
}

/**
 * The tri-color strike mark — the screen's brand moment. [reveal] fades + scales it in on
 * entrance (0.92→1), [breath] is the shared infinite fraction it breathes on (alpha 0.7→1.0,
 * scale 0.97→1.0, in phase with the glow), and [pulse] is the refusal accent — one stronger
 * breath. Lambdas defer the reads to the draw phase: no recomposition per animation frame.
 */
@Composable
private fun BreathingMark(
    reveal: () -> Float,
    breath: () -> Float,
    pulse: () -> Float,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = rememberFlintMark(),
        // Decorative: the "PEAK FOCUS" eyebrow right below carries the brand for TalkBack.
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = modifier
            .size(64.dp)
            .graphicsLayer {
                val b = breath()
                val p = pulse()
                val baseAlpha = 0.7f + 0.3f * b
                alpha = (baseAlpha + (1f - baseAlpha) * p) * reveal()
                val scale = (0.92f + 0.08f * reveal()) * (0.97f + 0.03f * b) * (1f + 0.08f * p)
                scaleX = scale
                scaleY = scale
            },
    )
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
        // 48dp minimum keeps the deliberate 52/48/48 exit ladder under "Go back".
        BreakAffordance.TakeABreak -> OutlinedButton(
            onClick = onBreakRequested,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text("Take a break")
        }

        is BreakAffordance.HoldToRequest -> HoldToRequestBreakButton(
            holdMillis = affordance.holdMillis,
            onHoldCompleted = onBreakRequested,
            modifier = modifier.fillMaxWidth(),
        )

        is BreakAffordance.WaitBeforeBreak -> FlintInfoPill(
            text = "Break available in ${formatDuration(affordance.remainingMillis)}",
            modifier = modifier,
        )

        is BreakAffordance.EmergencyPassOnly -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Hardcore session — no breaks, no early stop.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (affordance.passAvailable) {
                TextButton(
                    onClick = onEmergencyPassRequested,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text("Use an Emergency Pass")
                }
            } else {
                Text(
                    text = "Emergency Pass used — a new one arrives Monday.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = FlintSpacing.sm),
                )
            }
        }

        BreakAffordance.None -> Unit // guard shield: "Go back" and "Open Flint" are the exits
    }
}

/**
 * HARDER-level friction: press and hold for [holdMillis] to fire [onHoldCompleted]. The fill
 * animation is driven purely by the press gesture (no free-running timer); releasing early
 * drains it back to zero. The fill tween must stay [LinearEasing] over exactly [holdMillis]
 * so on-screen progress equals real hold time.
 */
@Composable
private fun HoldToRequestBreakButton(
    holdMillis: Long,
    onHoldCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) {
            tween(durationMillis = holdMillis.toInt(), easing = LinearEasing)
        } else {
            tween(durationMillis = FlintMotion.DurationShort, easing = FlintMotion.EasingStandard)
        },
        label = "holdToBreakProgress",
        finishedListener = { value ->
            if (value >= 1f) {
                pressed = false
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
            // Accessibility services can't perform a press-and-hold, so expose a plain
            // activate action that fires the same callback directly — touch users keep the
            // hold friction below (the gesture path is unchanged).
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Request a break") {
                    onHoldCompleted()
                    true
                }
            }
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
        Crossfade(
            targetState = pressed,
            animationSpec = tween(
                durationMillis = FlintMotion.DurationShort,
                easing = FlintMotion.EasingStandard,
            ),
            label = "holdToBreakLabel",
        ) { isPressed ->
            Text(
                text = if (isPressed) "Keep holding…" else "Hold to request a break",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// MARK: Previews — one per break-level state, plus the guard, light-theme + fallback cases.

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

@Preview(name = "Uninstall guard — protected badge", showBackground = true)
@Composable
private fun BlockScreenGuardPreview() {
    FlintTheme(darkTheme = true) {
        BlockScreen(
            state = BlockScreenState(
                packageName = "com.android.settings",
                appLabel = "Settings",
                reason = BlockScreenReason.UNINSTALL_GUARD,
                breakLevel = BreakLevel.HARDCORE,
                remainingMillis = 2_820_000L, // 47m
            ),
            onDismiss = {},
            onOpenFlint = {},
            onBreakRequested = {},
            onEmergencyPassRequested = {},
            // The nudge counter only animates on a live increment (baseline diff), so a
            // static preview value stays inert by design — hosts drive it.
            nudge = 0,
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
