package com.flint.peakfocus.feature.onboarding

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.theme.rememberFlintMark
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics

/**
 * PROMINENT DISCLOSURE + AFFIRMATIVE CONSENT (Play AccessibilityService policy, §4.3).
 *
 * This is a compliance gate, not a UX nicety. It must be shown in-app during normal use,
 * describe the data the AccessibilityService accesses and how it is used/shared, and require an
 * explicit accept BEFORE the user is sent to enable the service. The verbatim sentences live
 * in [ConsentCopy], where JVM tests pin every policy-required element.
 */
@Composable
fun AccessibilityConsentScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberFlintHaptics()
    ConsentBackIsDecline(onDecline)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(FlintSpacing.gutter),
        ) {
            // Scrollable disclosure region; the Accept/Decline block below never scrolls away.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    EntranceStep(step = 0) {
                        Icon(
                            imageVector = rememberFlintMark(),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Spacer(Modifier.height(FlintSpacing.md))
                    EntranceStep(step = 1) {
                        FlintScreenHeader(
                            title = ConsentCopy.HEADLINE,
                            eyebrow = "One-time setup",
                        )
                    }
                    Spacer(Modifier.height(FlintSpacing.md))
                    EntranceStep(step = 2) {
                        FlintCard {
                            DisclosureRow(
                                icon = Icons.Filled.Search,
                                heading = "What Flint sees",
                                body = ConsentCopy.WHAT_IT_READS,
                                bodyStyle = MaterialTheme.typography.bodyLarge,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = FlintSpacing.cardGap),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            DisclosureRow(
                                icon = Icons.Filled.Lock,
                                heading = "Stays on your device",
                                body = ConsentCopy.WHERE_IT_GOES,
                                bodyStyle = MaterialTheme.typography.bodyLarge,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = FlintSpacing.cardGap),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            DisclosureRow(
                                icon = Icons.Filled.Settings,
                                heading = "Reversible any time",
                                body = ConsentCopy.NEXT_STEP,
                                bodyStyle = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(FlintSpacing.md))
            EntranceStep(step = 3) {
                Column(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            haptics.confirm()
                            onAccept()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(ConsentCopy.ACCEPT_LABEL)
                    }
                    Spacer(Modifier.height(FlintSpacing.sm))
                    TextButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text(ConsentCopy.DECLINE_LABEL)
                    }
                    Spacer(Modifier.height(FlintSpacing.sm))
                    Text(
                        text = "Flint can't block anything until this is enabled — you can " +
                            "come back any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** One disclosure beat: 20dp primary icon beside the micro-heading + verbatim sentence. */
@Composable
private fun DisclosureRow(
    icon: ImageVector,
    heading: String,
    body: String,
    bodyStyle: TextStyle,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs)) {
            Text(
                text = heading.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = body,
                style = bodyStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One-shot staggered entrance: each step fades/rises in once per screen entry, offset by
 * [step] × DurationShort (false→true MutableTransitionState keys the play to composition).
 */
@Composable
private fun EntranceStep(step: Int, content: @Composable () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val delayMs = step * FlintMotion.DurationShort
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = FlintMotion.DurationMedium,
                delayMillis = delayMs,
                easing = FlintMotion.EasingStandard,
            ),
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = FlintMotion.DurationMedium,
                delayMillis = delayMs,
                easing = FlintMotion.EasingEmphasized,
            ),
            initialOffsetY = { it / 6 },
        ),
    ) {
        content()
    }
}

/**
 * System back on the consent gate means "Not now", never "quit the app mid-disclosure".
 * feature-onboarding carries no androidx.activity dependency, so this registers with the
 * framework OnBackInvokedDispatcher directly — API 33+ only (the app manifest opts into
 * back-invoked callbacks); pre-33 back keeps the default activity-finish behavior.
 */
@Composable
private fun ConsentBackIsDecline(onDecline: () -> Unit) {
    if (Build.VERSION.SDK_INT < 33) return
    val latestOnDecline by rememberUpdatedState(onDecline)
    val context = LocalContext.current
    DisposableEffect(context) {
        // Previews/tests host composition outside an Activity — then this is a no-op.
        val dispatcher = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
            ?.onBackInvokedDispatcher
        val callback = OnBackInvokedCallback { latestOnDecline() }
        dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConsentPreview() {
    FlintTheme { AccessibilityConsentScreen(onAccept = {}, onDecline = {}) }
}

@Preview(showBackground = true)
@Composable
private fun ConsentDarkPreview() {
    FlintTheme(darkTheme = true) { AccessibilityConsentScreen(onAccept = {}, onDecline = {}) }
}
