package com.flint.peakfocus.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyFloorTest {

    private val self = "com.flint.peakfocus"

    @Test fun `dialer, emergency, settings and permission surfaces are on the static floor`() {
        for (pkg in listOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.cellbroadcastreceiver",
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.android.systemui",
            "android",
        )) {
            assertTrue(pkg, SafetyFloor.isProtected(pkg, self))
        }
    }

    @Test fun `Flint itself and blank packages are protected`() {
        assertTrue(SafetyFloor.isProtected(self, self))
        assertTrue(SafetyFloor.isProtected("", self))
        assertTrue(SafetyFloor.isProtected("   ", self))
    }

    @Test fun `runtime surfaces extend the floor`() {
        val runtime = setOf("com.truecaller", "com.teslacoilsw.launcher")
        assertTrue(SafetyFloor.isProtected("com.truecaller", self, runtime))
        assertTrue(SafetyFloor.isProtected("com.teslacoilsw.launcher", self, runtime))
        assertFalse(SafetyFloor.isProtected("com.truecaller", self))
    }

    @Test fun `ordinary apps are not protected`() {
        assertFalse(SafetyFloor.isProtected("com.instagram.android", self))
        assertFalse(SafetyFloor.isProtected("com.android.chrome", self, setOf("com.custom.launcher")))
    }

    @Test fun `withoutProtected keeps order and drops floor packages`() {
        val kept = SafetyFloor.withoutProtected(
            listOf("com.instagram.android", "com.android.dialer", self, "com.custom.home", "com.reddit.frontpage"),
            self,
            setOf("com.custom.home"),
        )
        assertEquals(listOf("com.instagram.android", "com.reddit.frontpage"), kept.toList())
    }
}
