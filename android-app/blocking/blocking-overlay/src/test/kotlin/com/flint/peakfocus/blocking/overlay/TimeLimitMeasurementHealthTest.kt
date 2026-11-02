package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.AccessClockStamp
import org.junit.Assert.*
import org.junit.Test

class TimeLimitMeasurementHealthTest {
    private val now = AccessClockStamp(1_704_067_200_000L, 1_000, "boot")
    private val day = TimeLimitDayAccounting.initial(now, "UTC")
    private fun budget(quality: TrustedTimeLimitBudget.Measurement, remaining: Long? = null) =
        TrustedTimeLimitBudget("app", 1, day, remaining, quality)

    @Test fun positiveUnmeasuredBudgetsDegradeWhileZeroNeedsNoUsageMeasurement() {
        val health = TimeLimitMeasurementPublisher()
        assertTrue(health.unavailable(mapOf("app" to 1), now))
        assertFalse(health.unavailable(mapOf("app" to 0), now))
        for (quality in listOf(TrustedTimeLimitBudget.Measurement.ACCESS_MISSING,
            TrustedTimeLimitBudget.Measurement.QUERY_FAILED, TrustedTimeLimitBudget.Measurement.HISTORY_UNKNOWN)) {
            health.record(budget(quality))
            assertTrue(health.unavailable(mapOf("app" to 1), now))
            assertFalse(health.storageUnavailable(mapOf("app" to 1)))
        }
        health.record(budget(TrustedTimeLimitBudget.Measurement.OBSERVED, 60_000))
        assertFalse(health.unavailable(mapOf("app" to 1), now))
        val end = now.copy(wallEpochMs = now.wallEpochMs + 86_400_000, elapsedRealtimeMs = now.elapsedRealtimeMs + 86_400_000)
        assertTrue(health.unavailable(mapOf("app" to 1), end))
    }

    @Test fun failureCannotClearExhaustionAndOnlySuccessfulObservationRecoversMeasurementHealth() {
        val health = TimeLimitMeasurementPublisher()
        health.record(budget(TrustedTimeLimitBudget.Measurement.QUERY_FAILED, 0))
        assertTrue(health.state.value.getValue("app").spent)
        assertTrue(health.unavailable(mapOf("app" to 1), now))
        health.record(budget(TrustedTimeLimitBudget.Measurement.STORAGE_UNAVAILABLE, 0))
        assertTrue(health.storageUnavailable(mapOf("app" to 1)))
        assertFalse(health.storageUnavailable(emptyMap())) // Removed configuration is not a fault.
        health.record(budget(TrustedTimeLimitBudget.Measurement.OBSERVED, 0))
        assertFalse(health.unavailable(mapOf("app" to 1), now))
        assertFalse(health.storageUnavailable(mapOf("app" to 1)))
    }
}
