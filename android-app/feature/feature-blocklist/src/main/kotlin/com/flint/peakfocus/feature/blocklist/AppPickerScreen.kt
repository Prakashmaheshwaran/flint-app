package com.flint.peakfocus.feature.blocklist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.rememberFlintMark
import com.flint.peakfocus.core.common.ui.FlintSkeletonRow
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics

/** Which of the picker's three bodies is showing; keys the region crossfade. */
private enum class PickerRegion { LOADING, EMPTY, LIST }

/**
 * Launchable-app chooser backed by [InstalledAppsUiState]. In multi-select mode rows toggle
 * checkboxes and "Add · N" confirms the set; in single-select mode tapping a row confirms
 * immediately (used to pick the app an [AppLimitRow] belongs to), with the [initiallySelected]
 * row marked by a primary check.
 *
 * [initiallySelected] holds package names; packages that aren't in the installed list (app
 * uninstalled since the rule was made) can't be shown or toggled here — the caller is
 * responsible for preserving them across a confirm (the rule editor does).
 */
@Composable
internal fun AppPickerScreen(
    appsState: InstalledAppsUiState,
    title: String,
    eyebrow: String,
    multiSelect: Boolean,
    initiallySelected: Set<String>,
    onConfirm: (List<InstalledApp>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initiallySelected) }
    val haptics = rememberFlintHaptics()

    val apps = (appsState as? InstalledAppsUiState.Ready)?.apps.orEmpty()
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

    Column(modifier = modifier.fillMaxSize().padding(FlintSpacing.gutter)) {
        EditorHeader(title = title, eyebrow = eyebrow, onBack = onCancel)
        Spacer(Modifier.height(FlintSpacing.sm))

        if (appsState is InstalledAppsUiState.Ready) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps") },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
                },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = fadeIn(
                            tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
                        ),
                        exit = fadeOut(
                            tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
                        ),
                    ) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                },
                shape = MaterialTheme.shapes.large,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.height(FlintSpacing.sm))
            // Control row: result count left, filtered-subset bulk actions right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${filtered.size} " + if (filtered.size == 1) "APP" else "APPS",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium.merge(FlintNumerals),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (multiSelect) {
                    TextButton(
                        onClick = { selected = selected + filtered.map { it.packageName } },
                        enabled = filtered.any { it.packageName !in selected },
                    ) {
                        Text("Select all")
                    }
                    TextButton(
                        onClick = {
                            selected = selected - filtered.map { it.packageName }.toSet()
                        },
                        enabled = filtered.any { it.packageName in selected },
                    ) {
                        Text("Clear")
                    }
                }
            }
            Spacer(Modifier.height(FlintSpacing.xs))
        }

        val region = when {
            appsState is InstalledAppsUiState.Loading -> PickerRegion.LOADING
            filtered.isEmpty() -> PickerRegion.EMPTY
            else -> PickerRegion.LIST
        }
        AnimatedContent(
            targetState = region,
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = {
                fadeIn(
                    tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
                ) togetherWith fadeOut(
                    tween(FlintMotion.DurationMedium, easing = FlintMotion.EasingStandard),
                )
            },
            label = "pickerRegion",
        ) { current ->
            when (current) {
                PickerRegion.LOADING -> Column(Modifier.fillMaxSize()) {
                    repeat(6) { FlintSkeletonRow() }
                }

                PickerRegion.EMPTY -> PickerEmptyState(
                    query = trimmedQuery,
                    noAppsAtAll = apps.isEmpty(),
                    onClearSearch = { query = "" },
                )

                PickerRegion.LIST -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = FlintSpacing.md),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        AppPickerRow(
                            app = app,
                            multiSelect = multiSelect,
                            isSelected = app.packageName in selected,
                            initiallyAssigned = !multiSelect &&
                                app.packageName in initiallySelected,
                            onToggle = { nowSelected ->
                                if (multiSelect) {
                                    if (nowSelected) haptics.toggleOn() else haptics.toggleOff()
                                    selected = if (nowSelected) {
                                        selected + app.packageName
                                    } else {
                                        selected - app.packageName
                                    }
                                } else {
                                    onConfirm(listOf(app))
                                }
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        if (multiSelect && appsState is InstalledAppsUiState.Ready) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = FlintSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedContent(
                            targetState = selected.size,
                            transitionSpec = {
                                val spec = tween<Float>(
                                    FlintMotion.DurationShort,
                                    easing = FlintMotion.EasingStandard,
                                )
                                (
                                    slideInVertically(
                                        tween(
                                            FlintMotion.DurationShort,
                                            easing = FlintMotion.EasingStandard,
                                        ),
                                    ) { it / 2 } + fadeIn(spec)
                                    ) togetherWith (
                                    slideOutVertically(
                                        tween(
                                            FlintMotion.DurationShort,
                                            easing = FlintMotion.EasingStandard,
                                        ),
                                    ) { -it / 2 } + fadeOut(spec)
                                    )
                            },
                            label = "pickerSelectedCount",
                        ) { count ->
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.bodyMedium.merge(FlintNumerals),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = " selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(FlintSpacing.sm))
                    Button(
                        onClick = { onConfirm(apps.filter { it.packageName in selected }) },
                        // A confirm that changes nothing is noise — require a real edit.
                        enabled = selected != initiallySelected,
                    ) {
                        Text(
                            text = "Add · ${selected.size}",
                            style = LocalTextStyle.current.merge(FlintNumerals),
                        )
                    }
                }
            }
        }
    }
}

/** No-results stack: quiet mark, what missed, and the fastest way back to the full list. */
@Composable
private fun PickerEmptyState(
    query: String,
    noAppsAtAll: Boolean,
    onClearSearch: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = rememberFlintMark(),
                contentDescription = null,
                // Unspecified keeps the mark's three facets; the alpha keeps it quiet.
                tint = Color.Unspecified,
                modifier = Modifier.size(40.dp).alpha(0.6f),
            )
            Spacer(Modifier.height(FlintSpacing.md))
            Text(
                text = if (noAppsAtAll) "No apps to show" else "No match for “$query”",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(FlintSpacing.xs))
            Text(
                text = if (noAppsAtAll) {
                    "No launchable apps found on this device."
                } else {
                    "Nothing matches your search."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!noAppsAtAll) {
                Spacer(Modifier.height(FlintSpacing.sm))
                TextButton(onClick = onClearSearch) { Text("Clear search") }
            }
        }
    }
}

/**
 * One selectable app row. Multi-select uses [androidx.compose.foundation.selection.toggleable]
 * with a checkbox role so TalkBack announces checked state; single-select is a plain button
 * that confirms immediately, with a primary check marking the currently assigned row.
 */
@Composable
private fun AppPickerRow(
    app: InstalledApp,
    multiSelect: Boolean,
    isSelected: Boolean,
    initiallyAssigned: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            Color.Transparent
        },
        animationSpec = tween(FlintMotion.DurationShort, easing = FlintMotion.EasingStandard),
        label = "appPickerRowContainer",
    )
    val interaction = if (multiSelect) {
        Modifier.toggleable(value = isSelected, role = Role.Checkbox, onValueChange = onToggle)
    } else {
        Modifier.clickable(role = Role.Button) { onToggle(true) }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .then(interaction)
            .padding(horizontal = FlintSpacing.sm, vertical = FlintSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconThumb(icon = app.icon, label = app.label)
        Spacer(Modifier.width(FlintSpacing.cardGap))
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
        if (multiSelect) {
            Spacer(Modifier.width(FlintSpacing.sm))
            Checkbox(checked = isSelected, onCheckedChange = null)
        } else if (initiallyAssigned) {
            Spacer(Modifier.width(FlintSpacing.sm))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Currently selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
