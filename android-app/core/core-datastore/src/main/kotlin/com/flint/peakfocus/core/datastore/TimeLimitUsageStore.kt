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

data class TimeLimitUsageLedger(val migrationZoneId: String, val targets: Map<String, TimeLimitDayState>,
    val foreground: TimeLimitForegroundSpan? = null)

/** One visit in one detector incarnation. A new process/visit cannot charge its missing gap. */
data class TimeLimitForegroundSpan(val packageName: String, val ownerId: String, val visitId: String,
    val periodId: Long, val startedAt: AccessClockStamp, val checkpoint: AccessClockStamp)

data class TimeLimitForegroundObservation(val ownerId: String, val previousVisitId: String?,
    val visitId: String?, val packageName: String?, val observedAt: AccessClockStamp, val closeOnly: Boolean = false)

/** Immutable sampling ticket; OS queries run outside the serialized preference edit. */
data class TimeLimitMeasurementContext(val packageName: String, val periodId: Long, val zoneId: String,
    val windowStartEpochMs: Long, val observedAt: AccessClockStamp,
    /** Acknowledged floor before the OS read; retained if the later sample commit fails. */
    val committedDay: TimeLimitDayState? = null)

data class TimeLimitUsageObservation(val state: TimeLimitDayState, val sampleAccepted: Boolean)

/** Shared durable accounting for both detectors. It does not authorize a rule or an early edit. */
class DataStoreTimeLimitUsageStore(
    private val dataStore: DataStore<Preferences>,
    private val clockAtCommit: (() -> AccessClockStamp)? = null,
    private val deviceZone: () -> String = { TimeZone.getDefault().id },
) {
    val state = dataStore.data.storageStates(::readTimeLimitUsage)

    /**
     * Same serialized durable domain as OS samples. Ownership is rechecked inside the edit;
     * a retired detector can close only its own span and cannot start another one. A missed
     * visit/process/boot or observation deadline begins a new span without charging its gap.
     */
    suspend fun observeForeground(observation: TimeLimitForegroundObservation, isCurrentOwner: () -> Boolean = { true }) {
        dataStore.edit { prefs ->
            if (!observation.closeOnly && !isCurrentOwner()) return@edit
            var ledger = prepareTimeLimitSavedZones(prefs, observation.observedAt, deviceZone())
            val previous = ledger.foreground
            if (observation.closeOnly && previous?.ownerId != observation.ownerId) return@edit
            val stamp = observation.observedAt
            if (previous?.ownerId == observation.ownerId && previous.checkpoint.bootSessionId == stamp.bootSessionId &&
                trustedElapsedMs(previous.checkpoint, stamp) == null) return@edit
            if (previous?.ownerId == observation.ownerId && previous.visitId == observation.previousVisitId && isCurrentOwner()) {
                val elapsed = trustedElapsedMs(previous.checkpoint, stamp)?.takeIf { it <= MAX_FOREGROUND_GAP_MS }
                val old = ledger.targets[previous.packageName] ?: wireCorrupt()
                var row = old
                if (elapsed != null && previous.periodId == old.periodId) {
                    // Stop the old period at its trusted boundary before charging any suffix.
                    val ahead = trustedElapsedMs(previous.checkpoint, old.reset.checkpoint)
                    val remainingAtCheckpoint = if (ahead == null) OpenCreditPolicy().remainingToResetMs(old.reset, previous.checkpoint)
                        else Math.addExact(old.reset.remainingToResetMs, ahead)
                    val beforeReset = minOf(elapsed, remainingAtCheckpoint)
                    row = TimeLimitDayAccounting.observe(row, beforeReset)
                    val desiredZone = prefs.editorTimes().singleOrNull { it.packageName == previous.packageName }?.zoneId ?: row.zoneId
                    val advanced = reconcileForegroundRow(row, stamp, desiredZone)
                    row = if (advanced.rolled) TimeLimitDayAccounting.observe(advanced.state, elapsed - beforeReset) else advanced.state
                } else if (elapsed == null) row = row.copy(accountingUnknownUntilReset = true)
                ledger = ledger.copy(targets = ledger.targets + (previous.packageName to row))
            }
            val target = observation.packageName.takeUnless { observation.closeOnly || !isCurrentOwner() }
            val next = if (target == null) null else {
                val visit = observation.visitId ?: wireCorrupt()
                val templateZone = prefs.editorTimes().singleOrNull { it.packageName == target }?.zoneId
                var row = ledger.targets[target]?.let { reconcileForegroundRow(it, stamp, templateZone ?: it.zoneId).state }
                    ?: TimeLimitDayAccounting.initial(stamp, templateZone ?: ledger.migrationZoneId)
                val continuous = previous?.takeIf { it.ownerId == observation.ownerId && it.visitId == visit &&
                    it.packageName == target && it.periodId == row.periodId &&
                    trustedElapsedMs(it.checkpoint, stamp)?.let { gap -> gap <= MAX_FOREGROUND_GAP_MS } == true }
                if (continuous == null) row = TimeLimitDayAccounting.alignObserved(row, 0)
                ledger = ledger.copy(targets = ledger.targets + (target to row))
                TimeLimitForegroundSpan(target, observation.ownerId, visit, row.periodId, continuous?.startedAt ?: stamp, stamp)
            }
            prefs.putTimeLimitUsage(ledger.copy(foreground = next))
        }
    }

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
            result = TimeLimitMeasurementContext(packageName, row.periodId, row.zoneId, start, stamp, row)
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
            val span = ledger.foreground?.takeIf { it.packageName == context.packageName && it.periodId == mutation.state.periodId }
            // The OS sample ends at the ticket timestamp. Only a continuous, durably observed
            // suffix after that instant may be added when aligning the independent floors.
            val suffix = span?.takeIf { trustedElapsedMs(it.startedAt, context.observedAt) != null }
                ?.let { trustedElapsedMs(context.observedAt, it.checkpoint) }
            val state = if (mutation.sampleAccepted && suffix != null)
                TimeLimitDayAccounting.alignObserved(mutation.state, suffix) else mutation.state
            prefs.putTimeLimitUsage(ledger.copy(targets = ledger.targets + (context.packageName to state)))
            result = TimeLimitUsageObservation(state, mutation.sampleAccepted)
        }
        return checkNotNull(result)
    }

    companion object { const val MAX_FOREGROUND_GAP_MS = 30_000L }
}

/** A queued event may precede an OS query's newer commit; it must never rewind the reset. */
private fun reconcileForegroundRow(row: TimeLimitDayState, stamp: AccessClockStamp, zone: String): TimeLimitDayResult =
    if (row.observedAt.bootSessionId == stamp.bootSessionId && row.observedAt.elapsedRealtimeMs > stamp.elapsedRealtimeMs)
        TimeLimitDayResult(row, false)
    else TimeLimitDayAccounting.reconcile(row, stamp, zone)

/** One-time saved-zone capture archives the untouched legacy limits/default-tier bytes in the same edit. */
internal fun prepareTimeLimitUsage(prefs: MutablePreferences, now: AccessClockStamp, zone: String,
    legacyTargets: Set<String> = emptySet()): TimeLimitUsageLedger {
    if (StrictPrefsCodec.long(prefs, TIME_USAGE_SCHEMA) != null) return readTimeLimitUsage(prefs)
    timeUsageRequire(StrictPrefsCodec.string(prefs, TIME_USAGE_STATE) == null &&
        StrictPrefsCodec.string(prefs, TIME_USAGE_ARCHIVE) == null && StrictPrefsCodec.string(prefs, TIME_USAGE_TARGETS) == null)
    val raw = StrictPrefsCodec.string(prefs, PrefsKeys.TIME_LIMITS)
    val tier = StrictPrefsCodec.string(prefs, PrefsKeys.DEFAULT_BREAK_LEVEL)
    val limits = StrictPrefsCodec.timeLimits(raw.orEmpty(), StrictPrefsCodec.level(tier))
    // Legacy SharedPreferences budgets remain a stricter fallback, not editor templates.
    // Capture their target periods in the same first migration so later first queries
    // cannot choose a travel zone or begin a new day after a clock jump.
    val ledger = timeUsageValid { TimeLimitUsageLedger(zone,
        legacyTargets.associateWith { TimeLimitDayAccounting.initial(now, zone) } + limits.associate {
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
    readTimeLimitBootstrapContext(prefs) // Capture failures affect only Time readers.
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

/** Only the Time read boundary owns these children; capture failure cannot break other stores. */
internal fun validateTimeLimitUsageForRead(prefs: Preferences) {
    readTimeLimitBootstrapContext(prefs)
    val editorVersion = StrictPrefsCodec.long(prefs, EDITOR_VERSION_KEY)
    if (editorVersion != null) readEditorState(prefs) // Includes future-version rejection.
    val ownNames = setOf(TIME_USAGE_SCHEMA.name, TIME_USAGE_STATE.name, TIME_USAGE_ARCHIVE.name,
        TIME_USAGE_TARGETS.name, TIME_LIMIT_ZONE_SCHEMA.name)
    if (editorVersion?.let { it >= 3L } == true || prefs.asMap().keys.any { it.name in ownNames })
        readTimeLimitUsage(prefs)
}

internal inline fun <T> timeUsageValid(action: () -> T): T = try { action() }
catch (_: IllegalArgumentException) { wireCorrupt() }
catch (_: ArithmeticException) { wireCorrupt() }
catch (_: java.time.DateTimeException) { wireCorrupt() }
