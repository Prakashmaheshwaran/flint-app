package com.flint.peakfocus

import org.junit.Assert.*
import org.junit.Test

class FocusHistoryHookListenerTest {
    @Test fun fakeHooksForwardOnlySessionOpensAndAcceptedOrdinaryBreaks() {
        val opens = mutableListOf<Pair<String?, Long>>()
        val breaks = mutableListOf<Triple<String?, Long, Long>>()
        val listener = FocusHistoryHookListener({ id, at -> opens += id to at }, { id, until, at -> breaks += Triple(id, until, at) })
        listener.onOpenIntercepted("session-a", 100)
        listener.onBreakGranted("session-a", 200, 100)
        for (id in listOf(null, "ordinary-rule")) {
            listener.onOpenIntercepted(id, 100); listener.onBreakGranted(id, 200, 100)
        }
        listener.onBreakGranted("session-a", 100, 100)
        listener.onEmergencyPassUsed("session-a", 100) // no global operation data: omit
        assertEquals(listOf("session-a" to 100L), opens)
        assertEquals(listOf(Triple("session-a", 200L, 100L)), breaks)
    }
}
