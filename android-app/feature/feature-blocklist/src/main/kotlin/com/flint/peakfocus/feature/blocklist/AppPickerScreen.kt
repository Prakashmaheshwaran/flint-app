package com.flint.peakfocus.feature.blocklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Launchable-app chooser backed by [InstalledAppsUiState]. In multi-select mode rows toggle
 * checkboxes and "Add" confirms the set; in single-select mode tapping a row confirms
 * immediately (used to pick the app an [AppLimitRow] belongs to).
 *
 * [initiallySelected] holds package names; packages that aren't in the installed list (app
 * uninstalled since the rule was made) can't be shown or toggled here — the caller is
 * responsible for preserving them across a confirm (the rule editor does).
 */
@Composable
internal fun AppPickerScreen(
    appsState: InstalledAppsUiState,
    title: String,
    multiSelect: Boolean,
    initiallySelected: Set<String>,
    onConfirm: (List<InstalledApp>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initiallySelected) }

    Column(modifier = modifier.fillMaxSize().padding(20.dp)) {
        EditorHeader(title = title, onBack = onCancel)
        Spacer(Modifier.height(8.dp))

        when (appsState) {
            is InstalledAppsUiState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Reading your app list…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is InstalledAppsUiState.Ready -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search apps") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))

                val apps = appsState.apps
                val trimmedQuery = query.trim()
                val filtered = remember(apps, trimmedQuery) {
                    if (trimmedQuery.isEmpty()) {
                        apps
                    } else {
                        apps.filter {
                            it.label.contains(trimmedQuery, ignoreCase = true) ||
                                it.packageName.contains(trimmedQuery, ignoreCase = true)
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Text(
                            text = if (apps.isEmpty()) {
                                "No launchable apps found on this device."
                            } else {
                                "Nothing matches your search."
                            },
                            modifier = Modifier.padding(vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppPickerRow(
                                app = app,
                                showCheckbox = multiSelect,
                                isSelected = app.packageName in selected,
                                onClick = {
                                    if (multiSelect) {
                                        selected = if (app.packageName in selected) {
                                            selected - app.packageName
                                        } else {
                                            selected + app.packageName
                                        }
                                    } else {
                                        onConfirm(listOf(app))
                                    }
                                },
                            )
                        }
                    }
                }

                if (multiSelect) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${selected.size} selected",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { onConfirm(apps.filter { it.packageName in selected }) }) {
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    app: InstalledApp,
    showCheckbox: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconThumb(icon = app.icon, label = app.label)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showCheckbox) {
            Spacer(Modifier.width(8.dp))
            Checkbox(checked = isSelected, onCheckedChange = null)
        }
    }
}
