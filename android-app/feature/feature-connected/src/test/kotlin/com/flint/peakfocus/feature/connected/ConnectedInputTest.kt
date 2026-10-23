package com.flint.peakfocus.feature.connected

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectedInputTest {
    @Test fun emailTrimsOuterWhitespace() {
        assertEquals("person@example.test", ConnectedInput.email(" person@example.test "))
    }

    @Test fun malformedEmailRejected() {
        listOf("", "a", "no-at-sign", "a@@b", "a b@c", "a".repeat(255) + "@b").forEach {
            assertThrows(IllegalArgumentException::class.java) { ConnectedInput.email(it) }
        }
    }

    @Test fun newPasswordsHaveMinimumWithoutBreakingLegacySignIn() {
        assertThrows(IllegalArgumentException::class.java) { ConnectedInput.password("short", true) }
        assertEquals("short", ConnectedInput.password("short", false))
        assertEquals("some password", ConnectedInput.password("some password", true))
    }

    @Test fun passwordsAreNeverTrimmed() {
        assertEquals(" padded password ", ConnectedInput.password(" padded password ", true))
    }

    @Test fun passwordLimitsAreBounded() {
        listOf("", "a".repeat(129)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ConnectedInput.password(it, false) }
        }
    }

    @Test fun namesTrimAndRejectControls() {
        assertEquals("Focus family", ConnectedInput.name("  Focus family  "))
        listOf(" ", "bad\nname", "x".repeat(81)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ConnectedInput.name(it) }
        }
    }

    @Test fun groupKindsAreExplicit() {
        assertEquals("school", ConnectedInput.kind("school"))
        assertEquals("family", ConnectedInput.kind("family"))
        assertThrows(IllegalArgumentException::class.java) { ConnectedInput.kind("admin") }
    }

    @Test fun inviteCodesAreOpaqueUrlSafeValues() {
        val code = "abcd0123_-".repeat(4)
        assertEquals(code, ConnectedInput.invite(" $code "))
        listOf("short", "../".repeat(15), "a".repeat(129), "é".repeat(40)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ConnectedInput.invite(it) }
        }
    }

    @Test fun localConfigCannotPointAtOpalOrARealCloudProject() {
        val config = ConnectedConfig.localEmulator
        assertEquals("demo-flint", config.projectId)
        assertEquals("http://10.0.2.2:8787", config.apiBase)
        assertEquals("10.0.2.2", config.authHost)
        assertEquals(9099, config.authPort)
    }
}
