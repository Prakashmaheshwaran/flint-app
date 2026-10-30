package com.flint.peakfocus.core.common

import org.junit.Assert.*
import org.junit.Test

class RuntimeSafetySurfacesTest {
    @Test fun PC_48_PC_49_PC_53_missingRoleIdentityIsUnresolvedRatherThanAnEmptySuccess() {
        for (missing in listOf(null, "", " ", "\t\n")) {
            assertNull(RuntimeSafetySurfaces.resolvedIdentity(missing))
        }
    }

    @Test fun PC_49_customRoleIdentityIsRetainedExactly() {
        for (pkg in listOf("com.custom.dialer", "com.custom.home", "com.vendor.uninstaller")) {
            assertEquals(setOf(pkg), RuntimeSafetySurfaces.resolvedIdentity(pkg))
        }
    }

    @Test fun PC_49_candidateDiscoveryKeepsPhoneAndHomeSeparateFromOrdinaryLauncherProof() {
        val snapshot = RuntimeSafetySurfaces.candidateSources(setOf("home"), setOf("dialer"),
            visibleLaunchers = { error("no fallback needed") }, classify = { error("no fallback needed") })
        assertEquals(setOf("home"), snapshot[RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME])
        assertEquals(setOf("dialer"), snapshot[RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING])
        assertTrue(snapshot[RuntimeSafetySurfaces.SOURCE_ORDINARY_LAUNCHERS]!!.isEmpty())
    }

    @Test fun PC_48_PC_53_partialDiscoveryClassifiesOnlyProvenOrdinaryLaunchers() {
        val snapshot = RuntimeSafetySurfaces.candidateSources(null, setOf("dialer"),
            visibleLaunchers = { setOf("ordinary", "home", "unknown") },
            classify = { when (it) { "ordinary" -> false; "home" -> true; else -> null } })
        assertNull(snapshot[RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME])
        assertEquals(setOf("ordinary"), snapshot[RuntimeSafetySurfaces.SOURCE_ORDINARY_LAUNCHERS])
    }

    @Test fun PC_48_PC_53_totalPackageManagerFailureCannotInventAnOrdinaryLauncherIdentity() {
        val snapshot = RuntimeSafetySurfaces.candidateSources(null, null,
            visibleLaunchers = { null }, classify = { error("there is no proven launcher") })
        assertNull(snapshot[RuntimeSafetySurfaces.SOURCE_CANDIDATE_HOME])
        assertNull(snapshot[RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING])
        assertTrue(snapshot[RuntimeSafetySurfaces.SOURCE_ORDINARY_LAUNCHERS]!!.isEmpty())
    }

    @Test fun PC_49_noTelephonyResolvesCallingAsAbsentWithoutReadingUnavailableServices() {
        assertEquals(emptySet<String>(), RuntimeSafetySurfaces.resolvedCallingIdentity(false,
            role = { error("no TelecomManager required") }, effective = { error("no fallback required") }))
    }

    @Test fun PC_49_unsetDefaultUsesTheEffectiveDialerButLookupFailuresRemainUnresolved() {
        assertEquals(setOf("effective.dialer"), RuntimeSafetySurfaces.resolvedCallingIdentity(true,
            role = { null }, effective = { setOf("effective.dialer") }))
        assertEquals(setOf("chosen.dialer"), RuntimeSafetySurfaces.resolvedCallingIdentity(true,
            role = { "chosen.dialer" }, effective = { error("explicit role wins") }))
        assertNull(RuntimeSafetySurfaces.resolvedCallingIdentity(true, role = { null }, effective = { null }))
        assertNull(RuntimeSafetySurfaces.resolvedCallingIdentity(null, role = { "untrusted" }, effective = { null }))
        assertEquals(setOf("fallback"), RuntimeSafetySurfaces.resolvedCallingIdentity(true,
            role = { error("binder died") }, effective = { setOf("fallback") }))
        assertEquals(emptySet<String>(), RuntimeSafetySurfaces.resolvedCallingIdentity(true,
            role = { null }, effective = { emptySet() }))
    }
}
