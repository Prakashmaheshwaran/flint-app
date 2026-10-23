package com.flint.peakfocus.feature.connected

/** This milestone deliberately has no production endpoint or borrowed provider keys. */
class ConnectedConfig private constructor(
    val projectId: String,
    val authHost: String,
    val authPort: Int,
    val apiBase: String,
) {
    companion object {
        val localEmulator = ConnectedConfig(
            projectId = "demo-flint",
            authHost = "10.0.2.2",
            authPort = 9099,
            apiBase = "http://10.0.2.2:8787",
        )
    }
}

data class ConnectedProfile(val id: String, val email: String, val displayName: String)

/** Remote membership, deliberately distinct from core-model's saved app selection AppGroup. */
data class ConnectedGroup(
    val id: String,
    val name: String,
    val kind: String,
    val ownerId: String,
    val role: String,
    val memberCount: Int,
)

data class ConnectedMember(val userId: String, val displayName: String, val role: String)
data class InvitePreview(
    val code: String,
    val groupId: String,
    val groupName: String,
    val kind: String,
    val expiresAt: String,
)
data class CreatedInvite(val code: String, val groupId: String, val expiresAt: String)

data class ConnectedUiState(
    val uid: String? = null,
    val email: String? = null,
    val profile: ConnectedProfile? = null,
    val groups: List<ConnectedGroup> = emptyList(),
    val selectedGroup: ConnectedGroup? = null,
    val members: List<ConnectedMember> = emptyList(),
    val invitePreview: InvitePreview? = null,
    val createdInvite: CreatedInvite? = null,
    val loaded: Boolean = false,
    val membersLoaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

/** Account-bound data must disappear together when identity changes or a session is lost. */
internal fun ConnectedUiState.forIdentity(uid: String?, email: String?): ConnectedUiState =
    if (this.uid == uid) copy(email = email)
    else ConnectedUiState(uid = uid, email = email, busy = busy)

internal fun connectedHttpError(status: Int, code: String?): String = when (status) {
    401 -> "Your session expired. Sign in again."
    403 -> "Your account does not have access to this action."
    404 -> "This group or invite is no longer available."
    409 -> when (code) {
        "invite_used" -> "This invite has already been used. Ask for a new code."
        "owner_cannot_leave" -> "The owner cannot leave this group."
        "group_limit" -> "Your account has reached its group limit."
        "invite_limit" -> "This group has reached its pending invite limit."
        "member_limit" -> "This group has reached its member limit."
        "already_member" -> "You are already a member of this group."
        else -> "This action conflicts with the latest group state. Refresh and try again."
    }
    410 -> "This invite has expired. Ask for a new code."
    422 -> "Check the entered details and try again."
    429 -> "Too many requests. Try again shortly."
    else -> "The service is unavailable. Please try again."
}

internal object ConnectedInput {
    fun email(value: String): String = value.trim().also {
        require(it.length in 3..254 && it.count { c -> c == '@' } == 1 &&
            it.none(Char::isWhitespace)) { "Enter a valid email address." }
    }

    fun password(value: String, creating: Boolean): String = value.also {
        require(it.length in (if (creating) 8 else 1)..128) {
            if (creating) "Use a password with 8–128 characters." else "Enter your password."
        }
    }

    fun name(value: String): String = value.trim().also {
        require(it.length in 1..80 && it.none(Char::isISOControl)) { "Use a name with 1–80 characters." }
    }

    fun kind(value: String): String = value.also {
        require(it == "family" || it == "school") { "Choose a family or school group." }
    }

    fun invite(value: String): String = value.trim().also {
        require(it.length in 32..128 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' }) {
            "Enter the complete invite code."
        }
    }
}
