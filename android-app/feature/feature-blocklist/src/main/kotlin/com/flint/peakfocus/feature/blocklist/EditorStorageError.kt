package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.flint.peakfocus.core.common.theme.FlintSpacing

internal const val EDITOR_STORAGE_UNAVAILABLE_MESSAGE =
    "Saved protection couldn't be read. Your records were kept. Close this screen and try again."

/** Refuse to author from a placeholder snapshot. Existing mounted drafts stay mounted. */
@Composable
internal fun StorageEditorUnavailable(onClose: () -> Unit, loading: Boolean = false) {
    Column(Modifier.padding(FlintSpacing.gutter)) {
        if (loading) CircularProgressIndicator() else ErrorMessages(listOf(EDITOR_STORAGE_UNAVAILABLE_MESSAGE))
        TextButton(onClick = onClose) { Text("Back") }
    }
}

/** Capture the first complete snapshot, then keep it through errors and exit animations. */
@Composable
internal fun <T : Any> rememberEditorSnapshot(key: Any?, ready: T?): T? {
    val snapshot = remember(key) { mutableStateOf(ready) }
    LaunchedEffect(ready) {
        if (snapshot.value == null && ready != null) snapshot.value = ready
    }
    return snapshot.value
}
