package com.flint.peakfocus.feature.connected

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.ui.flintSwitchColors
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel
import com.flint.peakfocus.core.model.ManagedRole
import com.flint.peakfocus.core.model.ManagedRule
import com.flint.peakfocus.core.model.ManagedRuleScope
import com.flint.peakfocus.core.model.ManagedSchedule
import com.flint.peakfocus.core.model.ManagedTargets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Owner CRUD and accepted member-snapshot inspection; this screen never activates blocking. */
@Composable
internal fun ManagedPolicyScreen(
    config: ConnectedConfig,
    accountUid: String,
    group: ConnectedGroup,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model: ManagedPolicyViewModel = viewModel(factory = remember(context, config) {
        ManagedPolicyViewModel.factory(context, config)
    })
    val state by model.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(group.id, group.role, accountUid) { model.openGroup(group, accountUid) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); model.closeView() }
    }
    var confirmDiscard by remember(state.identity, state.group?.id, state.draft?.id) { mutableStateOf(false) }
    var confirmDelete by remember(state.identity, state.group?.id, state.draft?.id) { mutableStateOf(false) }
    val back: () -> Unit = {
        if (!state.busy) {
            if (state.draft != null) {
                if (state.dirty) confirmDiscard = true else model.closeEditor()
            } else onBack()
        }
    }
    BackHandler(onBack = back)

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(FlintSpacing.gutter),
            verticalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = back, enabled = !state.busy) {
                    Text(if (state.draft == null) "Back to group" else "Back to policies")
                }
                TextButton(onClick = model::refresh, enabled = !state.busy && !state.loading && state.group != null) {
                    Text("Refresh")
                }
            }
            FlintScreenHeader(state.group?.name ?: group.name, eyebrow = "Managed policies")
            FlintCard {
                Text("Local policy testing", style = MaterialTheme.typography.titleSmall)
                Text("Policies are saved to the local service. Blocking on member devices is not active yet.")
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { PolicyNotice(it, error = true) }
            state.message?.let { PolicyNotice(it) }
            when {
                state.group == null -> Text("Waiting for the signed-in account. Return to your account if it changed.")
                state.owner -> OwnerPolicies(state, model, onDelete = { confirmDelete = true })
                else -> MemberPolicies(state)
            }
        }
    }
    if (confirmDiscard && state.draft != null) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved edits?") },
            text = { Text("The policy stored on the service will not change.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; model.closeEditor() }) { Text("Discard edits") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmDelete && state.draft?.id != null) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirmDelete = false },
            title = { Text("Delete this policy?") },
            text = { Text("It will be removed from the group's saved policies and the next complete member snapshot.") },
            confirmButton = {
                TextButton(enabled = state.canWrite, onClick = { confirmDelete = false; model.delete() }) { Text("Delete policy") }
            },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { confirmDelete = false }) { Text("Keep policy") } },
        )
    }
}

@Composable
private fun OwnerPolicies(state: ManagedPolicyUiState, model: ManagedPolicyViewModel, onDelete: () -> Unit) {
    Text("You manage policies for this group's members. The owner is excluded from group policies.")
    if (state.staleOwnerPage) {
        PolicyNotice("Refresh the saved policy list before making another change.")
    }
    if (state.conflict) {
        FlintCard {
            Text("The saved policy changed or was deleted. Your edits and their original version are still here.")
            val canReload = state.page?.rules?.any { it.id == state.draft?.id } == true && !state.staleOwnerPage
            OutlinedButton(enabled = canReload && !state.loading && !state.busy, onClick = model::loadLatestDraft) {
                Text("Discard edits and load saved version")
            }
        }
    }
    if (state.uncertainCreate) {
        FlintCard {
            Text("Creation may have succeeded", style = MaterialTheme.typography.titleSmall)
            Text("Refresh and inspect the saved policies below before submitting again, to avoid a duplicate.")
            OutlinedButton(onClick = model::refresh, enabled = !state.loading && !state.busy) { Text("Refresh saved policies") }
            if (state.uncertaintyRefreshed && !state.staleOwnerPage) {
                TextButton(onClick = model::acknowledgeUncertainCreate, enabled = !state.loading && !state.busy) {
                    Text("I checked the list; allow another submission")
                }
            }
        }
    }
    state.draft?.let { draft ->
        PolicyEditor(state, draft, model::updateDraft, model::loadApps)
        Button(onClick = model::save, enabled = state.canWrite, modifier = Modifier.fillMaxWidth()) {
            Text(if (draft.id == null) "Create policy" else "Save policy")
        }
        if (draft.id != null) {
            TextButton(onClick = onDelete, enabled = state.canWrite) { Text("Delete policy") }
        }
    }
    if (state.draft == null || state.uncertainCreate) {
        FlintSectionLabel("Saved policies")
        val page = state.page
        if (page == null) {
            Text(if (state.loading) "Loading saved policies…" else "Policies haven't loaded. Refresh to try again.")
        } else {
            if (page.rules.isEmpty()) Text("No saved policies for this group.")
            page.rules.forEach { rule ->
                PolicyCard(rule, state.members, state.localApps, memberView = false) {
                    OutlinedButton(
                        onClick = { model.editPolicy(rule.id) },
                        enabled = !state.busy && !state.loading && !state.staleOwnerPage,
                    ) { Text(if (state.uncertainCreate) "Open saved policy" else "Edit policy") }
                }
            }
            if (state.draft == null) {
                Button(onClick = model::newPolicy, enabled = state.canWrite && page.rules.size < 10) { Text("New policy") }
                if (page.rules.size >= 10) Text("This group has 10 policies. Delete one before adding another.")
            }
        }
    }
}

@Composable
private fun MemberPolicies(state: ManagedPolicyUiState) {
    val snapshot = acceptedPolicySnapshot(state.authority, state.identity)
    when (state.authority) {
        is ManagedAuthorityState.Stale -> PolicyNotice("Showing the last accepted policies. The latest refresh failed; use Refresh to try again.")
        is ManagedAuthorityState.Loading -> Text("Refreshing accepted policies…")
        is ManagedAuthorityState.Unavailable -> PolicyNotice("Policies could not be loaded. Refresh to try again.", error = true)
        ManagedAuthorityState.SignedOut -> Text("Sign in to inspect your policies.")
        else -> Unit
    }
    if (snapshot == null) return
    Text("Last accepted snapshot: ${policyInstantLabel(snapshot.serverTimeEpochMs)}", style = MaterialTheme.typography.bodySmall)
    val membership = snapshot.memberships.firstOrNull { it.groupId == state.group?.id }
    if (membership == null) {
        Text("This group is absent from your latest accepted memberships. Return to your account and refresh groups.")
        return
    }
    if (membership.role != ManagedRole.MEMBER) {
        Text("No member policies apply to you as this group's owner.")
        return
    }
    val rules = snapshot.rules.filter { it.targetGroupId == membership.groupId }
    if (rules.isEmpty()) Text("No policies apply to you in this group.")
    rules.forEach { PolicyCard(it, emptyList(), state.localApps, memberView = true) {} }
}

@Composable
private fun PolicyCard(
    rule: ManagedRule,
    members: List<ConnectedMember>,
    localApps: List<ManagedLocalApp>,
    memberView: Boolean,
    actions: @Composable () -> Unit,
) {
    FlintCard {
        Text(rule.name, style = MaterialTheme.typography.titleMedium)
        Text(if (rule.enabled) "Enabled policy" else "Disabled policy", style = MaterialTheme.typography.labelLarge)
        Text(when (val scope = rule.scope) {
            ManagedRuleScope.Group -> "Applies to all group members; owner excluded"
            is ManagedRuleScope.Member -> if (memberView) "Applies to you" else "Applies to ${memberLabel(scope.userId, members)}"
        })
        Text(policyScheduleLabel(rule), style = MaterialTheme.typography.bodyMedium)
        when (val targets = rule.targets) {
            ManagedTargets.AllApps -> Text("All apps")
            is ManagedTargets.Selection -> {
                val labels = localApps.associate { it.packageName to it.label }
                targets.packages.sorted().forEach { Text(labels[it]?.let { label -> "$label ($it)" } ?: it) }
                targets.domains.sorted().forEach { Text(it) }
            }
        }
        Text(
            if (rule.unblocksAllowed) "Unblocks allowed by policy; no unblock grants are available yet."
            else "Unblocks are not allowed by this policy.",
            style = MaterialTheme.typography.bodySmall,
        )
        actions()
    }
}

@Composable
private fun PolicyEditor(
    state: ManagedPolicyUiState,
    draft: ManagedPolicyDraft,
    onChange: (ManagedPolicyDraft) -> Unit,
    onReloadApps: () -> Unit,
) {
    val enabled = !state.busy && !state.loading
    var memberMenu by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }
    var advancedPackages by remember { mutableStateOf(false) }
    FlintCard {
        OutlinedTextField(draft.name, { onChange(draft.copy(name = it.take(80))) }, label = { Text("Policy name") },
            singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        PolicySwitch("Enabled policy", draft.enabled, enabled) { onChange(draft.copy(enabled = it)) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
            ManagedPolicyKind.entries.forEach { kind ->
                FilterChip(selected = draft.kind == kind, onClick = {
                    if (kind != draft.kind) {
                        val defaults = newManagedPolicyDraft(System.currentTimeMillis())
                        onChange(if (kind == ManagedPolicyKind.SESSION) draft.copy(kind = kind,
                            startDate = defaults.startDate, endDate = defaults.endDate,
                            startTime = defaults.startTime, endTime = defaults.endTime)
                        else draft.copy(kind = kind, startTime = draft.startTime.take(5), endTime = draft.endTime.take(5)))
                    }
                }, enabled = enabled,
                    label = { Text(if (kind == ManagedPolicyKind.SESSION) "One-time session" else "Recurring schedule") })
            }
        }
        Column {
            OutlinedButton(onClick = { memberMenu = true }, enabled = enabled) {
                Text(draft.memberUserId?.let { memberLabel(it, state.members) } ?: "All group members")
            }
            DropdownMenu(expanded = memberMenu, onDismissRequest = { memberMenu = false }) {
                DropdownMenuItem(text = { Text("All members (owner excluded)") }, onClick = {
                    onChange(draft.copy(memberUserId = null)); memberMenu = false
                })
                state.members.filter { it.role == "member" && it.userId != state.group?.ownerId }.forEach { member ->
                    DropdownMenuItem(text = { Text(memberLabel(member.userId, state.members)) }, onClick = {
                        onChange(draft.copy(memberUserId = member.userId)); memberMenu = false
                    })
                }
            }
        }
        OutlinedTextField(draft.zoneId, { onChange(draft.copy(zoneId = it.take(64))) }, label = { Text("Time zone") },
            supportingText = { Text("Use UTC or an IANA zone such as America/New_York.") }, singleLine = true,
            enabled = enabled, modifier = Modifier.fillMaxWidth())
        if (draft.kind == ManagedPolicyKind.SESSION) {
            Text("Session dates and times below are UTC. The policy's time zone does not change these instants.", style = MaterialTheme.typography.bodySmall)
            PolicyDateField("Start date (UTC)", draft.startDate, enabled) { onChange(draft.copy(startDate = it)) }
            PolicyTimeField("Start time (UTC)", draft.startTime, enabled) { onChange(draft.copy(startTime = it)) }
            PolicyDateField("End date (UTC)", draft.endDate, enabled) { onChange(draft.copy(endDate = it)) }
            PolicyTimeField("End time (UTC)", draft.endTime, enabled) { onChange(draft.copy(endTime = it)) }
            Text("Sessions can last up to seven days.", style = MaterialTheme.typography.bodySmall)
        } else {
            PolicyTimeField("Start time", draft.startTime, enabled) { onChange(draft.copy(startTime = it)) }
            PolicyTimeField("End time", draft.endTime, enabled) { onChange(draft.copy(endTime = it)) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(FlintSpacing.xs)) {
                policyWeekdays.forEachIndexed { index, day ->
                    val iso = index + 1
                    FilterChip(selected = iso in draft.isoWeekdays, onClick = {
                        onChange(draft.copy(isoWeekdays = if (iso in draft.isoWeekdays) draft.isoWeekdays - iso else draft.isoWeekdays + iso))
                    }, label = { Text(day) }, enabled = enabled)
                }
            }
            Text("Days mark when a window starts. An earlier end time means it ends the next day.", style = MaterialTheme.typography.bodySmall)
        }
    }
    FlintCard {
        Text("Apps and websites", style = MaterialTheme.typography.titleMedium)
        PolicySwitch("All apps", draft.allApps, enabled) { onChange(draft.copy(allApps = it)) }
        if (!draft.allApps) {
            Text("Choose apps installed on this device or enter package names. This is not an inventory of members' devices.", style = MaterialTheme.typography.bodySmall)
            val selected = draft.packagesText.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
            val labels = state.localApps.associate { it.packageName to it.label }
            if (selected.isNotEmpty()) Text(selected.joinToString(", ") { labels[it] ?: it })
            OutlinedButton(onClick = { appPicker = true }, enabled = enabled && state.appsLoaded) { Text("Choose local apps") }
            state.appsError?.let { Text(it); TextButton(onClick = onReloadApps, enabled = enabled) { Text("Retry app list") } }
            TextButton(onClick = { advancedPackages = !advancedPackages }, enabled = enabled) { Text("Advanced package entry") }
            if (advancedPackages) {
                OutlinedTextField(draft.packagesText, { onChange(draft.copy(packagesText = it.take(6000))) },
                    label = { Text("Android packages, one per line") }, supportingText = { Text("Up to 20, for example com.example.app.") },
                    enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
            OutlinedTextField(draft.domainsText, { onChange(draft.copy(domainsText = it.take(6000))) },
                label = { Text("Websites, one per line") }, supportingText = { Text("Up to 20 lowercase hostnames, for example example.com.") },
                enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2)
        }
        PolicySwitch("Allow unblocks in the saved policy", draft.unblocksAllowed, enabled) { onChange(draft.copy(unblocksAllowed = it)) }
        Text("Unblocking is not available in this build. This saves your policy preference only.", style = MaterialTheme.typography.bodySmall)
    }
    if (appPicker) {
        ManagedAppPicker(state.localApps, draft.packagesText,
            onClose = { appPicker = false },
            onSave = { onChange(draft.copy(packagesText = it.sorted().joinToString("\n"))); appPicker = false })
    }
}

@Composable
private fun ManagedAppPicker(apps: List<ManagedLocalApp>, packages: String, onClose: () -> Unit, onSave: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(packages.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Apps on this device · ${selected.size}/20") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }, key = { it.packageName }) { app ->
                        val checked = app.packageName in selected
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                                enabled = checked || selected.size < 20) { value ->
                                selected = if (value) selected + app.packageName else selected - app.packageName
                            }, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, onCheckedChange = null)
                            Text(app.label)
                        }
                    }
                }
                if (apps.isEmpty()) Text("No launchable apps found on this device. Use advanced package entry.")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Use selection") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun PolicySwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = flintSwitchColors())
    }
}

@Composable
private fun PolicyDateField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf<DatePickerDialog?>(null) }
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }
    OutlinedTextField(value, { onChange(it.take(10)) }, label = { Text(label) }, singleLine = true,
        enabled = enabled, modifier = Modifier.fillMaxWidth(), supportingText = { Text("YYYY-MM-DD") })
    TextButton(enabled = enabled, onClick = {
        val date = runCatching { LocalDate.parse(value) }.getOrElse { LocalDate.now(ZoneOffset.UTC) }
        picker?.dismiss()
        picker = DatePickerDialog(context, { _, year, month, day ->
            onChange(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day))
        }, date.year, date.monthValue - 1, date.dayOfMonth).also { it.show() }
    }) { Text("Choose date") }
}

@Composable
private fun PolicyTimeField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf<TimePickerDialog?>(null) }
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }
    OutlinedTextField(value, { onChange(it.take(12)) }, label = { Text(label) }, singleLine = true,
        enabled = enabled, modifier = Modifier.fillMaxWidth(), supportingText = { Text("24-hour time") })
    TextButton(enabled = enabled, onClick = {
        val time = runCatching { LocalTime.parse(value) }.getOrElse { LocalTime.of(9, 0) }
        picker?.dismiss()
        picker = TimePickerDialog(context, { _, hour, minute -> onChange(policyTime(hour * 60 + minute)) },
            time.hour, time.minute, true).also { it.show() }
    }) { Text("Choose time") }
}

@Composable
private fun PolicyNotice(message: String, error: Boolean = false) {
    FlintCard {
        Text(message, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

private fun memberLabel(uid: String, members: List<ConnectedMember>): String =
    members.firstOrNull { it.userId == uid }?.displayName?.takeIf(String::isNotBlank) ?: uid

private val policyWeekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

internal fun policyInstantLabel(epochMs: Long): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss 'UTC'", Locale.getDefault())
        .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(epochMs))

internal fun policyScheduleLabel(rule: ManagedRule): String = when (val schedule = rule.schedule) {
    is ManagedSchedule.Session -> "${policyInstantLabel(schedule.startsAtEpochMs)} – ${policyInstantLabel(schedule.endsAtEpochMs)}"
    is ManagedSchedule.Recurring -> schedule.isoWeekdays.sorted().joinToString(", ") { policyWeekdays[it - 1] } +
        " · ${policyTime(schedule.startMinute)}–${policyTime(schedule.endMinute)} · ${rule.zoneId}" +
        if (schedule.endMinute < schedule.startMinute) " (ends next day)" else ""
}
