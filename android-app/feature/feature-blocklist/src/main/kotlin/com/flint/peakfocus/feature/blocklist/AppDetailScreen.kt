package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flint.peakfocus.blocking.overlay.Readiness
import com.flint.peakfocus.blocking.overlay.TargetProtection
import com.flint.peakfocus.blocking.overlay.TargetProtectionReader
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintChevron
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.common.ui.flintLoading

/** Resolves a [DetailLine]: its args in order, then the formatted time if it has one. */
@Composable
internal fun detailText(line: DetailLine, nowMs: Long): String {
    val resolved = line.args.map { if (it is ResArg) stringResource(it.id) else it }
    val args = (resolved + listOfNotNull(line.time?.let { detailTimeText(it, nowMs) })).toTypedArray()
    return if (line.plural != 0) pluralStringResource(line.plural, line.count, *args)
    else stringResource(line.text, *args)
}

/** Scrolls as one column so 2x text and 320 dp widths stay reachable; Back never scrolls away. */
@Composable
private fun TargetScaffold(onBack: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = FlintSpacing.gutter).testTag(tag)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = FlintSpacing.sm)) {
            Text(stringResource(R.string.targets_back))
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(bottom = FlintSpacing.gutter),
            ) { content() }
        }
    }
}

@Composable
internal fun AppsAndSitesList(
    state: PickedTargetsState,
    onOpen: (PickedTarget) -> Unit,
    onBack: () -> Unit,
) {
    TargetScaffold(onBack, "apps-and-sites") {
        Text(
            stringResource(R.string.targets_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(FlintSpacing.sm))
        Text(
            stringResource(R.string.targets_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(FlintSpacing.lg))
        when (state) {
            PickedTargetsState.Loading -> Column(Modifier.fillMaxWidth().height(48.dp).flintLoading()) {}
            PickedTargetsState.Unavailable -> Text(stringResource(R.string.targets_unavailable))
            is PickedTargetsState.Ready -> {
                if (state.targets.isEmpty()) {
                    Text(
                        stringResource(R.string.targets_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap)) {
                        state.targets.forEach { picked ->
                            FlintCard(onClick = { onOpen(picked) }, modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        picked.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(FlintSpacing.sm))
                                    FlintChevron()
                                }
                            }
                        }
                    }
                }
                if (state.hasAllowListRules) {
                    Spacer(Modifier.height(FlintSpacing.md))
                    Text(
                        stringResource(R.string.targets_allow_list_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Read-only detail; every sentence restates the reader's result, never the stores. */
@Composable
internal fun AppDetailContent(
    label: String,
    presentation: AppDetailPresentation,
    nowMs: Long,
    isSite: Boolean = false,
    onBack: () -> Unit,
) {
    TargetScaffold(onBack, "app-detail") {
        Text(
            label,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(FlintSpacing.sm))
        val headline = headlineLine(presentation.headline)
        Text(
            detailText(headline, nowMs),
            style = MaterialTheme.typography.titleMedium,
            modifier = if (presentation.headline == AppDetailHeadline.Loading) Modifier.flintLoading() else Modifier,
        )
        if (presentation.rows.isNotEmpty()) {
            Spacer(Modifier.height(FlintSpacing.lg))
            FlintSectionLabel(stringResource(R.string.target_covering), Modifier.semantics { heading() })
            Spacer(Modifier.height(FlintSpacing.sm))
            FlintCard(modifier = Modifier.fillMaxWidth()) {
                presentation.rows.forEachIndexed { index, cause ->
                    if (index > 0) Spacer(Modifier.height(FlintSpacing.md))
                    // One TalkBack stop per covering rule: name, kind and tier, then its status.
                    Column(Modifier.semantics(mergeDescendants = true) {}) {
                        Text(cause.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.target_cause_kind_tier, stringResource(kindName(cause.kind)), stringResource(tierName(cause.tier))),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            detailText(causeStatusLine(cause), nowMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (cause.timeUncertain) {
                            Text(
                                stringResource(R.string.target_cause_time_uncertain),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        presentation.timeLimit?.let { limit ->
            Spacer(Modifier.height(FlintSpacing.lg))
            FlintSectionLabel(stringResource(R.string.target_time), Modifier.semantics { heading() })
            Spacer(Modifier.height(FlintSpacing.sm))
            Text(detailText(timeLimitLine(limit), nowMs), style = MaterialTheme.typography.bodyMedium)
        }
        presentation.credits?.let { credits ->
            Spacer(Modifier.height(FlintSpacing.lg))
            FlintSectionLabel(stringResource(R.string.target_credits), Modifier.semantics { heading() })
            Spacer(Modifier.height(FlintSpacing.sm))
            Text(detailText(creditLine(credits), nowMs), style = MaterialTheme.typography.bodyMedium)
            presentation.activeCreditEndsAtWall?.let { end ->
                Text(
                    detailText(DetailLine(R.string.target_credit_active_until, time = end), nowMs),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // Website health describes the browser, so it belongs on a site's detail only.
        presentation.websiteCoverage?.takeIf { isSite }?.let { coverage ->
            Spacer(Modifier.height(FlintSpacing.lg))
            FlintSectionLabel(stringResource(R.string.target_websites), Modifier.semantics { heading() })
            Spacer(Modifier.height(FlintSpacing.sm))
            Text(detailText(websiteCoverageLine(coverage, presentation.websiteObservedAtWall), nowMs), style = MaterialTheme.typography.bodyMedium)
        }
        presentation.nextChangeAtWall?.let { next ->
            Spacer(Modifier.height(FlintSpacing.md))
            Text(
                detailText(DetailLine(R.string.target_next_change, time = next), nowMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (presentation.headline !is AppDetailHeadline.SafetyFloor) {
            Spacer(Modifier.height(FlintSpacing.lg))
            Text(
                stringResource(R.string.target_edit_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Collects lane C's read-only reader for one picked target; no store reads happen here. */
@Composable
internal fun AppDetailRoute(picked: PickedTarget, reader: TargetProtectionReader, onBack: () -> Unit) {
    val protection by remember(picked.target, reader) { reader.observe(picked.target.toTargetRef()) }
        .collectAsStateWithLifecycle(TargetProtection(Readiness.Loading))
    val presentation = remember(protection) { appDetailPresentation(appDetailSnapshot(protection)) }
    AppDetailContent(
        label = picked.label,
        presentation = presentation,
        nowMs = remember(protection) { System.currentTimeMillis() },
        isSite = picked.target is AppDetailTarget.Site,
        onBack = onBack,
    )
}
