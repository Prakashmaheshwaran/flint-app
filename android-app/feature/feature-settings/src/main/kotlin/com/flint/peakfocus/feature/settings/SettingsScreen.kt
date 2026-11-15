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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.common.ui.FlintBadge
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.FlintSectionTitle
import com.flint.peakfocus.core.common.ui.FlintStatusDot
import com.flint.peakfocus.core.common.ui.FlintStatusPill
import com.flint.peakfocus.core.common.ui.FlintTopGlow
import com.flint.peakfocus.core.common.ui.rememberReducedMotion
import com.flint.peakfocus.feature.settings.applock.AppLock
import com.flint.peakfocus.feature.settings.applock.AppLockSettingsSection
import com.flint.peakfocus.feature.settings.applock.AppLockSettingsUi
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
 * an explicit button tap. Every Accessibility fix calls the app's shared prominent-disclosure
 * gate, including repeat enables; this module never opens Accessibility Settings directly.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onEnableAccessibility: () -> Unit,
    onAccount: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var health by remember { mutableStateOf(PermissionHealthChecker.check(context)) }
    var exits by remember { mutableStateOf(ExitReasonReporter.lastExits(context)) }
    // One process-wide controller: the gate in MainActivity and this section share it.
    val appLockController = remember { AppLock.controller(context) }
    val appLockState by appLockController.state.collectAsState()

    // Re-check on every resume: the user returns here from Settings hand-offs, and grants can
    // be revoked behind Flint's back at any time (same pattern as StatsScreen / MainActivity).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health = PermissionHealthChecker.check(context)
                exits = ExitReasonReporter.lastExits(context)
                // The PIN record can change outside this screen; never show stale lock state.
                appLockController.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsHelpNavigation(
        websiteCoverageText = BlockingHealthUi.websiteCoverage(health),
        modifier = modifier,
    ) { openHelp ->
        SettingsLegalNavigation(modifier = modifier) { openLegal ->
            SettingsContent(
                health = health,
                exits = exits,
                oemGuidance = OemGuidanceCatalog.forOem(OemUtil.current),
                exitsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                nowMillis = System.currentTimeMillis(),
                onAccount = onAccount,
                onOpenLegal = openLegal,
                onOpenHelp = {
                    // Help states website coverage "now": take a fresh snapshot as it opens.
                    health = PermissionHealthChecker.check(context)
                    openHelp()
                },
                appLock = AppLockSettingsUi.from(appLockController, appLockState),
                onFix = { kind ->
                    routePermissionFix(kind, discloseAccessibility = onEnableAccessibility) { otherKind ->
                        val intent = when (otherKind) {
                            PermissionKind.ACCESSIBILITY -> error("Accessibility requires disclosure")
                            PermissionKind.USAGE_ACCESS -> UsageAccess.settingsIntent()
                            PermissionKind.OVERLAY -> OverlayPermission.settingsIntent(context)
                            PermissionKind.BATTERY_EXEMPTION -> BatteryOptimization.requestIntent(context)
                            PermissionKind.NOTIFICATIONS -> NotificationPermission.settingsIntent(context)
                        }
                        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                modifier = modifier,
            )
        }
    }
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
    onAccount: (() -> Unit)? = null,
    onOpenLegal: (LegalDocument) -> Unit = {},
    onOpenHelp: () -> Unit = {},
    appLock: AppLockSettingsUi? = null,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            FlintTopGlow(height = 240.dp, emberAlpha = 0.12f)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("You", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    FlintStatusPill("No account", emphasized = false)
                }
                Spacer(Modifier.height(16.dp))
                if (onAccount != null) {
                    OutlinedButton(onClick = onAccount, modifier = Modifier.fillMaxWidth()) {
                        Text("Connected sandbox (debug)")
                    }
                    Spacer(Modifier.height(FlintSpacing.md))
                }
                ProtectionCard(health, BlockingHealthUi.banner(health))
                Spacer(Modifier.height(12.dp))
                NotificationEnableCard()
                Spacer(Modifier.height(FlintSpacing.md))
                ClearFocusHistoryRow()
                Spacer(Modifier.height(FlintSpacing.md))
                if (appLock != null) {
                    FlintSectionTitle("App lock")
                    Spacer(Modifier.height(FlintSpacing.sm))
                    AppLockSettingsSection(appLock)
                    Spacer(Modifier.height(FlintSpacing.md))
                }
                FlintSectionTitle("Permissions")
                Spacer(Modifier.height(FlintSpacing.sm))
                BlockingHealthUi.rows(health).forEach { row ->
                    PermissionCard(row = row, onFix = { onFix(row.kind) })
                    Spacer(Modifier.height(FlintSpacing.cardGap))
                }
                Spacer(Modifier.height(FlintSpacing.sm))
                FlintSectionTitle("Troubleshooting")
                Spacer(Modifier.height(FlintSpacing.sm))
                OemGuidanceCard(oemGuidance)
                Spacer(Modifier.height(FlintSpacing.cardGap))
                ExitDiagnosticsCard(
                    exits = exits,
                    supported = exitsSupported,
                    nowMillis = nowMillis,
                )
                Spacer(Modifier.height(FlintSpacing.lg))
                HelpSettingsLink(onOpen = onOpenHelp)
                Spacer(Modifier.height(FlintSpacing.lg))
                LegalSettingsLinks(onOpen = onOpenLegal)
                Spacer(Modifier.height(FlintSpacing.lg))
            }
        }
    }
}

/** M16's protection card: a breathing ember dot, the health banner and three real grant states. */
@Composable
private fun ProtectionCard(health: HealthStatus, banner: BannerUi) {
    val accent = when (banner.level) {
        HealthLevel.HEALTHY -> FlintPalette.Ember
        HealthLevel.DEGRADED -> FlintPalette.Mist
        HealthLevel.BROKEN -> FlintPalette.Danger
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(FlintPalette.Card)
            .border(1.dp, FlintPalette.Hairline, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(FlintPalette.EmberChip),
                contentAlignment = Alignment.Center,
            ) {
                FlintStatusDot(accent, pulsing = banner.level == HealthLevel.HEALTHY, modifier = Modifier.size(10.dp))
            }
            Text(banner.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        // Full width and never ellipsized: the body carries the recovery guidance and the
        // website-coverage disclosure for this state, which can run long at large font scales.
        Text(
            banner.body,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = FlintPalette.Mist,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(FlintPalette.Hairline))
        val grants = listOf(
            "Accessibility" to health.accessibilityEnabled,
            "Usage access" to health.usageAccessGranted,
            "Battery" to health.batteryExemptionGranted,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val widest = grants.maxOf { (label, _) ->
                measurer.measure(AnnotatedString(label), grantLabelStyle(), softWrap = false).size.width
            }
            // Three columns only while every label fits whole; large font scales get a list.
            if (with(density) { widest.toDp() } <= maxWidth / grants.size - 4.dp) {
                Row(Modifier.fillMaxWidth()) {
                    grants.forEach { (label, granted) -> GrantStat(label, granted, Modifier.weight(1f)) }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    grants.forEach { (label, granted) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, style = grantLabelStyle(), modifier = Modifier.weight(1f))
                            GrantValue(granted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun grantLabelStyle() = MaterialTheme.typography.labelSmall.copy(
    fontSize = 12.sp,
    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
)

@Composable
private fun GrantValue(granted: Boolean) {
    Text(
        if (granted) "Allowed" else "Off",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        color = if (granted) FlintPalette.Mist else FlintPalette.Danger,
    )
}

@Composable
private fun GrantStat(label: String, granted: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = grantLabelStyle(), maxLines = 1)
        GrantValue(granted)
    }
}

/** The one notification grant that actually reaches Flint's session-end nudge. */
@Composable
private fun NotificationEnableCard() {
    val context = LocalContext.current
    val granted = NotificationPermission.isGranted(context)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(FlintPalette.Card)
            .border(1.dp, FlintPalette.Hairline, RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            FlintGlyphs.bell,
            contentDescription = null,
            tint = FlintPalette.EmberText,
            modifier = Modifier.size(26.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Android only notifies when a focus session ends; nothing fires before rules start.
            Text("Heads-up when focus ends?", style = MaterialTheme.typography.titleSmall)
            Text(
                "A quiet nudge when a focus session ends.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = FlintPalette.Mist,
            )
        }
        if (granted) {
            FlintStatusPill("On", emphasized = false)
        } else {
            Surface(
                onClick = {
                    context.startActivity(
                        NotificationPermission.settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                shape = CircleShape,
                color = FlintPalette.EmberChip,
                contentColor = FlintPalette.EmberText,
            ) {
                Text(
                    "Enable",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
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
internal fun PermissionCard(row: PermissionRowUi, onFix: () -> Unit) {
    val grantState = stringResource(if (row.granted) R.string.health_on else R.string.health_off)
    FlintCard {
        // One TalkBack stop names the grant and its state; the fix button stays separate.
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = grantState },
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            // The row's state description already speaks On/Off; the uppercase pill is visual.
            FlintBadge(text = grantState, modifier = Modifier.clearAndSetSemantics {})
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
internal fun OemGuidanceCard(guidance: OemGuidance) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val expansionState = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    val reducedMotion = rememberReducedMotion()
    FlintCard(onClick = { expanded = !expanded }, modifier = Modifier.semantics { stateDescription = expansionState }) {
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
            enter = if (reducedMotion) EnterTransition.None else expandTransition(),
            exit = if (reducedMotion) ExitTransition.None else collapseTransition(),
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
    val expansionState = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    val reducedMotion = rememberReducedMotion()
    FlintCard(onClick = { expanded = !expanded }, modifier = Modifier.semantics { stateDescription = expansionState }) {
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
            enter = if (reducedMotion) EnterTransition.None else expandTransition(),
            exit = if (reducedMotion) ExitTransition.None else collapseTransition(),
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
