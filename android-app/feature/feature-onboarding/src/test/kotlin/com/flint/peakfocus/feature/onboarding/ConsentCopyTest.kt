package com.flint.peakfocus.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentCopyTest {
    @Test
    fun `disclosure names the mechanism and data read`() {
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("Accessibility service", ignoreCase = true))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("which app is in the foreground"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("web address"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("known browser address bars"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("Not all browsers or pages"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("screen class names"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("visible Settings text"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("content descriptions"))
    }

    @Test
    fun `disclosure limits use and sharing`() {
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("only to enforce the blocks you set up"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("stays on your device", ignoreCase = true))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("never sent off your device"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("shared, or sold"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("no account", ignoreCase = true))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("no analytics", ignoreCase = true))
    }

    @Test
    fun `consent is explicit optional and describes the actual revocation path`() {
        assertTrue(ConsentCopy.HEADLINE.contains("enabling Accessibility"))
        assertFalse(ConsentCopy.HEADLINE.contains("Before Flint can block apps"))
        assertTrue(ConsentCopy.ACCEPT_LABEL.contains("Accept"))
        assertTrue(ConsentCopy.DECLINE_LABEL.isNotBlank())
        assertFalse(ConsentCopy.DECLINE_LABEL.contains("accept", ignoreCase = true))
        assertTrue(ConsentCopy.NEXT_STEP.contains("first end or disable active Hardcore rules"))
        assertTrue(ConsentCopy.NEXT_STEP.contains("hold-to-end"))
        assertTrue(ConsentCopy.NEXT_STEP.contains("backup path"))
        assertFalse(ConsentCopy.NEXT_STEP.contains("turn it off any time", ignoreCase = true))
    }

    @Test
    fun `guard disclosure names broad coverage fail closed behavior and limits`() {
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("any enabled Hardcore rule is active"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("even when removing another app"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("force stop, clear data"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("Accessibility disable"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("If screen text is unavailable"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("even for another app"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("no break or Emergency Pass action"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("only while Accessibility is running"))
        assertTrue(ConsentCopy.GUARD_BEHAVIOR.contains("may miss screens"))
    }
}
