package com.flint.peakfocus.feature.blockscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery opened from the app-open PIN screen never renders commitment names (PC-50). */
class RecoveryRedactionTest {

    private val named = RecoveryScreenState(
        loading = false,
        unavailable = false,
        available = true,
        choices = listOf(RecoveryChoice("rule-1", "Instagram limit"), RecoveryChoice("rule-2", "Deep work")),
        endConfirmation = listOf("Instagram limit"),
    )

    @Test fun `a redacted screen shows no per-commitment choices even if names are passed`() {
        assertTrue(visibleRecoveryChoices(named.copy(redacted = true)).isEmpty())
        assertNull(visibleEndConfirmation(named.copy(redacted = true)))
    }

    @Test fun `an unredacted screen keeps its choices and end confirmation`() {
        assertEquals(named.choices, visibleRecoveryChoices(named))
        assertEquals(listOf("Instagram limit"), visibleEndConfirmation(named))
    }
}
