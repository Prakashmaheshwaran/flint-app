package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flint.peakfocus.core.common.theme.FlintSpacing

/** Refuse to author from a placeholder snapshot. Existing mounted drafts stay mounted. */
@Composable
internal fun StorageEditorUnavailable(onClose: () -> Unit) {
    Column(Modifier.padding(FlintSpacing.gutter)) {
        ErrorMessages(listOf("Couldn't load. Try again."))
        TextButton(onClick = onClose) { Text("Back") }
    }
}
