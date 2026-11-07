package com.flint.peakfocus.feature.blocklist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.rememberFlintMark
import com.flint.peakfocus.core.common.ui.BoneButton
import com.flint.peakfocus.core.common.ui.FlintAppTile
import com.flint.peakfocus.core.common.ui.FlintCircleButton
import com.flint.peakfocus.core.common.ui.FlintGlyphs
import com.flint.peakfocus.core.common.ui.FlintHairline
import com.flint.peakfocus.core.common.ui.FlintSkeletonRow
import com.flint.peakfocus.core.common.ui.flintLoading
import com.flint.peakfocus.core.common.ui.rememberFlintHaptics
import com.flint.peakfocus.core.model.AppGroup

/**
 * Launchable-app chooser backed by [InstalledAppsUiState]. In multi-select mode rows toggle
 * checkboxes and "Add · N" confirms the set; in single-select mode tapping a row confirms
 * immediately (used to pick the app an [AppLimitRow] belongs to), with the [initiallySelected]
 * row marked by a primary check.
 *
 * [initiallySelected] holds package names; packages that aren't in the installed list (app
 * uninstalled since the rule was made) can't be shown or toggled here — the caller is
 * responsible for preserving them across a confirm (the rule editor does).
 * [eyebrow] defaults to the pending selection count and must not contain a count itself — the
 * screen owns the live count. Pass only a contextual heading (for example, "App limits").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppPickerScreen(
    appsState: InstalledAppsUiState,
    title: String,
    eyebrow: String? = null,
    multiSelect: Boolean,
    initiallySelected: Set<String>,
    onConfirm: (List<InstalledApp>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    groups: List<AppGroup> = emptyList(),
) {
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(AppPickerSelection(initiallySelected.toSet())) }
    val haptics = rememberFlintHaptics()

    val apps = (appsState as? InstalledAppsUiState.Ready)?.apps.orEmpty()
    val selectedApps = selection.selectedApps(apps)
    // Until loading finishes, retain the initial/pending count. Once ready, count exactly
    // the apps confirmation can return, including selected apps hidden by the search.
    val selectedCount = if (appsState is InstalledAppsUiState.Ready) {
        selectedApps.size
    } else {
        selection.packageNames.size
    }
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

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // Header/search scroll with the apps. A large font or the IME must not consume
        // the entire list viewport or push the confirmation controls off-screen.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = FlintSpacing.md),
        ) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlintCircleButton(onClick = onCancel, contentDescription = "Back", size = 44.dp) {
                        Icon(FlintGlyphs.back, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        (eyebrow ?: "$selectedCount selected").uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = FlintPalette.Mist,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle ?: if (multiSelect) "Pick a group, or tap apps one by one." else "Tap an app to choose it.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                    color = FlintPalette.Mist,
                )
                Spacer(Modifier.height(14.dp))
                if (groups.isNotEmpty() && multiSelect) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        groups.forEach { group ->
                            GroupChip(group = group) {
                                haptics.tick()
                                selection = selection.withSelection(group.apps.map { it.packageName }, true)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    stringResource(R.string.picker_safety_floor_note),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = FlintPalette.Stone,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (appsState is InstalledAppsUiState.Ready) {
                item(key = "search") {
                    Surface(
                        shape = CircleShape,
                        color = FlintPalette.BoneFill.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, FlintPalette.Hairline),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(FlintGlyphs.search, contentDescription = null, tint = FlintPalette.Mist, modifier = Modifier.size(18.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = FlintPalette.Bone),
                                cursorBrush = SolidColor(FlintPalette.Ember),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = "Search apps and sites"
                                },
                                decorationBox = { inner ->
                                    if (query.isEmpty()) {
                                        Text(
                                            "Search apps and sites",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = FlintPalette.Faint,
                                        )
                                    }
                                    inner()
                                },
                            )
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(FlintGlyphs.close, contentDescription = "Clear search", tint = FlintPalette.Mist, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // Keep the count's natural width; move the action group to the next
                    // line when larger labels would otherwise squeeze APP/APPS into pieces.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
                    ) {
                        Text(
                            text = "${filtered.size} " + if (filtered.size == 1) "APP" else "APPS",
                            modifier = Modifier.align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.labelMedium.merge(FlintNumerals),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (multiSelect) {
                            FlowRow(
                                modifier = Modifier.align(Alignment.CenterVertically),
                                horizontalArrangement = Arrangement.End,
                                verticalArrangement = Arrangement.spacedBy(FlintSpacing.xs),
                            ) {
                                TextButton(
                                    onClick = {
                                        selection = selection.withSelection(filtered.map { it.packageName }, true)
                                    },
                                    enabled = filtered.any { it.packageName !in selection.packageNames },
                                ) {
                                    Text("Select all")
                                }
                                TextButton(
                                    onClick = {
                                        selection = selection.withSelection(filtered.map { it.packageName }, false)
                                    },
                                    enabled = filtered.any { it.packageName in selection.packageNames },
                                ) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(FlintSpacing.xs))
                }
            }
            when {
                appsState is InstalledAppsUiState.Loading -> {
                    item(key = "loading") {
                        Column(Modifier.fillMaxWidth().flintLoading()) {
                            repeat(6) { FlintSkeletonRow() }
                        }
                    }
                }
                appsState is InstalledAppsUiState.Error -> item(key = "error") {
                    Text(appsState.message, color = MaterialTheme.colorScheme.error)
                    appsState.retry?.let { retry -> TextButton(onClick = retry) { Text("Retry") } }
                }
                filtered.isEmpty() -> item(key = "empty") {
                    // A lazy item is measured with an unbounded main axis, so a plain
                    // fillMaxSize would collapse and lose the vertical centring.
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        PickerEmptyState(
                            query = trimmedQuery,
                            noAppsAtAll = apps.isEmpty(),
                            onClearSearch = { query = "" },
                        )
                    }
                }
                else -> {
                    items(filtered, key = { it.packageName }) { app ->
                        AppPickerRow(
                            app = app,
                            multiSelect = multiSelect,
                            isSelected = app.packageName in selection.packageNames,
                            initiallyAssigned = !multiSelect &&
                                app.packageName in initiallySelected,
                            onToggle = { nowSelected ->
                                if (multiSelect) {
                                    if (nowSelected) haptics.toggleOn() else haptics.toggleOff()
                                    selection = selection.withSelection(listOf(app.packageName), nowSelected)
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
            Row(
                Modifier.fillMaxWidth().padding(vertical = FlintSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedContent(
                    targetState = selectedCount,
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
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
                BoneButton(
                    text = "Done · $selectedCount ${if (selectedCount == 1) "app" else "apps"}",
                    onClick = { onConfirm(selectedApps) },
                    enabled = selection.hasChanges(initiallySelected, apps),
                    compact = true,
                    modifier = Modifier.widthIn(min = 120.dp),
                )
            }
        }
    }
}

/** M05's group chip: two stacked mini icons, the group name and its app count. */
@Composable
private fun GroupChip(group: AppGroup, onClick: () -> Unit) {
    val apps = group.apps.toList()
    Row(
        Modifier
            .clip(CircleShape)
            .background(FlintPalette.BoneFill.copy(alpha = 0.5f))
            .border(1.dp, FlintPalette.Hairline, CircleShape)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            apps.take(2).forEachIndexed { index, app ->
                FlintAppTile(
                    packageName = app.packageName,
                    label = app.label,
                    size = 20.dp,
                    cornerRadius = 6.dp,
                    modifier = Modifier
                        .offset(x = (-6 * index).dp)
                        .zIndex((2 - index).toFloat()),
                )
            }
        }
        Text(group.name, style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1)
        Text(
            "${group.apps.size}",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
            color = FlintPalette.Mist,
        )
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
                    stringResource(R.string.picker_empty_list_note)
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
 * with a checkbox role so TalkBack announces checked state; single-select is a radio control
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
    val interaction = if (multiSelect) {
        Modifier.toggleable(value = isSelected, role = Role.Checkbox, onValueChange = onToggle)
    } else {
        Modifier.selectable(selected = initiallyAssigned, role = Role.RadioButton) { onToggle(true) }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(interaction)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FlintAppTile(
            packageName = app.packageName,
            label = app.label,
            size = 40.dp,
            cornerRadius = 12.dp,
            preloadedIcon = app.icon, // decoded off the main thread by InstalledAppsSource
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = FlintPalette.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (multiSelect) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) FlintPalette.Ember else Color.Transparent)
                    .border(
                        if (isSelected) 1.dp else 1.5.dp,
                        if (isSelected) FlintPalette.Ember else FlintPalette.Bone.copy(alpha = 0.3f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(FlintGlyphs.check, contentDescription = null, tint = FlintPalette.OnEmber, modifier = Modifier.size(14.dp))
                }
            }
        } else if (initiallyAssigned) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(FlintPalette.Ember),
                contentAlignment = Alignment.Center,
            ) {
                Icon(FlintGlyphs.check, contentDescription = null, tint = FlintPalette.OnEmber, modifier = Modifier.size(14.dp))
            }
        }
    }
    FlintHairline()
}
