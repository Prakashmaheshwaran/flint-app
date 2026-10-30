package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.util.TimeZone

internal val TIME_USAGE_SCHEMA = longPreferencesKey("time_limit_usage_schema_v1")
internal val TIME_USAGE_STATE = stringPreferencesKey("time_limit_usage_state_v1")
internal val TIME_USAGE_ARCHIVE = stringPreferencesKey("time_limit_usage_archive_v1")
internal val TIME_USAGE_TARGETS = stringPreferencesKey("time_limit_usage_targets_v1")

data class TimeLimitUsageLedger(val migrationZoneId: String, val targets: Map<String, TimeLimitDayState>)

/** Immutable sampling ticket; OS queries run outside the serialized preference edit. */
data class TimeLimitMeasurementContext(val packageName: String, val periodId: Long, val zoneId: String,
    val windowStartEpochMs: Long, val observedAt: AccessClockStamp)

data class TimeLimitUsageObservation(val state: TimeLimitDayState, val sampleAccepted: Boolean)

/** Shared durable accounting for both detectors. It does not authorize a rule or an early edit. */
class DataStoreTimeLimitUsageStore(
    private val dataStore: DataStore<Preferences>,
    private val clockAtCommit: (() -> AccessClockStamp)? = null,
    private val deviceZone: () -> String = { TimeZone.getDefault().id },
) {
    val state = dataStore.data.storageStates(::readTimeLimitUsage)

    /** Existing rows never adopt a new device zone or forget usage after off/delete/recreate. */
    suspend fun measurementContext(packageName: String, now: AccessClockStamp,
        savedZoneForNewTarget: String? = null): TimeLimitMeasurementContext {
        var result: TimeLimitMeasurementContext? = null
        dataStore.edit { prefs ->
            val stamp = clockAtCommit?.invoke() ?: now
            var ledger = prepareTimeLimitSavedZones(prefs, stamp, deviceZone())
            timeUsageRequire(packageName.isNotBlank() && packageName.length <= OpenCreditLedgerPolicy.MAX_ATOM)
            val previous = ledger.targets[packageName]
            val templateZone = prefs.editorTimes().singleOrNull { it.packageName == packageName }?.zoneId
            val row = timeUsageValid {
                previous?.let { TimeLimitDayAccounting.reconcile(it, stamp, templateZone ?: it.zoneId).state }
                    ?: TimeLimitDayAccounting.initial(stamp, savedZoneForNewTarget ?:
                        templateZone ?: ledger.migrationZoneId)
            }
            ledger = ledger.copy(targets = ledger.targets + (packageName to row))
            prefs.putTimeLimitUsage(ledger)
            val start = DailyUsageTimeline.startOfDay(stamp.wallEpochMs, OpenCreditLedgerPolicy.quotaZone(row.zoneId))
            result = TimeLimitMeasurementContext(packageName, row.periodId, row.zoneId, start, stamp)
        }
        return checkNotNull(result)
    }

    /** Delayed reports cannot charge another period; unavailable measurements never become zero. */
    suspend fun record(context: TimeLimitMeasurementContext, totalMs: Long?, now: AccessClockStamp): TimeLimitUsageObservation {
        var result: TimeLimitUsageObservation? = null
        dataStore.edit { prefs ->
            val stamp = clockAtCommit?.invoke() ?: now
            val ledger = readTimeLimitUsage(prefs)
            val current = ledger.targets[context.packageName] ?: wireCorrupt()
            val desiredZone = prefs.editorTimes().singleOrNull { it.packageName == context.packageName }?.zoneId ?: current.zoneId
            // A legitimate template-zone change can retire a sampling ticket. It is stale
            // evidence, not corruption, and must neither charge nor disable the new period.
            val sample = totalMs?.takeIf { current.zoneId == context.zoneId && it != Long.MAX_VALUE }?.let {
                TimeLimitUsageSample(context.windowStartEpochMs, it, context.observedAt)
            }
            val mutation = timeUsageValid { TimeLimitDayAccounting.record(current, context.periodId, sample, stamp, desiredZone) }
            prefs.putTimeLimitUsage(ledger.copy(targets = ledger.targets + (context.packageName to mutation.state)))
            result = TimeLimitUsageObservation(mutation.state, mutation.sampleAccepted)
        }
        return checkNotNull(result)
    }
}

/** One-time saved-zone capture archives the untouched legacy limits/default-tier bytes in the same edit. */
internal fun prepareTimeLimitUsage(prefs: MutablePreferences, now: AccessClockStamp, zone: String): TimeLimitUsageLedger {
    if (StrictPrefsCodec.long(prefs, TIME_USAGE_SCHEMA) != null) return readTimeLimitUsage(prefs)
    timeUsageRequire(StrictPrefsCodec.string(prefs, TIME_USAGE_STATE) == null &&
        StrictPrefsCodec.string(prefs, TIME_USAGE_ARCHIVE) == null && StrictPrefsCodec.string(prefs, TIME_USAGE_TARGETS) == null)
    val raw = StrictPrefsCodec.string(prefs, PrefsKeys.TIME_LIMITS)
    val tier = StrictPrefsCodec.string(prefs, PrefsKeys.DEFAULT_BREAK_LEVEL)
    val limits = StrictPrefsCodec.timeLimits(raw.orEmpty(), StrictPrefsCodec.level(tier))
    val ledger = timeUsageValid { TimeLimitUsageLedger(zone, limits.associate {
        it.packageName to TimeLimitDayAccounting.initial(now, it.zoneId ?: zone)
    }) }
    val archive = TimeLimitUsageCodec.archive(raw, tier)
    val encoded = TimeLimitUsageCodec.encode(ledger)
    prefs[TIME_USAGE_ARCHIVE] = archive
    prefs[TIME_USAGE_TARGETS] = TimeLimitUsageCodec.targetRegistry(ledger.targets.keys)
    prefs[TIME_USAGE_STATE] = encoded
    prefs[TIME_USAGE_SCHEMA] = 1L
    return ledger
}

internal fun readTimeLimitUsage(prefs: Preferences): TimeLimitUsageLedger {
    readTimeLimitZoneVersion(prefs)
    val version = StrictPrefsCodec.long(prefs, TIME_USAGE_SCHEMA) ?: wireCorrupt()
    if (version > 1) wireFuture()
    timeUsageRequire(version == 1L)
    val original = TimeLimitUsageCodec.readArchive(StrictPrefsCodec.string(prefs, TIME_USAGE_ARCHIVE) ?: wireCorrupt())
    val ledger = TimeLimitUsageCodec.decode(StrictPrefsCodec.string(prefs, TIME_USAGE_STATE) ?: wireCorrupt())
    val registered = TimeLimitUsageCodec.readTargetRegistry(StrictPrefsCodec.string(prefs, TIME_USAGE_TARGETS) ?: wireCorrupt())
    timeUsageRequire(ledger.targets.keys == registered)
    val imported = StrictPrefsCodec.timeLimits(original.first.orEmpty(), StrictPrefsCodec.level(original.second))
    timeUsageRequire(imported.all { it.packageName in ledger.targets })
    timeUsageRequire(prefs.editorTimes().all { it.zoneId != null })
    return ledger
}

internal fun MutablePreferences.putTimeLimitUsage(ledger: TimeLimitUsageLedger) {
    val previous = readTimeLimitUsage(this) // Missing registered rows never initialize themselves again.
    timeUsageRequire(ledger.targets.keys.containsAll(previous.targets.keys))
    this[TIME_USAGE_STATE] = TimeLimitUsageCodec.encode(ledger)
    this[TIME_USAGE_TARGETS] = TimeLimitUsageCodec.targetRegistry(ledger.targets.keys)
}

internal fun timeUsageRequire(value: Boolean) { if (!value) wireCorrupt() }
internal inline fun <T> timeUsageValid(action: () -> T): T = try { action() }
catch (_: IllegalArgumentException) { wireCorrupt() }
catch (_: ArithmeticException) { wireCorrupt() }
catch (_: java.time.DateTimeException) { wireCorrupt() }
