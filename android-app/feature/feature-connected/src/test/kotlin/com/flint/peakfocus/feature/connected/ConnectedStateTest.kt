package com.flint.peakfocus.feature.connected

import org.junit.Assert.*
import org.junit.Test

class ConnectedStateTest {
    private val group = ConnectedGroup("group", "Family", "family", "alice", "owner", 1)
    private val populated = ConnectedUiState(
        uid = "alice", email = "alice@example.test",
        profile = ConnectedProfile("alice", "alice@example.test", "Alice"),
        groups = listOf(group), selectedGroup = group,
        members = listOf(ConnectedMember("alice", "Alice", "owner")),
        invitePreview = InvitePreview("code", "group", "Family", "family", "future"),
        createdInvite = CreatedInvite("secret", "group", "future"),
        loaded = true, membersLoaded = true, busy = true,
    )

    @Test fun signOutPurgesTheEntireAccountSnapshot() {
        assertEquals(ConnectedUiState(busy = true), populated.forIdentity(null, null))
    }

    @Test fun switchingAccountsPurgesMembershipAndInviteCredentials() {
        assertEquals(
            ConnectedUiState(uid = "bob", email = "bob@example.test", busy = true),
            populated.forIdentity("bob", "bob@example.test"),
        )
    }

    @Test fun sameIdentityKeepsItsLoadedData() {
        assertEquals(populated, populated.forIdentity("alice", "alice@example.test"))
    }

    @Test fun initialListsAreNotPresentedAsSuccessfullyLoadedEmptyResults() {
        assertFalse(ConnectedUiState().loaded)
        assertFalse(ConnectedUiState().membersLoaded)
    }

    @Test fun conflictFailuresHaveDistinctActionableDescriptions() {
        val codes = listOf("invite_used", "owner_cannot_leave", "group_limit", "invite_limit", "member_limit", "already_member")
        assertEquals(codes.size, codes.map { connectedHttpError(409, it) }.distinct().size)
        assertTrue(connectedHttpError(409, "member_limit").contains("member limit"))
        assertTrue(connectedHttpError(409, "group_limit").contains("group limit"))
    }

    @Test fun unknownResponseNeverEchoesUntrustedServiceContent() {
        assertFalse(connectedHttpError(503, "bearer-secret").contains("bearer-secret"))
        assertFalse(connectedHttpError(409, "bearer-secret").contains("bearer-secret"))
        assertTrue(connectedHttpError(401, null).contains("Sign in"))
    }
}
