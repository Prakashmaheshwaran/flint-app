package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.OpenCreditLedgerPolicy
import com.flint.peakfocus.core.model.*

internal val TIME_LIMIT_ZONE_SCHEMA = longPreferencesKey("time_limit_saved_zone_schema_v1")

/** D5: validated migration preserves explicit zones/tiers and stamps legacy zones once at upgrade. */
internal fun prepareTimeLimitSavedZones(prefs: MutablePreferences, now: AccessClockStamp, deviceZone: String): TimeLimitUsageLedger {
    if (StrictPrefsCodec.long(prefs, TIME_LIMIT_ZONE_SCHEMA) != null) {
        readTimeLimitZoneVersion(prefs)
        return readTimeLimitUsage(prefs)
    }
    // Both markers and canonical records publish in one edit. An established missing marker
    // is never a reason to perform a second migration or capture a different travel zone.
    timeUsageRequire(StrictPrefsCodec.long(prefs, TIME_USAGE_SCHEMA) == null)
    val limits = prefs.editorTimes()
    val ledger = prepareTimeLimitUsage(prefs, now, deviceZone)
    val migrated = limits.map { limit ->
        limit.withSavedTimeLimitZone(previous = null, zoneForNewTarget = ledger.targets.getValue(limit.packageName).zoneId)
    }
    if (migrated != limits) prefs[PrefsKeys.TIME_LIMITS] = PrefsCodec.encodeTimeLimits(migrated)
    prefs[TIME_LIMIT_ZONE_SCHEMA] = 1L
    return ledger
}

internal fun readTimeLimitZoneVersion(prefs: Preferences) {
    val version = StrictPrefsCodec.long(prefs, TIME_LIMIT_ZONE_SCHEMA) ?: wireCorrupt()
    if (version > 1) wireFuture()
    timeUsageRequire(version == 1L)
}

/** Commit-time normalization for a new draft; a device-zone change never rewrites an existing zone. */
internal fun TimeLimit.withSavedTimeLimitZone(previous: TimeLimit?, zoneForNewTarget: String): TimeLimit =
    copy(zoneId = zoneId ?: previous?.zoneId ?: zoneForNewTarget).also {
        timeUsageValid { OpenCreditLedgerPolicy.quotaZone(checkNotNull(it.zoneId)) }
    }
