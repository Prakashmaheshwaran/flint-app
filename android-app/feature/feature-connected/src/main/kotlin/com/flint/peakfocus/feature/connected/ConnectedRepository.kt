package com.flint.peakfocus.feature.connected

import android.content.Context
import org.json.JSONObject
import java.net.URLEncoder

/** Optional account service. Managed selections upload only through explicit owner authoring. */
internal class ConnectedRepository(context: Context, config: ConnectedConfig) {
    private val runtime = ConnectedRuntimes.open(context.applicationContext, config)
    val uid: String? get() = runtime.identity.uid
    val email: String? get() = runtime.identity.email
    val managedIdentity get() = runtime.identity.managedIdentity
    val managedState get() = runtime.managed.state

    fun observeIdentity(onChanged: () -> Unit): () -> Unit = runtime.identity.observe(onChanged)
    suspend fun authenticate(email: String, password: String, create: Boolean) = runtime.identity.authenticate(email, password, create)
    suspend fun resetPassword(email: String) = runtime.identity.resetPassword(email)
    suspend fun signOut() = runtime.identity.signOut()
    suspend fun managedRules(groupId: String) = runtime.managed.page(groupId)
    suspend fun managedRule(groupId: String, ruleId: String) = runtime.managed.rule(groupId, ruleId)
    suspend fun createManagedRule(groupId: String, input: ManagedRuleInput) = runtime.managed.create(groupId, input)
    suspend fun updateManagedRule(groupId: String, ruleId: String, expectedRevision: Long, input: ManagedRuleInput) = runtime.managed.update(groupId, ruleId, expectedRevision, input)
    suspend fun deleteManagedRule(groupId: String, ruleId: String, expectedRevision: Long) = runtime.managed.delete(groupId, ruleId, expectedRevision)
    suspend fun refreshManagedState() = runtime.managed.refresh()

    suspend fun profile(): ConnectedProfile = request("GET", "/v1/me").getJSONObject("user").let {
        ConnectedProfile(it.getString("id"), it.optString("email"), it.optString("displayName"))
    }

    suspend fun saveName(name: String) {
        request("PATCH", "/v1/me", JSONObject().put("displayName", name))
    }

    suspend fun groups(): List<ConnectedGroup> = request("GET", "/v1/groups").getJSONArray("groups").let { rows ->
        List(rows.length()) { group(rows.getJSONObject(it)) }
    }

    suspend fun createGroup(name: String, kind: String): ConnectedGroup = runtime.managed.membershipChange { lease ->
        group(request(lease, "POST", "/v1/groups", JSONObject().put("name", name).put("kind", kind)).getJSONObject("group"))
    }

    suspend fun members(id: String): List<ConnectedMember> =
        request("GET", "/v1/groups/${segment(id)}/members").getJSONArray("members").let { rows ->
            List(rows.length()) {
                val row = rows.getJSONObject(it)
                ConnectedMember(row.getString("userId"), row.optString("displayName"), row.getString("role"))
            }
        }

    suspend fun createInvite(id: String): CreatedInvite =
        request("POST", "/v1/groups/${segment(id)}/invites", JSONObject()).getJSONObject("invite").let {
            CreatedInvite(it.getString("code"), it.getString("groupId"), it.getString("expiresAt"))
        }

    suspend fun previewInvite(code: String): InvitePreview =
        request("GET", "/v1/invites/${segment(code)}").getJSONObject("invite").let {
            InvitePreview(code, it.getString("groupId"), it.getString("groupName"), it.getString("kind"), it.getString("expiresAt"))
        }

    suspend fun acceptInvite(code: String): ConnectedGroup = runtime.managed.membershipChange { lease ->
        group(request(lease, "POST", "/v1/invites/${segment(code)}/accept", JSONObject()).getJSONObject("group"))
    }

    suspend fun leaveGroup(id: String) = runtime.managed.leave(id) { lease ->
        request(lease, "POST", "/v1/groups/${segment(id)}/leave", JSONObject())
        Unit
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val lease = runtime.identity.lease()
        return request(lease, method, path, body)
    }

    private suspend fun request(lease: ConnectedLease, method: String, path: String, body: JSONObject? = null): JSONObject =
        JSONObject(runtime.transport.request(lease, method, path, body?.toString())).also { runtime.identity.requireCurrent(lease) }

    private fun group(row: JSONObject) = ConnectedGroup(
        row.getString("id"), row.getString("name"), row.getString("kind"),
        row.getString("ownerId"), row.getString("role"), row.getInt("memberCount"),
    )

    private fun segment(value: String) = URLEncoder.encode(value, "UTF-8")


}

internal open class ConnectedFailure(message: String) : Exception(message)
