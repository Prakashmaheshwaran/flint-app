package com.flint.peakfocus.feature.blockscreen

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantStatusTest {
    private fun state(level: BreakLevel, status: GrantStatus) = BlockScreenState(
        "com.app", reason = BlockScreenReason.OPEN_LIMIT, breakLevel = level, grantStatus = status,
    )
    @Test fun loadingSavingAndUnavailableNeverOfferATierExit() {
        for (level in BreakLevel.entries) {
            assertEquals(BreakAffordance.Saving, blockScreenContent(state(level, GrantStatus.LOADING)).breakAffordance)
            assertEquals(BreakAffordance.Saving, blockScreenContent(state(level, GrantStatus.SAVING)).breakAffordance)
            assertEquals(BreakAffordance.Unavailable, blockScreenContent(state(level, GrantStatus.UNAVAILABLE)).breakAffordance)
        }
    }
    @Test fun failedWritesKeepTheExistingTierSpecificRetryControl() {
        for (level in BreakLevel.entries) {
            assertEquals(blockScreenContent(state(level, GrantStatus.READY)).breakAffordance,
                blockScreenContent(state(level, GrantStatus.FAILED)).breakAffordance)
        }
    }
    @Test fun anUnpaidNormalOpenOffersACounterRetryAcrossAllTiers() {
        for (level in BreakLevel.entries) {
            assertEquals(BreakAffordance.RetryOpen, blockScreenContent(state(level, GrantStatus.RETRY_OPEN)).breakAffordance)
        }
    }
}
