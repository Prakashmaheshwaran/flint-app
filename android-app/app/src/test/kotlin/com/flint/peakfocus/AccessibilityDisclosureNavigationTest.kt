package com.flint.peakfocus

import com.flint.peakfocus.feature.onboarding.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityDisclosureNavigationTest {
    @Test
    fun `Today accessibility setup discloses without opening system Settings`() {
        val navigation = AccessibilityDisclosureNavigation()
        navigation.setup(SetupStep.ENABLE_ACCESSIBILITY) { error("Settings bypassed disclosure") }
        assertTrue(navigation.showDisclosure)
    }

    @Test
    fun `You request uses same gate and decline opens no Settings`() {
        val navigation = AccessibilityDisclosureNavigation()
        var launches = 0
        navigation.request()
        assertTrue(navigation.showDisclosure)
        navigation.decline()
        navigation.accept { launches++ }
        assertFalse(navigation.showDisclosure)
        assertEquals(0, launches)
    }

    @Test
    fun `accept opens Settings once and a repeat enable requires disclosure again`() {
        val navigation = AccessibilityDisclosureNavigation()
        var launches = 0
        navigation.accept { launches++ }
        assertEquals(0, launches)
        navigation.request()
        navigation.accept { launches++ }
        navigation.accept { launches++ }
        assertEquals(1, launches)
        assertFalse(navigation.showDisclosure)
        navigation.setup(SetupStep.ENABLE_ACCESSIBILITY) { error("Unexpected direct handoff") }
        assertTrue(navigation.showDisclosure)
        assertEquals(1, launches)
        navigation.accept { launches++ }
        assertEquals(2, launches)
    }

    @Test
    fun `decline can be followed by a fresh disclosure request`() {
        val navigation = AccessibilityDisclosureNavigation()
        navigation.request()
        navigation.decline()
        navigation.request()
        assertTrue(navigation.showDisclosure)
    }

    @Test
    fun `restoring a pending disclosure still requires affirmative acceptance`() {
        val navigation = AccessibilityDisclosureNavigation.StateSaver.restore(true)!!
        assertTrue(navigation.showDisclosure)
        navigation.decline()
        navigation.accept { error("Restoration must not grant consent") }
        val dismissed = AccessibilityDisclosureNavigation.StateSaver.restore(false)!!
        dismissed.accept { error("Dismissed disclosure must not grant consent") }
    }

    @Test
    fun `other setup grants keep their direct explicit handoff`() {
        SetupStep.entries.filter { it != SetupStep.ENABLE_ACCESSIBILITY }.forEach { step ->
            val navigation = AccessibilityDisclosureNavigation()
            val launches = mutableListOf<SetupStep>()
            navigation.setup(step, launches::add)
            assertEquals(listOf(step), launches)
            assertFalse(navigation.showDisclosure)
        }
    }
}
