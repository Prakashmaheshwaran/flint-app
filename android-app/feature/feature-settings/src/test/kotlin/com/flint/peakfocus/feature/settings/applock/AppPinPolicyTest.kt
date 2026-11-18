package com.flint.peakfocus.feature.settings.applock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** New-PIN shape rules, matching iOS `FlintPIN.isValidNewPIN`. */
class AppPinPolicyTest {

    @Test fun `accepts four to six ASCII digits`() {
        listOf("0000", "1234", "9999", "123456", "000000", "137900").forEach {
            assertTrue("$it should be valid", AppPinPolicy.isValidNewPin(it))
        }
    }

    @Test fun `rejects anything that is not an exact 4-6 ASCII digit string`() {
        listOf(
            "",
            "1",
            "123",
            "1234567",
            " 1234",
            "1234 ",
            "12 34",
            "١٢٣٤", // Arabic-Indic digits
            "１２３４", // fullwidth digits
            "12a4",
            "12٣4",
            "-1234",
            "+1234",
            "1234\n",
        ).forEach {
            assertFalse("$it should be invalid", AppPinPolicy.isValidNewPin(it))
        }
    }

    @Test fun `verification accepts longer entries than a new pin may be`() {
        assertTrue(AppPinPolicy.MAX_ENTRY_LENGTH >= AppPinPolicy.MAX_LENGTH)
        assertTrue(AppPinPolicy.MIN_LENGTH < AppPinPolicy.MAX_LENGTH)
    }
}
