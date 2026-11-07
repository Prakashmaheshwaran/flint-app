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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.EmberButton
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.FlintTopGlow
import com.flint.peakfocus.core.common.ui.QuietButton
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
        Box(Modifier.fillMaxSize()) {
            FlintTopGlow(height = 300.dp, emberAlpha = 0.16f)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 20.dp),
            ) {
                // Scrollable disclosure region; the Accept/Decline block below never scrolls away.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp, bottom = FlintSpacing.md),
                    ) {
                        EntranceStep(step = 0) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ProgressPip(active = true)
                                    ProgressPip(active = false)
                                }
                                Text(
                                    "Step 1 of 2",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    ),
                                    color = FlintPalette.Mist,
                                )
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                        EntranceStep(step = 1) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(FlintPalette.EmberChip),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = FlintGlyphs.shield,
                                    contentDescription = null,
                                    tint = FlintPalette.Ember,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(
                                ConsentCopy.HEADLINE,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Two permissions, asked once. Both can be changed later in Settings.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FlintPalette.Mist,
                            )
                        }
                        Spacer(Modifier.height(26.dp))
                        EntranceStep(step = 2) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                PermissionSummaryCard(
                                    glyph = FlintGlyphs.lock,
                                    title = "Block apps and sites",
                                    body = "Uses Android's Accessibility service to rest the apps you choose.",
                                    tag = "Required",
                                    emphasized = true,
                                )
                                PermissionSummaryCard(
                                    glyph = FlintGlyphs.chart,
                                    title = "Show screen time",
                                    body = "Usage access powers Insights. Nothing leaves this phone.",
                                    tag = "Recommended",
                                    emphasized = false,
                                )
                                PermissionSummaryCard(
                                    glyph = FlintGlyphs.device,
                                    title = "Keep the block screen visible",
                                    body = "Display over other apps keeps the shield above the app you opened.",
                                    tag = "Recommended",
                                    emphasized = false,
                                )
                                PermissionSummaryCard(
                                    glyph = FlintGlyphs.shield,
                                    title = "Keep blocking in the background",
                                    body = "Battery exemption helps Android leave Flint running.",
                                    tag = "Recommended",
                                    emphasized = false,
                                )
                                PermissionSummaryCard(
                                    glyph = FlintGlyphs.bell,
                                    title = "Tell me when a session ends",
                                    body = "One quiet notification. No streak nags.",
                                    tag = "Optional",
                                    emphasized = false,
                                )
                            }
                        }
                        Spacer(Modifier.height(22.dp))
                        EntranceStep(step = 3) {
                            FlintCard(contentPadding = PaddingValues(14.dp)) {
                                DisclosureRow(
                                    icon = Icons.Filled.Search,
                                    heading = "What Flint sees",
                                    body = ConsentCopy.WHAT_IT_READS,
                                    bodyStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = FlintSpacing.cardGap),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                DisclosureRow(
                                    icon = Icons.Filled.Lock,
                                    heading = "Stays on your device",
                                    body = ConsentCopy.WHERE_IT_GOES,
                                    bodyStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = FlintSpacing.cardGap),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                                DisclosureRow(
                                    icon = Icons.Filled.Settings,
                                    heading = "Your choice and how to turn it off",
                                    body = ConsentCopy.NEXT_STEP,
                                    bodyStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        EntranceStep(step = 4) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(FlintPalette.BoneFaint)
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                PrivacyLine("Your lists and history stay on this phone.")
                                PrivacyLine("No account, no analytics, nothing uploaded.")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.md))
                EntranceStep(step = 5) {
                    Column(Modifier.fillMaxWidth()) {
                        EmberButton(
                            ConsentCopy.ACCEPT_LABEL,
                            onClick = {
                                haptics.confirm()
                                onAccept()
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        QuietButton(ConsentCopy.DECLINE_LABEL, onDecline)
                        Text(
                            text = "Accessibility is optional. You can review this disclosure again " +
                                "from Today or You.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = FlintPalette.Stone,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressPip(active: Boolean) {
    Box(
        Modifier
            .size(width = 28.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (active) FlintPalette.Ember else FlintPalette.Bone.copy(alpha = 0.14f)),
    )
}

/** One permission in M02's card idiom: 44dp glyph circle, 15/600 title, 13/400 body, tag pill. */
@Composable
private fun PermissionSummaryCard(
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    tag: String,
    emphasized: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(FlintPalette.Card)
            .border(
                1.dp,
                if (emphasized) FlintPalette.Ember.copy(alpha = 0.4f) else FlintPalette.Hairline,
                RoundedCornerShape(22.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (emphasized) FlintPalette.EmberChip else FlintPalette.BoneFill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                glyph,
                contentDescription = null,
                tint = if (emphasized) FlintPalette.Ember else FlintPalette.Mist,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp), color = FlintPalette.Mist)
        }
        Surface(
            color = if (emphasized) FlintPalette.EmberChip else FlintPalette.BoneFill,
            contentColor = if (emphasized) FlintPalette.EmberText else FlintPalette.Mist,
            shape = CircleShape,
        ) {
            Text(
                tag,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(FlintGlyphs.check, contentDescription = null, tint = FlintPalette.Bone, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = FlintPalette.Mist)
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
