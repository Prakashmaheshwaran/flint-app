package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.AccessClockStamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Current-boot observations only; quota/exhaustion authority stays in the durable day store. */
internal class TimeLimitMeasurementPublisher {
    private val values = MutableStateFlow<Map<String, TrustedTimeLimitBudget>>(emptyMap())
    val state = values.asStateFlow()

    @Synchronized fun record(budget: TrustedTimeLimitBudget) {
        // Bounded by the persisted accounting registry. A full cache never invents healthy
        // coverage for an unrecorded target; its absence remains measurement-unavailable.
        if (budget.packageName !in values.value && values.value.size >= 2_000) return
        values.value = values.value + (budget.packageName to budget)
    }

    fun unavailable(budgets: Map<String, Int>, now: AccessClockStamp): Boolean = budgets.any { (pkg, minutes) ->
        if (minutes <= 0) false else values.value[pkg]?.let { value ->
            value.measurement != TrustedTimeLimitBudget.Measurement.OBSERVED ||
                value.day?.accountingUnknownUntilReset != false ||
                value.resetRemainingMs(now)?.let { it <= 0L } != false
        } != false
    }

    fun storageUnavailable(budgets: Map<String, Int>): Boolean = budgets.keys.any {
        values.value[it]?.measurement == TrustedTimeLimitBudget.Measurement.STORAGE_UNAVAILABLE
    }
}

/** Both coordinators consume the same latest observation, so a policy tick cannot erase it. */
internal object TimeLimitMeasurementHealth { val publisher = TimeLimitMeasurementPublisher() }
