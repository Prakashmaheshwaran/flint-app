package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.ref.WeakReference

class TargetFloorIdentitiesTest {
    private val self = "com.flint.peakfocus"

    @After fun clear() = TargetFloorIdentities.register(null as WeakReference<SafetyFloorGate>?)

    private fun gate() = SafetyFloorGate(
        selfPackage = self,
        resolveRuntime = { mapOf(RuntimeSafetySurfaces.SOURCE_HOME to setOf("com.example.home")) },
        nowElapsedMs = { 1_000L },
        requiredRuntimeSources = setOf(RuntimeSafetySurfaces.SOURCE_HOME),
    )

    @Test fun `no live gate means no runtime identity`() {
        TargetFloorIdentities.register(null as WeakReference<SafetyFloorGate>?)
        assertNull(TargetFloorIdentities.read("com.example.home"))
    }

    @Test fun `a constructed gate registers itself and reads delegate to it`() {
        val live = gate()
        live.protectsFresh("com.example.home", maxAgeMs = 0) // inline resolve, as the coordinator does
        assertEquals(FloorIdentity.Kind.RUNTIME_HOME, TargetFloorIdentities.read("com.example.home")?.kind)
        assertEquals(live.readIdentity("com.example.home"), TargetFloorIdentities.read("com.example.home"))
        assertNull(TargetFloorIdentities.read("com.example.ordinary"))
    }

    @Test fun `a collected gate is ignored`() {
        gate()
        val collected = WeakReference(gate()).also { it.clear() }
        TargetFloorIdentities.register(collected)
        assertNull(TargetFloorIdentities.read("com.example.home"))
        assertNull(TargetFloorIdentities.read(self))
    }

    @Test fun `static fallback names only Flint and the static floor, never runtime roles`() {
        assertEquals(FloorIdentity.Kind.SELF, TargetFloorIdentities.staticIdentity(self, self)?.kind)
        assertEquals(FloorIdentity.Kind.STATIC_SYSTEM, TargetFloorIdentities.staticIdentity("com.android.settings", self)?.kind)
        assertNull(TargetFloorIdentities.staticIdentity("com.example.home", self))
        assertNull(TargetFloorIdentities.staticIdentity("com.example.dialer", self))
    }

    @Test fun `the newest gate wins and clearing it never falls back to an older gate`() {
        val older = gate().also { it.protectsFresh("com.example.home", maxAgeMs = 0) }
        val newer = SafetyFloorGate(self, { mapOf(RuntimeSafetySurfaces.SOURCE_HOME to setOf("com.example.other")) },
            { 1_000L }, requiredRuntimeSources = setOf(RuntimeSafetySurfaces.SOURCE_HOME))
        newer.protectsFresh("com.example.other", maxAgeMs = 0)
        assertNull(TargetFloorIdentities.read("com.example.home"))
        assertEquals(FloorIdentity.Kind.RUNTIME_HOME, TargetFloorIdentities.read("com.example.other")?.kind)
        TargetFloorIdentities.register(WeakReference(newer).also { it.clear() })
        assertNull(TargetFloorIdentities.read("com.example.home"))
        assertEquals(FloorIdentity.Kind.RUNTIME_HOME, older.readIdentity("com.example.home")?.kind)
    }
}
