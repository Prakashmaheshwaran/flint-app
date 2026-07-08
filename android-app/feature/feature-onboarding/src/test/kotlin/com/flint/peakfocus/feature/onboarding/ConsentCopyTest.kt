package com.flint.peakfocus.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The consent screen is a Play compliance gate (AccessibilityService policy §4.3 / ADR-007),
 * and until now nothing but a code comment ("do not weaken this copy") protected it. These
 * tests pin the policy-required elements so a copy edit that drops one fails the build.
 */
class ConsentCopyTest {

    // --- §4.3: name the mechanism and describe the data accessed ---

    @Test
    fun `disclosure names the accessibility service`() {
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("Accessibility service", ignoreCase = true))
    }

    @Test
    fun `disclosure says exactly what is read - foreground app and browser address`() {
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("which app is in the foreground"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("web address"))
    }

    // --- §4.3: describe how the data is used and shared ---

    @Test
    fun `disclosure limits use to enforcing the user's own blocks`() {
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("only to enforce the blocks you set up"))
    }

    @Test
    fun `disclosure states the data never leaves the device`() {
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("stays on your device", ignoreCase = true))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("never sent off your device"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("shared, or sold"))
    }

    @Test
    fun `disclosure restates the no-account no-analytics promise - ADR-002`() {
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("no account", ignoreCase = true))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("no analytics", ignoreCase = true))
    }

    // --- Affirmative consent: explicit accept, real decline, revocability ---

    @Test
    fun `accept is explicit and decline is a real choice`() {
        assertTrue(ConsentCopy.ACCEPT_LABEL.contains("Accept", ignoreCase = false))
        assertTrue(ConsentCopy.DECLINE_LABEL.isNotBlank())
        // The decline label must not guilt or mislead — it's a plain deferral.
        assertFalse(ConsentCopy.DECLINE_LABEL.contains("accept", ignoreCase = true))
    }

    @Test
    fun `user is told the grant is revocable`() {
        assertTrue(ConsentCopy.NEXT_STEP.contains("turn it off any time", ignoreCase = true))
    }

    @Test
    fun `copy stays consistent with the settings screen's disclosure language`() {
        // feature-settings states the same three facts in its accessibility row (its own
        // BlockingHealthUiTest holds that end); the shared phrases below are the contract.
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("which app is in the foreground"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("only to enforce the blocks you set up"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("never", ignoreCase = true))
    }
}
