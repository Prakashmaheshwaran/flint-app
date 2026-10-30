package com.flint.peakfocus.feature.blockscreen

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class GrantStatusTest {
    @Test fun PC_28_PC_35_pendingQuotaExplainsProtectionAndOffersNoCreditOrOrdinaryExit() {
        for (level in BreakLevel.entries) {
            val content = blockScreenContent(state(level, GrantStatus.RESETTING).copy(openCreditsRemaining = 3))
            assertEquals(BreakAffordance.QuotaPending, content.breakAffordance)
            assertEquals("Access credits are updating. This app stays protected.", content.reasonLine)
        }
    }
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
    @org.junit.Test fun PC_28_PC_30_positiveCreditsOfferExplicitTimedAccessAtEveryTierIncludingHardcore() {
        for (level in BreakLevel.entries) {
            val ready = state(level, GrantStatus.READY).copy(openCreditsRemaining = 2, openCreditDurationMillis = 300_000)
            assertEquals(BreakAffordance.UseCredit(2, 300_000), blockScreenContent(ready).breakAffordance)
            assertEquals(BreakAffordance.Unavailable, blockScreenContent(ready.copy(grantStatus = GrantStatus.UNAVAILABLE)).breakAffordance)
            assertEquals(BreakAffordance.Saving, blockScreenContent(ready.copy(grantStatus = GrantStatus.SAVING)).breakAffordance)
        }
    }

    @org.junit.Test fun PC_32_knownOverlapNamesTheContributorAndOffersNoCreditOrFreeBreak() {
        val ready = state(BreakLevel.EASY, GrantStatus.READY).copy(openCreditsRemaining = 3,
            openCreditDurationMillis = 120_000, openCreditBlockingContributors = listOf("Focus"))
        assertEquals(BreakAffordance.CreditBlocked(listOf("Focus")), blockScreenContent(ready).breakAffordance)
        org.junit.Assert.assertTrue(blockScreenContent(ready).reasonLine.contains("Also blocked by Focus."))
    }

    @Test fun PC_83_limitSelectionOnlyOffersDeletionWhileRuleSelectionCanDisable() {
        val choices = listOf(RecoveryChoice("rule:work", "Work"), RecoveryChoice("open:app", "Open limit", canDisable = false))
        org.junit.Assert.assertTrue(canDisableRecoverySelection(choices, setOf("rule:work")))
        org.junit.Assert.assertFalse(canDisableRecoverySelection(choices, setOf("open:app")))
        org.junit.Assert.assertFalse(canDisableRecoverySelection(choices, setOf("rule:work", "open:app")))
        org.junit.Assert.assertFalse(canDisableRecoverySelection(choices, emptySet()))
    }

    @Test fun PC_32_otherContributorNamesDoNotChangeTheLatestEndHeadlineOrCountdown() {
        val ready = state(BreakLevel.EASY, GrantStatus.READY).copy(remainingMillis = 42_000, openCreditsRemaining = 2,
            openCreditBlockingContributors = listOf("Work", "Time limit"))
        val baseline = blockScreenContent(ready.copy(openCreditBlockingContributors = emptyList()))
        val actual = blockScreenContent(ready)
        assertEquals(baseline.headline, actual.headline)
        assertEquals(baseline.countdownLabel, actual.countdownLabel)
        org.junit.Assert.assertTrue(actual.reasonLine.contains("Also blocked by Work, Time limit."))
    }

    @org.junit.Test fun PC_28_PC_35_exhaustionKeepsTierExceptionsAndShortCreditShowsItsActualDuration() {
        val ready = state(BreakLevel.HARDER, GrantStatus.READY).copy(openCreditsRemaining = 1, openCreditDurationMillis = 120_000)
        assertEquals(BreakAffordance.UseCredit(1, 120_000), blockScreenContent(ready).breakAffordance)
        assertEquals(BreakAffordance.HoldToRequest(BREAK_HOLD_MILLIS), blockScreenContent(ready.copy(openCreditsRemaining = 0)).breakAffordance)
    }
}
