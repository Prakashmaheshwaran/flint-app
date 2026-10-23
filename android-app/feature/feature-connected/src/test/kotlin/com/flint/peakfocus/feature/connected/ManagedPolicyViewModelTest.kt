package com.flint.peakfocus.feature.connected

import androidx.lifecycle.ViewModelStore
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedPolicyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val owner = ManagedIdentity("demo-flint", "owner", 1)
    private val group = ConnectedGroup("group", "Family", "family", "owner", "owner", 2)
    private val otherGroup = group.copy(id = "other", name = "School")

    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owners.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun model(api: FakeApi): ManagedPolicyViewModel = ManagedPolicyViewModel(api, now = { 1_800_000_000_000L }).also {
        ViewModelStore().also { store -> store.put("policy", it); owners += store }
    }

    private fun validNew(vm: ManagedPolicyViewModel) {
        vm.newPolicy()
        vm.updateDraft(vm.uiState.value.draft!!.copy(name = "New policy", allApps = true))
    }

    private fun rule(groupId: String = group.id, id: String = "rule", revision: Long = 5, name: String = "Study") = ManagedRule(
        id, groupId, revision, name, "UTC", true, ManagedRuleScope.Group,
        ManagedSchedule.Recurring(540, 900, setOf(1, 2, 3, 4, 5)), ManagedTargets.AllApps, false, 1, 2,
    )

    @Test fun `owner loads authoring page and current members without fetching member authority`() = runTest(dispatcher) {
        val api = FakeApi(owner).apply { pages[group.id] = listOf(rule()) }
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        assertTrue(vm.uiState.value.canWrite)
        assertEquals(1, vm.uiState.value.page!!.rules.size)
        assertEquals("Taylor", vm.uiState.value.members.last().displayName)
        assertEquals(0, api.refreshCalls)
    }

    @Test fun `update sends captured rule revision not global page revision`() = runTest(dispatcher) {
        val api = FakeApi(owner).apply { pages[group.id] = listOf(rule(revision = 5)); revision = 100 }
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        vm.editPolicy("rule")
        vm.updateDraft(vm.uiState.value.draft!!.copy(name = "Edited"))
        vm.save()
        runCurrent()
        assertEquals(5L, api.expectedRevision)
        assertEquals("Edited", vm.uiState.value.page!!.rules.single().name)
        assertNull(vm.uiState.value.draft)
    }

    @Test fun `refresh preserves dirty draft and detects changed or deleted revision`() = runTest(dispatcher) {
        val api = FakeApi(owner).apply { pages[group.id] = listOf(rule()) }
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        vm.editPolicy("rule")
        vm.updateDraft(vm.uiState.value.draft!!.copy(name = "My unsaved edit"))
        api.pages[group.id] = listOf(rule(revision = 6, name = "Changed elsewhere"))
        api.revision = 11
        vm.refresh()
        runCurrent()
        assertEquals("My unsaved edit", vm.uiState.value.draft!!.name)
        assertEquals(5L, vm.uiState.value.draft!!.expectedRevision)
        assertTrue(vm.uiState.value.conflict)
        vm.save()
        assertEquals(0, api.updateCalls)
        vm.loadLatestDraft()
        assertEquals("Changed elsewhere", vm.uiState.value.draft!!.name)
        assertEquals(6L, vm.uiState.value.draft!!.expectedRevision)
        assertFalse(vm.uiState.value.dirty)
        api.pages[group.id] = emptyList()
        api.revision++
        vm.refresh()
        runCurrent()
        assertTrue(vm.uiState.value.conflict)
        assertFalse(vm.uiState.value.canWrite)
    }

    @Test fun `uncertain creation blocks another POST until refresh and explicit reconciliation`() = runTest(dispatcher) {
        val api = FakeApi(owner).apply { createFailure = ManagedFailureKind.AMBIGUOUS_MUTATION }
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        validNew(vm)
        vm.save()
        runCurrent()
        assertTrue(vm.uiState.value.uncertainCreate)
        vm.acknowledgeUncertainCreate()
        vm.save()
        assertEquals(1, api.createCalls)
        vm.refresh()
        runCurrent()
        assertTrue(vm.uiState.value.uncertaintyRefreshed)
        assertFalse(vm.uiState.value.canWrite)
        api.createFailure = null
        vm.acknowledgeUncertainCreate()
        vm.save()
        runCurrent()
        assertEquals(2, api.createCalls)
        assertNull(vm.uiState.value.draft)
        assertFalse(vm.uiState.value.uncertainCreate)
    }

    @Test fun `late create after switching groups cannot publish into the new group`() = runTest(dispatcher) {
        lateMutation("create")
    }

    @Test fun `late update after switching groups cannot publish into the new group`() = runTest(dispatcher) {
        lateMutation("update")
    }

    @Test fun `late delete after switching groups cannot publish into the new group`() = runTest(dispatcher) {
        lateMutation("delete")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.lateMutation(operation: String) {
        val api = FakeApi(owner).apply {
            pages[group.id] = listOf(rule())
            pages[otherGroup.id] = listOf(rule(otherGroup.id, "other-rule", name = "Other policy"))
        }
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        if (operation == "create") validNew(vm) else vm.editPolicy("rule")
        val gate = CompletableDeferred<Unit>()
        api.mutationGate = gate
        if (operation == "delete") vm.delete() else vm.save()
        runCurrent()
        assertTrue(vm.uiState.value.busy)
        vm.closeView()
        vm.openGroup(otherGroup, owner.accountUid)
        runCurrent()
        val before = vm.uiState.value.page
        gate.complete(Unit)
        runCurrent()
        assertEquals(otherGroup.id, vm.uiState.value.group!!.id)
        assertEquals(before, vm.uiState.value.page)
        assertNull(vm.uiState.value.message)
        assertNull(vm.uiState.value.draft)
    }

    @Test fun `late response after close cannot publish and reopened create requires reconciliation`() = runTest(dispatcher) {
        val api = FakeApi(owner)
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        validNew(vm)
        val gate = CompletableDeferred<Unit>()
        api.mutationGate = gate
        vm.save()
        runCurrent()
        vm.closeView()
        assertFalse(vm.uiState.value.busy)
        assertTrue(vm.uiState.value.uncertainCreate)
        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.page!!.rules.isEmpty())
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        assertEquals(1, vm.uiState.value.page!!.rules.size)
        assertTrue(vm.uiState.value.uncertainCreate)
        assertFalse(vm.uiState.value.canWrite)
    }

    @Test fun `same UID new auth generation clears draft and rejects old mutation receipt`() = runTest(dispatcher) {
        val api = FakeApi(owner).apply { pages[group.id] = listOf(rule()) }
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        validNew(vm)
        val gate = CompletableDeferred<Unit>()
        api.mutationGate = gate
        vm.save()
        runCurrent()
        api.identity.value = owner.copy(sessionGeneration = 2)
        runCurrent()
        assertNull(vm.uiState.value.draft)
        val before = vm.uiState.value.page
        gate.complete(Unit)
        runCurrent()
        assertEquals(before, vm.uiState.value.page)
        assertNull(vm.uiState.value.message)
        assertEquals(2L, vm.uiState.value.identity!!.sessionGeneration)
    }

    @Test fun `foreign identity removes owner page and dirty draft`() = runTest(dispatcher) {
        val api = FakeApi(owner)
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        validNew(vm)
        api.identity.value = ManagedIdentity(owner.projectId, "other-account", 2)
        runCurrent()
        assertNull(vm.uiState.value.group)
        assertNull(vm.uiState.value.page)
        assertNull(vm.uiState.value.draft)
        assertFalse(vm.uiState.value.canWrite)
    }

    @Test fun `identity change before collector runs cannot dispatch the old draft`() = runTest(dispatcher) {
        val api = FakeApi(owner)
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        validNew(vm)
        api.identity.value = owner.copy(sessionGeneration = 2)
        // The UI still has its old draft until the queued collector runs.
        vm.save()
        runCurrent()
        assertEquals(0, api.createCalls)
        assertNull(vm.uiState.value.draft)
    }

    @Test fun `identity change after button callback but before launch cannot dispatch old draft`() = runTest(dispatcher) {
        val api = FakeApi(owner)
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        validNew(vm)
        vm.save()
        api.identity.value = owner.copy(sessionGeneration = 2)
        runCurrent()
        assertEquals(0, api.createCalls)
        assertNull(vm.uiState.value.draft)
    }

    @Test fun `failed owner refresh keeps prior page marked stale and blocks mutations`() = runTest(dispatcher) {
        val api = FakeApi(owner).apply { pages[group.id] = listOf(rule()) }
        val vm = model(api)
        vm.openGroup(group, owner.accountUid)
        runCurrent()
        api.listFailure = true
        vm.refresh()
        runCurrent()
        assertEquals(1, vm.uiState.value.page!!.rules.size)
        assertTrue(vm.uiState.value.staleOwnerPage)
        assertFalse(vm.uiState.value.canWrite)
        assertNotNull(vm.uiState.value.error)
    }

    @Test fun `member inspection never calls owner endpoints and rejects obsolete cached identity`() = runTest(dispatcher) {
        val member = ManagedIdentity(owner.projectId, "member", 1)
        val api = FakeApi(member)
        val snapshot = ManagedSnapshot(member, revision = 10, serverTimeEpochMs = 100,
            memberships = listOf(ManagedMembership(group.id, "membership", ManagedRole.MEMBER)), rules = listOf(rule()), tombstones = emptyList())
        api.authority.value = ManagedAuthorityState.Ready(snapshot, ManagedClock.Confirmed(100))
        val vm = model(api)
        vm.openGroup(group.copy(role = "member"), member.accountUid)
        runCurrent()
        assertEquals(0, api.listCalls)
        assertEquals(1, api.refreshCalls)
        assertEquals(snapshot, acceptedPolicySnapshot(vm.uiState.value.authority, member))
        assertNull(acceptedPolicySnapshot(vm.uiState.value.authority, member.copy(sessionGeneration = 2)))
        api.authority.value = ManagedAuthorityState.Stale(snapshot, ManagedClock.Unconfirmed("offline"), "offline")
        runCurrent()
        assertEquals(snapshot, acceptedPolicySnapshot(vm.uiState.value.authority, member))
        assertNull(vm.uiState.value.page)
        assertFalse(vm.uiState.value.canWrite)
    }

    private class FakeApi(initialIdentity: ManagedIdentity) : ManagedPolicyApi {
        override val identity = MutableStateFlow<ManagedIdentity?>(initialIdentity)
        override val authority = MutableStateFlow<ManagedAuthorityState>(ManagedAuthorityState.SignedOut)
        val pages = mutableMapOf<String, List<ManagedRule>>()
        var revision = 10L
        var mutationGate: CompletableDeferred<Unit>? = null
        var createFailure: ManagedFailureKind? = null
        var listFailure = false
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var listCalls = 0
        var refreshCalls = 0
        var expectedRevision: Long? = null
        override suspend fun list(groupId: String): ManagedRulePage {
            listCalls++
            if (listFailure) throw ManagedRequestFailure(ManagedFailureKind.NETWORK, "offline")
            return ManagedRulePage(identity.value!!, revision, pages[groupId].orEmpty(), emptyList())
        }
        override suspend fun members(groupId: String) = listOf(
            ConnectedMember("owner", "Owner", "owner"), ConnectedMember("member", "Taylor", "member"),
        )
        override suspend fun create(groupId: String, input: ManagedRuleInput): ManagedRuleResult {
            createCalls++
            val requestIdentity = identity.value!!
            mutationGate?.await()
            createFailure?.let { throw ManagedRequestFailure(it, "failed") }
            val rule = input.rule("created-$createCalls", groupId, ++revision)
            pages[groupId] = pages[groupId].orEmpty() + rule
            return ManagedRuleResult(requestIdentity, revision, rule)
        }
        override suspend fun update(groupId: String, ruleId: String, revision: Long, input: ManagedRuleInput): ManagedRuleResult {
            updateCalls++
            expectedRevision = revision
            val requestIdentity = identity.value!!
            mutationGate?.await()
            val rule = input.rule(ruleId, groupId, ++this.revision)
            pages[groupId] = pages[groupId].orEmpty().filterNot { it.id == ruleId } + rule
            return ManagedRuleResult(requestIdentity, this.revision, rule)
        }
        override suspend fun delete(groupId: String, ruleId: String, revision: Long): ManagedDeleteResult {
            deleteCalls++
            expectedRevision = revision
            val requestIdentity = identity.value!!
            mutationGate?.await()
            pages[groupId] = pages[groupId].orEmpty().filterNot { it.id == ruleId }
            return ManagedDeleteResult(requestIdentity, ++this.revision, ManagedTombstone(ruleId, groupId, this.revision, 100))
        }
        override suspend fun refresh(): ManagedRefreshResult {
            refreshCalls++
            return ManagedRefreshResult.Retained(authority.value)
        }
        override suspend fun localApps() = listOf(ManagedLocalApp("com.example.app", "Example"))
        private fun ManagedRuleInput.rule(id: String, groupId: String, revision: Long) =
            ManagedRule(id, groupId, revision, name, zoneId, enabled, scope, schedule, targets, unblocksAllowed, 1, 2)
    }
}
