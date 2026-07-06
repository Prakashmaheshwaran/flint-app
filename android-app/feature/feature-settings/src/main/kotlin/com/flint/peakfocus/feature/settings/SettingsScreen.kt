package com.flint.peakfocus.feature.settings

import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.flint.peakfocus.blocking.resilience.ExitCategory
import com.flint.peakfocus.blocking.resilience.ExitDiagnostic
import com.flint.peakfocus.blocking.resilience.ExitReasonReporter
import com.flint.peakfocus.blocking.resilience.HealthLevel
import com.flint.peakfocus.blocking.resilience.HealthStatus
import com.flint.peakfocus.blocking.resilience.PermissionHealthChecker
import com.flint.peakfocus.core.common.Oem
import com.flint.peakfocus.core.common.OemUtil
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.FlintBadge
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.FlintStatusDot
import com.flint.peakfocus.permissions.AccessibilityPermission
import com.flint.peakfocus.permissions.BatteryOptimization
import com.flint.peakfocus.permissions.NotificationPermission
import com.flint.peakfocus.permissions.OverlayPermission
import com.flint.peakfocus.permissions.UsageAccess

/**
 * Keep-blocking-alive settings: blocking health with a per-grant fix action, the
 * battery-exemption request, static per-manufacturer auto-start guidance, and a
 * "why did blocking stop?" diagnostics row. Public entry point for the A-VERIFY integrator —
 * this module does not touch the nav graph.
 *
 * ADR-007 in practice: nothing here auto-redirects — every Settings hand-off happens only from
 * an explicit button tap, and each hand-off button sits below disclosure copy consistent with
 * the onboarding consent screen. The prominent-disclosure consent gate itself (before the very
 * first accessibility enable) lives in feature-onboarding.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var health by remember { mutableStateOf(PermissionHealthChecker.check(context)) }
    var exits by remember { mutableStateOf(ExitReasonReporter.lastExits(context)) }

    // Re-check on every resume: the user returns here from Settings hand-offs, and grants can
    // be revoked behind Flint's back at any time (same pattern as StatsScreen / MainActivity).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health = PermissionHealthChecker.check(context)
                exits = ExitReasonReporter.lastExits(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsContent(
        health = health,
        exits = exits,
        oemGuidance = OemGuidanceCatalog.forOem(OemUtil.current),
        exitsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        nowMillis = System.currentTimeMillis(),
        onFix = { kind ->
            // User-initiated only (ADR-007): reached exclusively via the row's fix button.
            val intent = when (kind) {
                PermissionKind.ACCESSIBILITY -> AccessibilityPermission.settingsIntent()
                PermissionKind.USAGE_ACCESS -> UsageAccess.settingsIntent()
                PermissionKind.OVERLAY -> OverlayPermission.settingsIntent(context)
                PermissionKind.BATTERY_EXEMPTION -> BatteryOptimization.requestIntent(context)
                PermissionKind.NOTIFICATIONS -> NotificationPermission.settingsIntent(context)
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        modifier = modifier,
    )
}

/** Stateless body — previewable without touching system services. */
@Composable
internal fun SettingsContent(
    health: HealthStatus,
    exits: List<ExitDiagnostic>,
    oemGuidance: OemGuidance,
    exitsSupported: Boolean,
    nowMillis: Long,
    onFix: (PermissionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(FlintSpacing.gutter),
        ) {
            FlintScreenHeader(title = "Settings", eyebrow = "Keep blocking alive")
            Spacer(Modifier.height(FlintSpacing.md))
            HealthBannerCard(BlockingHealthUi.banner(health))
            Spacer(Modifier.height(FlintSpacing.md))
            BlockingHealthUi.rows(health).forEach { row ->
                PermissionCard(row = row, onFix = { onFix(row.kind) })
                Spacer(Modifier.height(FlintSpacing.cardGap))
            }
            Spacer(Modifier.height(FlintSpacing.sm))
            FlintSectionLabel("Troubleshooting")
            Spacer(Modifier.height(FlintSpacing.sm))
            OemGuidanceCard(oemGuidance)
            Spacer(Modifier.height(FlintSpacing.cardGap))
            ExitDiagnosticsCard(
                exits = exits,
                supported = exitsSupported,
                nowMillis = nowMillis,
            )
            Spacer(Modifier.height(FlintSpacing.lg))
        }
    }
}

@Composable
private fun HealthBannerCard(banner: BannerUi) {
    val container = when (banner.level) {
        HealthLevel.HEALTHY -> MaterialTheme.colorScheme.surfaceContainerLow
        HealthLevel.DEGRADED -> MaterialTheme.colorScheme.surfaceContainerHigh
        HealthLevel.BROKEN -> MaterialTheme.colorScheme.errorContainer
    }
    val headlineColor = when (banner.level) {
        HealthLevel.BROKEN -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bodyColor = when (banner.level) {
        HealthLevel.BROKEN -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    FlintCard(containerColor = container, contentColor = headlineColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (banner.level) {
                HealthLevel.HEALTHY -> FlintStatusDot(MaterialTheme.colorScheme.primary)
                HealthLevel.DEGRADED -> Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HealthLevel.BROKEN -> Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.width(FlintSpacing.sm))
            Text(
                text = banner.headline,
                style = MaterialTheme.typography.titleMedium,
                color = headlineColor,
            )
        }
        Spacer(Modifier.height(FlintSpacing.xs))
        Text(
            text = banner.body,
            style = MaterialTheme.typography.bodyMedium,
            color = bodyColor,
        )
    }
}

/**
 * One grant row. When the grant is missing, the disclosure paragraph is shown *above* the fix
 * button (consent language before the hand-off — ADR-007); the redirect fires only on the tap.
 */
@Composable
private fun PermissionCard(row: PermissionRowUi, onFix: () -> Unit) {
    FlintCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Dot vocabulary: primary = granted, error = enforcement at risk, muted = the
            // merely-recommended grant missing (never dressed as mandatory).
            FlintStatusDot(
                color = when {
                    row.granted -> MaterialTheme.colorScheme.primary
                    row.affectsEnforcement -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(FlintSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(FlintSpacing.xs))
                Text(
                    text = row.role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(FlintSpacing.cardGap))
            FlintBadge(text = if (row.granted) "On" else "Off")
        }
        if (!row.granted) {
            Spacer(Modifier.height(FlintSpacing.sm))
            Text(
                text = row.disclosure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(FlintSpacing.cardGap))
            if (row.affectsEnforcement) {
                Button(onClick = onFix, modifier = Modifier.fillMaxWidth()) {
                    Text(row.fixLabel)
                }
            } else {
                OutlinedButton(onClick = onFix, modifier = Modifier.fillMaxWidth()) {
                    Text(row.fixLabel)
                }
            }
        }
    }
}

private fun expandTransition(): EnterTransition =
    expandVertically(
        animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
    ) + fadeIn(
        animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
    )

private fun collapseTransition(): ExitTransition =
    shrinkVertically(
        animationSpec = tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingEmphasized),
    ) + fadeOut(
        animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
    )

/** Static, local, per-manufacturer survival steps — expandable, no deep links, no network. */
@Composable
private fun OemGuidanceCard(guidance: OemGuidance) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FlintCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Keep Flint running on ${guidance.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(FlintSpacing.xs))
                Text(
                    text = "Manufacturer battery settings that can stop blocking",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(FlintSpacing.cardGap))
            Text(
                text = if (expanded) "Hide" else "Show",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandTransition(),
            exit = collapseTransition(),
        ) {
            Column {
                Spacer(Modifier.height(FlintSpacing.sm))
                Text(
                    text = guidance.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(FlintSpacing.sm))
                guidance.steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(vertical = FlintSpacing.xs)) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium.merge(FlintNumerals),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(FlintSpacing.sm))
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(FlintSpacing.sm))
                Text(
                    text = "Exact menu names vary by Android version — these are the usual " +
                        "paths. If they don't match your phone, dontkillmyapp.com documents " +
                        "most models.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Why did blocking stop?" — recent process deaths from ExitReasonReporter, in plain words. */
@Composable
private fun ExitDiagnosticsCard(
    exits: List<ExitDiagnostic>,
    supported: Boolean,
    nowMillis: Long,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FlintCard(onClick = { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Why did blocking stop?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(FlintSpacing.xs))
                Text(
                    text = "Recent times Android ended Flint's process",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(FlintSpacing.cardGap))
            Text(
                text = if (expanded) "Hide" else "Show",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandTransition(),
            exit = collapseTransition(),
        ) {
            Column {
                Spacer(Modifier.height(FlintSpacing.sm))
                when {
                    !supported -> Text(
                        text = "Your Android version doesn't keep exit records (Android 11 or " +
                            "newer is required), so Flint can't show why it was stopped.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    exits.isEmpty() -> Text(
                        text = "No process deaths on record — either Flint hasn't been killed " +
                            "recently, or the system hasn't recorded one yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> exits.forEach { diagnostic ->
                        val explanation = ExitDiagnosticsText.explain(diagnostic.category)
                        Column(Modifier.fillMaxWidth().padding(vertical = FlintSpacing.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = explanation.headline,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = ExitDiagnosticsText.relativeAge(
                                        diagnostic.timestampMillis,
                                        nowMillis,
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                        .merge(FlintNumerals),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(FlintSpacing.xs))
                            Text(
                                text = explanation.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.sm))
                Text(
                    text = "Read on demand from Android's own records. Stays on this device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Previews (fake state only; no system services) ---

private const val PREVIEW_NOW = 1_750_000_000_000L

private fun previewDegradedStatus() = HealthStatus(
    accessibilityEnabled = false,
    usageAccessGranted = true,
    overlayGranted = true,
    batteryExemptionGranted = false,
)

private fun previewExits() = listOf(
    ExitDiagnostic(
        category = ExitCategory.OEM_OR_SYSTEM_KILL,
        reasonCode = 3,
        timestampMillis = PREVIEW_NOW - 2 * 3_600_000L,
        description = null,
    ),
    ExitDiagnostic(
        category = ExitCategory.USER_KILL,
        reasonCode = 10,
        timestampMillis = PREVIEW_NOW - 26 * 3_600_000L,
        description = "stop user",
    ),
)

@Preview(showBackground = true)
@Composable
private fun SettingsDegradedPreview() {
    FlintTheme {
        SettingsContent(
            health = previewDegradedStatus(),
            exits = previewExits(),
            oemGuidance = OemGuidanceCatalog.forOem(Oem.XIAOMI),
            exitsSupported = true,
            nowMillis = PREVIEW_NOW,
            onFix = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsHealthyPreview() {
    FlintTheme {
        SettingsContent(
            health = HealthStatus(
                accessibilityEnabled = true,
                usageAccessGranted = true,
                overlayGranted = true,
                batteryExemptionGranted = true,
            ),
            exits = emptyList(),
            oemGuidance = OemGuidanceCatalog.forOem(Oem.OTHER),
            exitsSupported = true,
            nowMillis = PREVIEW_NOW,
            onFix = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsBrokenDarkPreview() {
    FlintTheme(darkTheme = true) {
        SettingsContent(
            health = HealthStatus(
                accessibilityEnabled = false,
                usageAccessGranted = false,
                overlayGranted = false,
                batteryExemptionGranted = false,
            ),
            exits = previewExits(),
            oemGuidance = OemGuidanceCatalog.forOem(Oem.SAMSUNG),
            exitsSupported = true,
            nowMillis = PREVIEW_NOW,
            onFix = {},
        )
    }
}
