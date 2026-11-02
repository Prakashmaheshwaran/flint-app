package com.flint.peakfocus.core.history

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FocusAlarmCoordinatorTest {
    private class Registry : FocusAlarmRegistry {
        var persisted = emptyMap<String, Long>()
        var writes = 0
        var failOnWrite: Int? = null
        override suspend fun scheduled() = persisted
        override suspend fun setScheduled(alarms: Map<String, Long>) {
            writes++
            if (writes == failOnWrite) throw IOException("injected registry failure")
            persisted = alarms.toMap()
        }
    }
    @Test fun interruptedRegistrationRetainsIdentityForStartupCancellation() = runTest {
        val registry = Registry(); val platform = mutableMapOf<String, Long>()
        val interrupted = FocusAlarmCoordinator(registry, { id, at ->
            platform[id] = at; throw IOException("failure after Android registration")
        }, { platform.remove(it) })
        try { interrupted.reconcile(mapOf("session-a" to 100L), 0); fail("Expected failure") } catch (_: IOException) { }
        assertEquals(mapOf("session-a" to 100L), registry.persisted)
        assertEquals(registry.persisted, platform)
        // The session ended early while the process was absent. A new adapter knows its ID.
        FocusAlarmCoordinator(registry, { id, at -> platform[id] = at }, { platform.remove(it) })
            .reconcile(emptyMap(), 1)
        assertTrue(platform.isEmpty()); assertTrue(registry.persisted.isEmpty())
    }
    @Test fun cancellationThenFailedPersistenceForcesUnchangedDeadlineToRegisterAgain() = runTest {
        val registry = Registry(); val platform = mutableMapOf<String, Long>()
        val coordinator = FocusAlarmCoordinator(registry, { id, at -> platform[id] = at }, { platform.remove(it) })
        val plan = mapOf("session-a" to 100L)
        coordinator.reconcile(plan, 0)
        registry.failOnWrite = registry.writes + 2 // intent succeeds, final registry write fails
        try { coordinator.reconcile(emptyMap(), 1); fail("Expected failure") } catch (_: IOException) { }
        assertTrue(platform.isEmpty()); assertEquals(plan, registry.persisted)
        coordinator.reconcile(plan, 2)
        assertEquals(plan, platform); assertEquals(plan, registry.persisted)
    }
}
