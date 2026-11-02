package com.flint.peakfocus.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal enum class LegalDocument(val assetPath: String, @get:StringRes val title: Int) {
    PRIVACY("legal/privacy-policy.md", R.string.legal_privacy_policy),
    LICENSES("legal/open-source-licenses.txt", R.string.legal_open_source_licenses),
}

/** Module-local routes preserve the settings scroll position and survive recreation. */
@Composable
internal fun SettingsLegalNavigation(
    modifier: Modifier = Modifier,
    loadDocument: ((LegalDocument) -> String)? = null,
    settings: @Composable ((LegalDocument) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val bundledLoader: (LegalDocument) -> String = remember(context) {
        { document -> context.assets.open(document.assetPath).bufferedReader().use { it.readText() } }
    }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val document = LegalDocument.entries.find { it.name == route }
    val stateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = document != null) { route = null }
    if (document == null) {
        stateHolder.SaveableStateProvider("settings") {
            settings { route = it.name }
        }
    } else {
        stateHolder.SaveableStateProvider(document.name) {
            LegalScreen(document, loadDocument ?: bundledLoader, onBack = { route = null }, modifier = modifier)
        }
    }
}

@Composable
internal fun LegalSettingsLinks(onOpen: (LegalDocument) -> Unit) {
    FlintSectionLabel(stringResource(R.string.legal_section))
    Spacer(Modifier.height(FlintSpacing.sm))
    LegalDocument.entries.forEach { document ->
        OutlinedButton(onClick = { onOpen(document) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(document.title))
        }
    }
}

private sealed interface LegalContent {
    data object Loading : LegalContent
    data object Failed : LegalContent
    data class Loaded(val paragraphs: List<String>) : LegalContent
}

/** Plain, selectable text: no WebView, URL launching, network access or HTML execution. */
@Composable
private fun LegalScreen(
    document: LegalDocument,
    loadDocument: (LegalDocument) -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var attempt by remember { mutableStateOf(0) }
    val content by produceState<LegalContent>(LegalContent.Loading, document, loadDocument, attempt) {
        value = LegalContent.Loading
        value = try {
            withContext(Dispatchers.IO) {
                LegalContent.Loaded(loadDocument(document).split(Regex("\\n\\s*\\n")))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            LegalContent.Failed
        }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(FlintSpacing.gutter)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.legal_back)) }
            FlintScreenHeader(title = stringResource(document.title))
            Text(stringResource(R.string.legal_offline), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(FlintSpacing.md))
            when (val loaded = content) {
                LegalContent.Loading -> Text(stringResource(R.string.legal_loading))
                LegalContent.Failed -> {
                    Text(stringResource(R.string.legal_load_error))
                    OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.legal_retry)) }
                }
                is LegalContent.Loaded -> SelectionContainer(Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("legal-document")) {
                        items(loaded.paragraphs) { paragraph ->
                            Text(
                                paragraph,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().padding(bottom = FlintSpacing.md),
                            )
                        }
                    }
                }
            }
        }
    }
}
