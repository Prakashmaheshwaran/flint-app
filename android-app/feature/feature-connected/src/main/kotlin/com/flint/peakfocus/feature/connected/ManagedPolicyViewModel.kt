package com.flint.peakfocus.feature.connected

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.flint.peakfocus.core.model.ManagedIdentity
import com.flint.peakfocus.core.model.ManagedRule
import com.flint.peakfocus.core.model.ManagedSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ManagedLocalApp(val packageName: String, val label: String)

/** UI-only authoring state. It is never published to a blocking engine or personal store. */
internal data class ManagedPolicyUiState(
    val identity: ManagedIdentity? = null,
    val group: ConnectedGroup? = null,
    val page: ManagedRulePage? = null,
    val members: List<ConnectedMember> = emptyList(),
    val localApps: List<ManagedLocalApp> = emptyList(),
    val appsLoaded: Boolean = false,
    val appsError: String? = null,
    val authority: ManagedAuthorityState = ManagedAuthorityState.SignedOut,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val staleOwnerPage: Boolean = false,
    val draft: ManagedPolicyDraft? = null,
    val baseline: ManagedPolicyDraft? = null,
    val conflict: Boolean = false,
    val uncertainCreate: Boolean = false,
    val uncertaintyRefreshed: Boolean = false,
    val error: String? = null,
    val message: String? = null,
) {
    val owner: Boolean get() = group?.role == "owner" && group.ownerId == identity?.accountUid
    val dirty: Boolean get() = draft != null && draft != baseline
    val canWrite: Boolean get() = owner && page != null && page.identity == identity &&
        !loading && !busy && !staleOwnerPage && !conflict && !uncertainCreate
}

/** Injectable UI port; production delegates all authentication and authority to the repository. */
internal interface ManagedPolicyApi {
    val identity: StateFlow<ManagedIdentity?>
    val authority: StateFlow<ManagedAuthorityState>
    suspend fun list(groupId: String): ManagedRulePage
    suspend fun members(groupId: String): List<ConnectedMember>
    suspend fun create(groupId: String, input: ManagedRuleInput): ManagedRuleResult
    suspend fun update(groupId: String, ruleId: String, revision: Long, input: ManagedRuleInput): ManagedRuleResult
    suspend fun delete(groupId: String, ruleId: String, revision: Long): ManagedDeleteResult
    suspend fun refresh(): ManagedRefreshResult
    suspend fun localApps(): List<ManagedLocalApp>
}

internal class ManagedPolicyViewModel(
    private val api: ManagedPolicyApi,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val state = MutableStateFlow(ManagedPolicyUiState(identity = api.identity.value, authority = api.authority.value))
    val uiState: StateFlow<ManagedPolicyUiState> = state.asStateFlow()
    private var requestedGroup: ConnectedGroup? = null
    private var requestedUid: String? = null
    private var navigationGeneration = 0L

    init {
        viewModelScope.launch {
            api.identity.collect { identity ->
                if (identity == state.value.identity) return@collect
                navigationGeneration++
                val old = state.value
                state.value = ManagedPolicyUiState(identity = identity, authority = api.authority.value,
                    localApps = old.localApps, appsLoaded = old.appsLoaded, appsError = old.appsError)
                val group = requestedGroup
                if (identity != null && identity.accountUid == requestedUid && group != null) {
                    state.update { it.copy(group = group) }
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            api.authority.collect { authority -> state.update { it.copy(authority = authority) } }
        }
        loadApps()
    }

    fun openGroup(group: ConnectedGroup, accountUid: String) {
        requestedGroup = group
        requestedUid = accountUid
        val identity = api.identity.value
        if (identity?.accountUid != accountUid) return
        if (state.value.identity == identity && state.value.group?.id == group.id) {
            state.update { it.copy(group = group) }
            if (!state.value.busy) refresh()
            return
        }
        navigationGeneration++
        state.value = ManagedPolicyUiState(identity = identity, group = group, authority = api.authority.value,
            localApps = state.value.localApps, appsLoaded = state.value.appsLoaded, appsError = state.value.appsError)
        refresh()
    }

    /** Prevent a future account event from fetching a group that is no longer being viewed. */
    fun closeView() {
        requestedGroup = null
        requestedUid = null
        navigationGeneration++
        state.update { current ->
            current.copy(
                loading = false, busy = false,
                staleOwnerPage = current.staleOwnerPage || current.busy,
                uncertainCreate = current.uncertainCreate || current.busy && current.draft?.id == null,
                uncertaintyRefreshed = if (current.busy) false else current.uncertaintyRefreshed,
            )
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            try {
                val apps = api.localApps()
                state.update { it.copy(localApps = apps, appsLoaded = true, appsError = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                state.update { it.copy(appsError = "Couldn't load apps from this device. You can retry or enter package names.") }
            }
        }
    }

    fun refresh() {
        val before = state.value
        val group = before.group ?: return
        val identity = before.identity ?: return
        if (before.loading || before.busy) return
        val generation = navigationGeneration
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (before.owner) {
                    val page = api.list(group.id)
                    val members = api.members(group.id)
                    if (!current(identity, generation, group.id) || page.identity != identity) return@launch
                    acceptOwnerPage(page, members)
                } else {
                    api.refresh()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current(identity, generation, group.id)) state.update {
                    it.copy(error = policyError(error), staleOwnerPage = before.owner)
                }
            } finally {
                if (current(identity, generation, group.id)) state.update { it.copy(loading = false) }
            }
        }
    }

    private fun acceptOwnerPage(page: ManagedRulePage, members: List<ConnectedMember>) {
        state.update { current ->
            if (current.page != null && page.revision < current.page.revision) return@update current
            val draft = current.draft
            val saved = page.rules.firstOrNull { it.id == draft?.id }
            current.copy(
                page = page, members = members, staleOwnerPage = false,
                conflict = draft?.id != null && saved?.revision != draft.expectedRevision,
                uncertaintyRefreshed = current.uncertainCreate,
            )
        }
    }

    fun newPolicy() {
        if (!state.value.canWrite || state.value.page!!.rules.size >= 10) return
        val draft = newManagedPolicyDraft(now())
        state.update { it.copy(draft = draft, baseline = draft, error = null, message = null, conflict = false) }
    }

    fun editPolicy(ruleId: String) {
        val current = state.value
        if (!current.owner || current.loading || current.busy || current.staleOwnerPage) return
        val rule = current.page?.rules?.firstOrNull { it.id == ruleId } ?: return
        val draft = rule.toPolicyDraft()
        state.update { it.copy(draft = draft, baseline = draft, conflict = false, uncertainCreate = false,
            uncertaintyRefreshed = false, error = null, message = null) }
    }

    fun updateDraft(draft: ManagedPolicyDraft) {
        val old = state.value.draft ?: return
        if (state.value.busy || old.id != draft.id || old.expectedRevision != draft.expectedRevision) return
        state.update { it.copy(draft = draft, error = null, message = null) }
    }

    fun closeEditor() {
        if (state.value.busy) return
        // Keep uncertain-creation recovery attached to this group even after closing the form.
        state.update { it.copy(draft = null, baseline = null, conflict = false, error = null) }
    }

    fun loadLatestDraft() {
        val id = state.value.draft?.id ?: return
        editPolicy(id)
    }

    fun acknowledgeUncertainCreate() {
        if (state.value.uncertainCreate && state.value.uncertaintyRefreshed && !state.value.loading && !state.value.busy) {
            state.update { it.copy(uncertainCreate = false, uncertaintyRefreshed = false, error = null) }
        }
    }

    fun save() {
        val current = state.value
        val draft = current.draft ?: return
        if (!current.canWrite) return
        val group = current.group ?: return
        val input = try { managedPolicyInput(draft, current.members, group.ownerId) } catch (error: IllegalArgumentException) {
            state.update { it.copy(error = error.message ?: "Check the policy details.") }
            return
        }
        mutate(creating = draft.id == null) { lease ->
            val result = if (draft.id == null) api.create(group.id, input)
            else api.update(group.id, draft.id, requireNotNull(draft.expectedRevision), input)
            if (result.identity != lease.identity || !current(lease.identity, lease.navigation, lease.groupId)) return@mutate
            state.update {
                val page = it.page ?: return@update it
                it.copy(page = page.copy(revision = result.revision,
                    rules = (page.rules.filterNot { rule -> rule.id == result.rule.id } + result.rule).sortedBy { rule -> rule.name }),
                    draft = null, baseline = null, message = "Policy saved to the local service. Device blocking is not active yet.")
            }
        }
    }

    fun delete() {
        val current = state.value
        if (!current.canWrite) return
        val group = current.group ?: return
        val draft = current.draft ?: return
        val id = draft.id ?: return
        val revision = draft.expectedRevision ?: return
        mutate(creating = false) { lease ->
            val result = api.delete(group.id, id, revision)
            if (result.identity != lease.identity || !current(lease.identity, lease.navigation, lease.groupId)) return@mutate
            state.update {
                val page = it.page ?: return@update it
                it.copy(page = page.copy(revision = result.revision,
                    rules = page.rules.filterNot { rule -> rule.id == id },
                    tombstones = page.tombstones.filterNot { tombstone -> tombstone.id == id } + result.tombstone),
                    draft = null, baseline = null, message = "Policy deleted from the local service.")
            }
        }
    }

    private data class RequestLease(val identity: ManagedIdentity, val navigation: Long, val groupId: String)

    private fun mutate(creating: Boolean, block: suspend (RequestLease) -> Unit) {
        val identity = state.value.identity ?: return
        // StateFlow collection can lag an auth event by one main-thread dispatch.
        if (identity != api.identity.value) return
        val generation = navigationGeneration
        val groupId = state.value.group?.id ?: return
        state.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                if (!current(identity, generation, groupId)) return@launch
                block(RequestLease(identity, generation, groupId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current(identity, generation, groupId)) state.update { current ->
                    val kind = (error as? ManagedRequestFailure)?.kind
                    val uncertain = creating && kind == ManagedFailureKind.AMBIGUOUS_MUTATION
                    current.copy(
                        error = policyError(error),
                        conflict = current.conflict || kind == ManagedFailureKind.REVISION_CONFLICT || kind == ManagedFailureKind.NOT_FOUND,
                        uncertainCreate = current.uncertainCreate || uncertain,
                        uncertaintyRefreshed = if (uncertain) false else current.uncertaintyRefreshed,
                        staleOwnerPage = current.staleOwnerPage || kind in setOf(ManagedFailureKind.FORBIDDEN,
                            ManagedFailureKind.REVISION_CONFLICT, ManagedFailureKind.NOT_FOUND,
                            ManagedFailureKind.AMBIGUOUS_MUTATION, ManagedFailureKind.CAPACITY),
                    )
                }
            } finally {
                if (current(identity, generation, groupId)) state.update { it.copy(busy = false) }
            }
        }
    }

    private fun current(identity: ManagedIdentity, navigation: Long, groupId: String): Boolean =
        identity == api.identity.value && identity == state.value.identity &&
            navigation == navigationGeneration && state.value.group?.id == groupId

    companion object {
        fun factory(context: Context, config: ConnectedConfig): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ManagedPolicyViewModel(
                    RepositoryPolicyApi(ConnectedRepository(appContext, config), appContext),
                ) as T
            }
        }
    }
}

internal fun acceptedPolicySnapshot(authority: ManagedAuthorityState, identity: ManagedIdentity?): ManagedSnapshot? {
    val snapshot = when (authority) {
        is ManagedAuthorityState.Ready -> authority.snapshot
        is ManagedAuthorityState.Stale -> authority.snapshot
        is ManagedAuthorityState.Loading -> authority.cached
        else -> null
    }
    return snapshot?.takeIf { it.identity == identity }
}

private fun policyError(error: Exception): String = when ((error as? ManagedRequestFailure)?.kind) {
    ManagedFailureKind.REVISION_CONFLICT -> "This policy changed elsewhere. Refresh and review the latest saved version."
    ManagedFailureKind.AMBIGUOUS_MUTATION -> "The result is uncertain. Refresh the policy list before making another change."
    ManagedFailureKind.CAPACITY -> "This group has reached its policy limit. Refresh the list; delete a policy before adding another."
    ManagedFailureKind.FORBIDDEN -> "Your account cannot manage this group's policies. Refresh your groups."
    ManagedFailureKind.NOT_FOUND -> "This policy or group is no longer available. Refresh the policy list."
    ManagedFailureKind.IDENTITY_CHANGED, ManagedFailureKind.UNAUTHENTICATED -> "Your account changed or signed out. Return to your account."
    ManagedFailureKind.VALIDATION -> "The service rejected these policy details. Check the fields and target size."
    ManagedFailureKind.RATE_LIMITED -> "Too many requests. Wait before refreshing."
    ManagedFailureKind.PROTOCOL, ManagedFailureKind.STORAGE -> "The policy response could not be accepted. Existing saved policies have been retained."
    else -> "Couldn't reach the local policy service. Refresh to try again."
}

private class RepositoryPolicyApi(
    private val repository: ConnectedRepository,
    private val context: Context,
) : ManagedPolicyApi {
    override val identity get() = repository.managedIdentity
    override val authority get() = repository.managedState
    override suspend fun list(groupId: String) = repository.managedRules(groupId)
    override suspend fun members(groupId: String) = repository.members(groupId)
    override suspend fun create(groupId: String, input: ManagedRuleInput) = repository.createManagedRule(groupId, input)
    override suspend fun update(groupId: String, ruleId: String, revision: Long, input: ManagedRuleInput) =
        repository.updateManagedRule(groupId, ruleId, revision, input)
    override suspend fun delete(groupId: String, ruleId: String, revision: Long) =
        repository.deleteManagedRule(groupId, ruleId, revision)
    override suspend fun refresh() = repository.refreshManagedState()
    override suspend fun localApps(): List<ManagedLocalApp> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(intent, 0).mapNotNull {
            val name = it.activityInfo?.packageName ?: return@mapNotNull null
            if (name == context.packageName) return@mapNotNull null
            ManagedLocalApp(name, it.loadLabel(context.packageManager)?.toString()?.takeIf(String::isNotBlank) ?: name)
        }.distinctBy { it.packageName }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
