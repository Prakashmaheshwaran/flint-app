package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TimeLimitDomainReadinessTest {
    private val now = AccessClockStamp(1_704_067_200_000, 1_000, "boot")
    private val times = listOf(TimeLimit("time-app", 30))
    private val ready = StorageState.Ready(times)
    private val fault = StorageState.Unavailable(StorageFailure.CORRUPT)
    private fun budget(quality: TrustedTimeLimitBudget.Measurement, left: Long? = 0L) =
        TrustedTimeLimitBudget("time-app", 30, TimeLimitDayAccounting.initial(now, "UTC"), left, quality)

    @Test fun accountingFaultDoesNotReclassifyAnUnrelatedTargetAsTimeLimited() {
        assertTrue(TimeLimitDomainReadiness.permitsNewAccess("open-app", ready, fault, null, true, now))
        assertFalse(TimeLimitDomainReadiness.permitsNewAccess("time-app", ready, fault,
            budget(TrustedTimeLimitBudget.Measurement.OBSERVED), true, now))
    }

    @Test fun productionRequiresMeasuredCurrentPeriodBeforeNewAccessCanOmitOrCoverTime() {
        assertFalse(TimeLimitDomainReadiness.permitsNewAccess("time-app", ready, ready, null, true, now))
        for (quality in TrustedTimeLimitBudget.Measurement.entries) {
            val expected = quality == TrustedTimeLimitBudget.Measurement.OBSERVED || quality == TrustedTimeLimitBudget.Measurement.NOT_NEEDED
            assertEquals(quality.name, expected,
                TimeLimitDomainReadiness.permitsNewAccess("time-app", ready, ready, budget(quality), true, now))
        }
        assertFalse(TimeLimitDomainReadiness.permitsNewAccess("time-app", ready, ready,
            budget(TrustedTimeLimitBudget.Measurement.OBSERVED, null), true, now))
        val expired = now.copy(wallEpochMs = now.wallEpochMs + 86_400_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 86_400_000)
        assertFalse(TimeLimitDomainReadiness.permitsNewAccess("time-app", ready, ready,
            budget(TrustedTimeLimitBudget.Measurement.OBSERVED), true, expired))
    }

    @Test fun aParentFaultOrUnfinishedTimeLoadCannotBorrowReadyMetadata() {
        assertEquals(fault, TimeLimitDomainReadiness.metadata(fault, fault))
        assertEquals(fault, TimeLimitDomainReadiness.metadata(fault, StorageState.Loading))
        assertEquals(StorageState.Loading, TimeLimitDomainReadiness.metadata(StorageState.Loading, null))
    }
}
