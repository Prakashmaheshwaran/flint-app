package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageState
import com.flint.peakfocus.core.model.TimeLimit

/** A Time-owned accounting fault cannot revoke verified policy or another domain's rights. */
internal object TimeLimitDomainReadiness {
    fun metadata(
        time: StorageState<List<TimeLimit>>,
        shared: StorageState<ProtectionSnapshot>?,
    ): StorageState<List<TimeLimit>> =
        if (time is StorageState.Unavailable && shared is StorageState.Ready) StorageState.Ready(shared.value.times)
        else time

    /** New access must not omit a possibly active Time contributor on this target. */
    fun permitsNewAccess(
        packageName: String,
        metadata: StorageState<List<TimeLimit>>,
        time: StorageState<List<TimeLimit>>,
        budget: TrustedTimeLimitBudget?,
        requireMeasurement: Boolean,
        now: AccessClockStamp,
    ): Boolean {
        val committed = (metadata as? StorageState.Ready)?.value?.filter { it.packageName == packageName }.orEmpty()
        val configured = committed.isNotEmpty()
        if (!configured && budget == null) return true
        if (time !is StorageState.Ready) return false
        if (budget == null) return !requireMeasurement
        // R2p2-N01: the last observation can predate a strengthening edit. Evidence measured
        // against a looser cap than the committed one cannot prove the committed limit unspent;
        // wait for the detector to re-measure. Equal or stricter (legacy minimum) evidence can.
        if (committed.any { budget.dailyMinutes > it.dailyMinutes }) return false
        return budget.remainingMs != null && budget.resetRemainingMs(now)?.let { it > 0 } == true &&
            (budget.measurement == TrustedTimeLimitBudget.Measurement.OBSERVED ||
                budget.measurement == TrustedTimeLimitBudget.Measurement.NOT_NEEDED)
    }
}
