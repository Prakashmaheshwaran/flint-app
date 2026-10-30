package com.flint.peakfocus.blocking.overlay

import android.content.Context
import com.flint.peakfocus.blocking.engine.OpenCreditPolicy
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.blocking.engine.TimeLimitDayState
import com.flint.peakfocus.core.data.UsageQuery
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.datastore.TimeLimitMeasurementContext
import com.flint.peakfocus.core.datastore.TimeLimitUsageObservation
import com.flint.peakfocus.core.model.AccessClockStamp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** A committed period and measurement result, shared by both detection paths. */
data class TrustedTimeLimitBudget(
    val packageName: String,
    val dailyMinutes: Int,
    val day: TimeLimitDayState?,
    /** Null means positive quota is unmeasured, never known unused or invented exhaustion. */
    val remainingMs: Long?,
    val measurement: Measurement,
) {
    enum class Measurement { OBSERVED, NOT_NEEDED, ACCESS_MISSING, QUERY_FAILED, HISTORY_UNKNOWN, STORAGE_UNAVAILABLE }
    val spent: Boolean get() = remainingMs == 0L
    fun resetRemainingMs(now: AccessClockStamp): Long? = day?.let {
        OpenCreditPolicy().remainingToResetMs(it.reset, now)
    }
}

/**
 * Queries happen outside the serialized accounting transaction. Only its committed result
 * can spend quota; a delayed ticket cannot impose its old-period verdict on a new period.
 * Construct/use off Main. No raw wall midnight or device zone is quota authority here.
 */
class TrustedTimeLimitReader internal constructor(
    private val contextFor: suspend (String, AccessClockStamp) -> TimeLimitMeasurementContext,
    private val record: suspend (TimeLimitMeasurementContext, Long?, AccessClockStamp) -> TimeLimitUsageObservation,
    private val query: (String, Long, Long) -> Long?,
    private val clock: () -> AccessClockStamp,
    private val publish: (TrustedTimeLimitBudget) -> Unit = {},
) {
    private val lastGood = ConcurrentHashMap<String, TimeLimitDayState>()

    suspend fun read(packageName: String, dailyMinutes: Int): TrustedTimeLimitBudget {
        require(dailyMinutes >= 0)
        try {
            val ticket = contextFor(packageName, clock())
            ticket.committedDay?.let { lastGood[packageName] = it }
            val total = if (dailyMinutes > 0) try {
                query(packageName, ticket.windowStartEpochMs, ticket.observedAt.wallEpochMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { Long.MAX_VALUE } else null
            val committed = record(ticket, total, clock())
            lastGood[packageName] = committed.state
            val quality = when {
                dailyMinutes == 0 -> TrustedTimeLimitBudget.Measurement.NOT_NEEDED
                total == null -> TrustedTimeLimitBudget.Measurement.ACCESS_MISSING
                total == Long.MAX_VALUE || !committed.sampleAccepted -> TrustedTimeLimitBudget.Measurement.QUERY_FAILED
                committed.state.accountingUnknownUntilReset -> TrustedTimeLimitBudget.Measurement.HISTORY_UNKNOWN
                else -> TrustedTimeLimitBudget.Measurement.OBSERVED
            }
            return result(packageName, dailyMinutes, committed.state, quality)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unavailable observation cannot refund previously evidenced exhaustion.
            // No old positive remaining value is presented as a successful measurement.
            return result(packageName, dailyMinutes, lastGood[packageName], TrustedTimeLimitBudget.Measurement.STORAGE_UNAVAILABLE)
        }
    }

    private fun result(pkg: String, minutes: Int, day: TimeLimitDayState?, quality: TrustedTimeLimitBudget.Measurement): TrustedTimeLimitBudget {
        val measured = day?.let { TimeLimitDayAccounting.remainingMs(it, minutes) }
        val remaining = when {
            minutes == 0 -> 0L
            measured == 0L -> 0L
            quality == TrustedTimeLimitBudget.Measurement.OBSERVED -> measured
            else -> null
        }
        return TrustedTimeLimitBudget(pkg, minutes, day, remaining, quality).also(publish)
    }

    companion object {
        fun create(context: Context): TrustedTimeLimitReader {
            val app = context.applicationContext
            val store = FlintPreferences.timeLimitUsage(app)
            return TrustedTimeLimitReader(
                contextFor = { pkg, now -> store.measurementContext(pkg, now) },
                record = store::record,
                query = { pkg, start, now -> UsageQuery.foregroundMillisForWindowIfGranted(app, pkg, start, now) },
                clock = ProtectionClock::now,
                publish = TimeLimitMeasurementHealth.publisher::record,
            )
        }
    }
}
