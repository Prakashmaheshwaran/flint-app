package com.flint.peakfocus.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintSectionLabel

/**
 * "How Flint works": strictness tiers, the Emergency Pass, what Flint cannot prevent on Android
 * and its platform limits. Every sentence must stay true of the shipped code and ADR-012
 * (docs/architecture/protection-contract.md); tier and pass copy follows lane C's merged
 * enforcement and recovery wording. Authored copy lives in res/values/strings.xml
 * (`help_*`). The Websites fact also renders lane C's shared website-coverage projection
 * (BlockingHealthUi.websiteCoverage), the same text You shows. HelpCopyTest guards both.
 */
internal data class HelpFact(
    @StringRes val title: Int,
    @StringRes val text: Int,
    /** Appends the supplied website-coverage projection; Help never derives coverage itself. */
    val showsWebsiteCoverage: Boolean = false,
)

internal data class HelpSection(@StringRes val title: Int, val facts: List<HelpFact>)

internal object HelpContent {
    val sections: List<HelpSection> = listOf(
        HelpSection(
            R.string.help_section_strictness,
            listOf(
                HelpFact(R.string.help_strict_easy_title, R.string.help_strict_easy_text),
                HelpFact(R.string.help_strict_harder_title, R.string.help_strict_harder_text),
                HelpFact(R.string.help_strict_hardcore_title, R.string.help_strict_hardcore_text),
                HelpFact(R.string.help_strict_timers_title, R.string.help_strict_timers_text),
                HelpFact(R.string.help_strict_legacy_title, R.string.help_strict_legacy_text),
            ),
        ),
        HelpSection(
            R.string.help_section_pass,
            listOf(
                HelpFact(R.string.help_pass_title, R.string.help_pass_text),
                HelpFact(R.string.help_pass_recovery_title, R.string.help_pass_recovery_text),
                HelpFact(R.string.help_pass_where_title, R.string.help_pass_where_text),
            ),
        ),
        HelpSection(
            R.string.help_section_limits,
            listOf(
                HelpFact(R.string.help_access_title, R.string.help_access_text),
                HelpFact(R.string.help_safety_title, R.string.help_safety_text),
                HelpFact(R.string.help_uninstall_title, R.string.help_uninstall_text),
                HelpFact(R.string.help_clock_title, R.string.help_clock_text),
            ),
        ),
        HelpSection(
            R.string.help_section_websites,
            listOf(HelpFact(R.string.help_websites_title, R.string.help_websites_text, showsWebsiteCoverage = true)),
        ),
        HelpSection(
            R.string.help_section_running,
            listOf(HelpFact(R.string.help_battery_title, R.string.help_battery_text)),
        ),
    )
}

/** Module-local route, like the legal documents: survives recreation, Back returns to You. */
@Composable
internal fun SettingsHelpNavigation(
    websiteCoverageText: String,
    modifier: Modifier = Modifier,
    settings: @Composable (onOpenHelp: () -> Unit) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val stateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = open) { open = false }
    if (open) {
        stateHolder.SaveableStateProvider("help") {
            HelpScreen(onBack = { open = false }, websiteCoverageText = websiteCoverageText, modifier = modifier)
        }
    } else {
        stateHolder.SaveableStateProvider("settings") { settings { open = true } }
    }
}

/** The entry on You, above the legal links. */
@Composable
internal fun HelpSettingsLink(onOpen: () -> Unit) {
    FlintSectionLabel(stringResource(R.string.help_section_label))
    Spacer(Modifier.height(FlintSpacing.sm))
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("settings-help")) {
        Text(stringResource(R.string.help_title))
    }
}

@Composable
internal fun HelpScreen(onBack: () -> Unit, websiteCoverageText: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = FlintSpacing.gutter).testTag("help-screen")) {
            // Kept outside the scrolling content so the way back never scrolls away.
            TextButton(onClick = onBack, modifier = Modifier.padding(top = FlintSpacing.sm)) {
                Text(stringResource(R.string.help_back))
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = FlintSpacing.gutter),
            ) {
                Text(
                    stringResource(R.string.help_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(FlintSpacing.sm))
                Text(
                    stringResource(R.string.help_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HelpContent.sections.forEach { section ->
                    Spacer(Modifier.height(FlintSpacing.lg))
                    FlintSectionLabel(
                        stringResource(section.title),
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(FlintSpacing.sm))
                    FlintCard(modifier = Modifier.fillMaxWidth()) {
                        section.facts.forEachIndexed { index, fact ->
                            if (index > 0) Spacer(Modifier.height(FlintSpacing.md))
                            Text(
                                stringResource(fact.title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                            Spacer(Modifier.height(FlintSpacing.xs))
                            Text(
                                stringResource(fact.text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (fact.showsWebsiteCoverage) {
                                // Lane C owns the observation wording and browser matrix; this is
                                // the same read-only projection rendered on You.
                                Spacer(Modifier.height(FlintSpacing.sm))
                                Text(
                                    websiteCoverageText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(FlintSpacing.lg))
                Text(
                    stringResource(R.string.help_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(FlintSpacing.lg))
            }
        }
    }
}
