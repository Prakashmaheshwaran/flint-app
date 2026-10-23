package com.flint.peakfocus.feature.connected

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.ui.FlintCard
import com.flint.peakfocus.core.common.ui.FlintScreenHeader
import com.flint.peakfocus.core.common.ui.FlintSectionLabel

/** Account and membership milestone backed by the configured local test service. */
@Composable
fun ConnectedScreen(
    config: ConnectedConfig?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (config == null) {
        BackHandler(onBack = onBack)
        ConnectedLayout(modifier) {
            TextButton(onClick = onBack) { Text("Back") }
            FlintScreenHeader("Account", eyebrow = "Connected services")
            FlintCard {
                Text("Accounts are unavailable in this build", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Use a build with the local test service configured to try accounts and groups.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        return
    }

    val context = LocalContext.current
    val viewModel: ConnectedViewModel = viewModel(factory = ConnectedViewModel.factory(context, config))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var policyGroupId by rememberSaveable(state.uid) { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val policyGroup = state.selectedGroup?.takeIf { it.id == policyGroupId }
    val accountUid = state.uid
    if (policyGroup != null && accountUid != null) {
        ManagedPolicyScreen(config, accountUid, policyGroup,
            onBack = { policyGroupId = null }, modifier = modifier)
        return
    }
    val goBack: () -> Unit = {
        if (state.selectedGroup != null) viewModel.closeGroup() else onBack()
    }
    BackHandler { if (!state.busy) goBack() }

    ConnectedLayout(modifier, contentKey = state.selectedGroup?.id ?: state.uid ?: state.email.orEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = goBack, enabled = !state.busy) {
                Text(if (state.selectedGroup != null) "Back to account" else "Back")
            }
            if (state.email != null) {
                TextButton(onClick = viewModel::refresh, enabled = !state.busy) { Text("Refresh") }
            }
        }
        FlintScreenHeader(
            title = state.selectedGroup?.name ?: "Account",
            eyebrow = "Local testing",
        )
        FlintCard {
            Text("Local test accounts", style = MaterialTheme.typography.titleSmall)
            Text(
                "Accounts, profiles and groups on this screen use the local test service. " +
                    "Use test details. These are separate from Opal accounts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ConnectedFeedback(state, onDismiss = viewModel::clearMessage)

        if (state.email == null) {
            AuthenticationCard(
                busy = state.busy,
                onAuthenticate = viewModel::authenticate,
                onResetPassword = viewModel::resetPassword,
            )
        } else if (state.selectedGroup != null) {
            GroupDetails(state, viewModel, onPolicies = { policyGroupId = state.selectedGroup?.id })
        } else {
            AccountOverview(state, viewModel)
        }
    }

    state.invitePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) viewModel.dismissInvite() },
            title = { Text("Join ${preview.groupName}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
                    Text("${groupKindLabel(preview.kind)} group")
                    Text("Expires: ${preview.expiresAt}")
                    Text("Your profile name will appear in the group's member list.")
                    state.error?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::acceptInvite, enabled = !state.busy) { Text("Join group") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissInvite, enabled = !state.busy) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConnectedLayout(
    modifier: Modifier,
    contentKey: String = "unavailable",
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        key(contentKey) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(FlintSpacing.gutter),
                verticalArrangement = Arrangement.spacedBy(FlintSpacing.cardGap),
                content = content,
            )
        }
    }
}

@Composable
private fun ConnectedFeedback(state: ConnectedUiState, onDismiss: () -> Unit) {
    val text = state.error ?: state.message ?: return
    val isError = state.error != null
    FlintCard(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Dismiss") }
    }
}

@Composable
private fun AuthenticationCard(
    busy: Boolean,
    onAuthenticate: (String, String, Boolean) -> Unit,
    onResetPassword: (String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    // Passwords must never be written to saved instance state.
    var password by remember { mutableStateOf("") }
    var create by rememberSaveable { mutableStateOf(false) }
    val canAuthenticate = !busy && email.trim().isNotEmpty() && password.isNotEmpty()

    FlintCard {
        Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
                FilterChip(
                    selected = !create,
                    onClick = { create = false },
                    enabled = !busy,
                    label = { Text("Sign in") },
                )
                FilterChip(
                    selected = create,
                    onClick = { create = true },
                    enabled = !busy,
                    label = { Text("Create account") },
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(254) },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(128) },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = if (create) ({ Text("Use at least 8 characters.") }) else null,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val submittedPassword = password
                    password = ""
                    onAuthenticate(email.trim(), submittedPassword, create)
                },
                enabled = canAuthenticate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (create) "Create test account" else "Sign in")
            }
            TextButton(
                onClick = {
                    password = ""
                    onResetPassword(email.trim())
                },
                enabled = !busy && email.trim().isNotEmpty(),
            ) { Text("Reset password") }
        }
    }
}

@Composable
private fun AccountOverview(state: ConnectedUiState, viewModel: ConnectedViewModel) {
    FlintSectionLabel("Profile")
    state.profile?.let { profile ->
        ProfileCard(profile, state.busy, viewModel::saveName)
    }
    FlintCard {
        Text(state.email.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = viewModel::signOut, enabled = !state.busy) { Text("Sign out") }
    }

    FlintSectionLabel("Your groups")
    if (state.groups.isEmpty()) {
        FlintCard {
            Text(
                when {
                    state.loaded -> "No groups yet"
                    state.busy -> "Loading groups…"
                    else -> "Groups haven't loaded"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            if (state.loaded) {
                Text("Create a group or preview an invitation code below.")
            } else if (!state.busy) {
                OutlinedButton(onClick = viewModel::refresh) { Text("Refresh groups") }
            }
        }
    }
    state.groups.forEach { group ->
        FlintCard {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${groupKindLabel(group.kind)} · ${memberCountLabel(group.memberCount)} · ${group.role}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { viewModel.openGroup(group) }, enabled = !state.busy) {
                Text("View group")
            }
        }
    }
    CreateGroupCard(state.busy, viewModel::createGroup)
    JoinGroupCard(state.busy, viewModel::previewInvite)
}

@Composable
private fun ProfileCard(profile: ConnectedProfile, busy: Boolean, onSave: (String) -> Unit) {
    // Preserve an edited draft, while keeping an untouched field in sync with the server.
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.displayName) }
    var lastServerName by rememberSaveable(profile.id) { mutableStateOf(profile.displayName) }
    LaunchedEffect(profile.id, profile.displayName) {
        if (name.trim() == lastServerName || name.trim() == profile.displayName) {
            // Matching the returned value also marks a successfully saved draft clean.
            name = profile.displayName
        }
        lastServerName = profile.displayName
    }
    FlintCard {
        Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Profile name") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(name.trim()) },
                enabled = !busy && name.trim().isNotEmpty() && name.trim() != profile.displayName,
            ) { Text("Save name") }
        }
    }
}

@Composable
private fun CreateGroupCard(busy: Boolean, onCreate: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("family") }
    FlintCard {
        Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
            Text("Create a group", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Group name") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
                listOf("family", "school").forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(groupKindLabel(option)) },
                        enabled = !busy,
                    )
                }
            }
            Button(onClick = { onCreate(name.trim(), kind) }, enabled = !busy && name.trim().isNotEmpty()) {
                Text("Create group")
            }
        }
    }
}

@Composable
private fun JoinGroupCard(busy: Boolean, onPreview: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    FlintCard {
        Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.sm)) {
            Text("Join a group", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.take(128) },
                label = { Text("Invitation code") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onPreview(code.trim()) }, enabled = !busy && code.trim().isNotEmpty()) {
                Text("Preview invitation")
            }
        }
    }
}

@Composable
private fun GroupDetails(state: ConnectedUiState, viewModel: ConnectedViewModel, onPolicies: () -> Unit) {
    val group = state.selectedGroup ?: return
    var confirmLeave by remember(group.id) { mutableStateOf(false) }

    FlintCard {
        Text(
            "${groupKindLabel(group.kind)} · ${memberCountLabel(group.memberCount)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text("Your role: ${group.role}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onPolicies, enabled = !state.busy) {
            Text(if (group.role == "owner") "Manage policies" else "View my policies")
        }
    }
    FlintSectionLabel("Members")
    FlintCard {
        Column(verticalArrangement = Arrangement.spacedBy(FlintSpacing.md)) {
            if (state.members.isEmpty()) {
                Text(
                    when {
                        state.membersLoaded -> "No members to show"
                        state.busy -> "Loading members…"
                        else -> "Members haven't loaded"
                    },
                )
                if (!state.membersLoaded && !state.busy) {
                    OutlinedButton(onClick = viewModel::refresh) { Text("Refresh members") }
                }
            }
            state.members.forEach { member ->
                Column {
                    Text(member.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        member.role,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (group.role.equals("owner", ignoreCase = true)) {
        FlintCard {
            Text("Invite a member", style = MaterialTheme.typography.titleMedium)
            Text("Generate a code to copy. No invitation is sent automatically.")
            Button(onClick = viewModel::createInvite, enabled = !state.busy) { Text("Generate invitation code") }
        }
        state.createdInvite?.takeIf { it.groupId == group.id }?.let { invite ->
            CreatedInviteCard(invite, state.busy)
        }
    } else {
        OutlinedButton(onClick = { confirmLeave = true }, enabled = !state.busy) { Text("Leave group") }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) confirmLeave = false },
            title = { Text("Leave ${group.name}?") },
            text = { Text("You will lose access to this group. You will need an invitation to join again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        viewModel.leaveGroup()
                    },
                    enabled = !state.busy,
                ) { Text("Leave group") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }, enabled = !state.busy) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CreatedInviteCard(invite: CreatedInvite, busy: Boolean) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(invite.code) { mutableStateOf(false) }
    LaunchedEffect(invite.code) { copied = false }
    FlintCard {
        Text("Invitation code", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(invite.code, style = MaterialTheme.typography.titleLarge) }
        Text("Expires: ${invite.expiresAt}", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(invite.code))
                copied = true
            },
            enabled = !busy,
        ) { Text(if (copied) "Code copied" else "Copy code") }
    }
}

private fun groupKindLabel(kind: String): String = when (kind.lowercase()) {
    "family" -> "Family"
    "school" -> "School"
    else -> kind
}

private fun memberCountLabel(count: Int): String = if (count == 1) "1 member" else "$count members"
