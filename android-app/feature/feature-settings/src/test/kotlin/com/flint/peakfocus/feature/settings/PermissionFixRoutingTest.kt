package com.flint.peakfocus.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionFixRoutingTest {
    @Test
    fun `every Accessibility fix requests disclosure and never a direct handoff`() {
        var disclosures = 0
        repeat(2) {
            routePermissionFix(
                PermissionKind.ACCESSIBILITY,
                discloseAccessibility = { disclosures++ },
                openOtherSettings = { error("Accessibility bypassed the gate") },
            )
        }
        assertEquals(2, disclosures)
    }

    @Test
    fun `other permission fixes preserve the requested Settings destination`() {
        PermissionKind.entries.filter { it != PermissionKind.ACCESSIBILITY }.forEach { kind ->
            val launches = mutableListOf<PermissionKind>()
            routePermissionFix(kind, { error("Unrelated grant entered disclosure") }, launches::add)
            assertEquals(listOf(kind), launches)
        }
    }
}
