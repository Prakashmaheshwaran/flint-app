package com.flint.peakfocus.e2e

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupAnrPolicyTest {
    @Test fun onlyIdentifiedSystemOrLauncherAnrsCanBeDismissed() {
        fun action(title: String, wait: Boolean = true, close: Boolean = true) =
            SetupAnrPolicy.action(title, "Pixel Launcher", wait, close)
        assertEquals(SetupAnrPolicy.Action.WAIT, action("System UI isn't responding"))
        assertEquals(SetupAnrPolicy.Action.WAIT, action("\u2068System UI\u2069 isn’t responding"))
        assertEquals(SetupAnrPolicy.Action.CLOSE, action("Pixel Launcher isn't responding", wait = false))
        assertEquals(SetupAnrPolicy.Action.FAIL, action("Flint isn't responding"))
        assertEquals(SetupAnrPolicy.Action.FAIL, action("com.flint.peakfocus isn't responding"))
        assertEquals(SetupAnrPolicy.Action.FAIL, action("Unknown isn't responding"))
        assertEquals(SetupAnrPolicy.Action.FAIL, action("System UI keeps stopping"))
        assertEquals(SetupAnrPolicy.Action.FAIL, action("System UI isn't responding", wait = false, close = false))
    }
}
