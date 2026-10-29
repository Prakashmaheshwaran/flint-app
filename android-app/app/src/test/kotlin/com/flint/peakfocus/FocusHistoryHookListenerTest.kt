package com.flint.peakfocus

import org.junit.Assert.*
import org.junit.Test

class FocusHistoryHookListenerTest {
    @Suppress("DEPRECATION")
    @Test fun compatibilityHooksOnlyForwardGenuineSessionOpens() {
        val opens = mutableListOf<Pair<String?, Long>>()
        val listener = FocusHistoryHookListener { id, at -> opens += id to at }
        listener.onOpenIntercepted("session-a", 100)
        listener.onBreakGranted("session-a", 200, 100)
        listener.onBreakGranted("session-a", "grant-a", 200, 100)
        listener.onBreakEnded("session-a", "grant-a", com.flint.peakfocus.blocking.overlay.BlockEventHooks.BreakEndReason.EXPIRED, 200)
        for (id in listOf(null, "ordinary-rule")) {
            listener.onOpenIntercepted(id, 100); listener.onBreakGranted(id, 200, 100)
        }
        listener.onBreakGranted("session-a", 100, 100)
        listener.onEmergencyPassUsed("session-a", 100) // no global operation data: omit
        listener.onEmergencyPassCommitted("p1-operation", setOf("session-a"), 200, 100)
        assertEquals(listOf("session-a" to 100L), opens)
    }
}
