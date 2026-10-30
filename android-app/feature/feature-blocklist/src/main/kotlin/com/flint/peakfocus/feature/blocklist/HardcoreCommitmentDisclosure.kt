package com.flint.peakfocus.feature.blocklist

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Existing rule-editor disclosure, shared verbatim by Sleep; no separate pass or consent. */
internal const val HARDCORE_COMMITMENT_DISCLOSURE =
    "An active Hardcore occurrence cannot be weakened in Flint before it ends, " +
        "except with an available Emergency Pass. Changes can be saved for after it ends."

@Composable
internal fun HardcoreCommitmentDisclosure() {
    Text(
        text = HARDCORE_COMMITMENT_DISCLOSURE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
