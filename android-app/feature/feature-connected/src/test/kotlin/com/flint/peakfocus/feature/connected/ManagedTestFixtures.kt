package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.model.*
import com.google.gson.*
import kotlinx.coroutines.CompletableDeferred

internal object ManagedFixture {
    const val group = "10000000-0000-0000-0000-000000000001"
    const val member = "20000000-0000-0000-0000-000000000001"
    const val rule = "30000000-0000-0000-0000-000000000001"
    val identity = ManagedIdentity("demo-flint", "member", 1)
    val input = ManagedRuleInput("Evening", "America/New_York", true, ManagedRuleScope.Group,
        ManagedSchedule.Recurring(1200, 1380, setOf(1, 2, 3, 4, 5)), ManagedTargets.Selection(setOf("com.example.app"), setOf("example.com")), false)
    fun rule(revision: Long = 2): JsonObject = ManagedWireCodec.input(input).apply {
        addProperty("id", rule); addProperty("targetGroupId", group); addProperty("revision", revision)
        addProperty("createdAt", "2026-10-11T00:00:00.000Z"); addProperty("updatedAt", "2026-10-11T00:00:00.000Z")
    }
    fun snapshot(revision: Long = 2, includeRule: Boolean = true, membership: Boolean = true): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 1); addProperty("complete", true); addProperty("userId", "member")
        addProperty("revision", revision); addProperty("serverTime", "2026-10-11T01:00:00.000Z")
        add("memberships", JsonArray().apply { if (membership) add(JsonObject().apply { addProperty("groupId", group); addProperty("membershipId", member); addProperty("role", "member") }) })
        add("rules", JsonArray().apply { if (includeRule) add(rule(minOf(2, revision))) })
        add("tombstones", JsonArray())
    }
}

internal class FakeConnectedAuth(var user: String? = "member") : ConnectedAuthGateway {
    override val uid get() = user
    override val email get() = user?.let { "$it@example.test" }
    private val listeners = mutableListOf<() -> Unit>()
    var tokenCount = 0
    var signOutCount = 0
    var authentication: CompletableDeferred<Unit>? = null
    override fun observe(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    override suspend fun authenticate(email: String, password: String, create: Boolean) {
        authentication?.await()
        user = email.substringBefore('@'); listeners.toList().forEach { it() }
    }
    override suspend fun resetPassword(email: String) = Unit
    override suspend fun token(forceRefresh: Boolean): String { tokenCount++; return "synthetic-token" }
    override fun signOut() { signOutCount++; user = null; listeners.toList().forEach { it() } }
}

internal class FakeManagedClock(var boot: String = "boot-1", var elapsed: Long = 1000) : ManagedDeviceClock {
    override val bootId get() = boot
    override fun elapsedRealtime() = elapsed
}
