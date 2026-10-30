package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.AccessClockStamp

internal val TIME_BOOTSTRAP_CONTEXT = stringPreferencesKey("time_limit_bootstrap_context_v1")

/** First-open inputs only. This snapshot grants no editor, pass, credit or budget authority. */
internal data class TimeLimitBootstrapContext(val zoneId: String, val capturedAt: AccessClockStamp,
    val legacyTargets: Set<String>)

/**
 * Runs before ANY production preference read/edit. Preserve the legacy source and current
 * editor bytes; serialized marker-v3 preparation later consumes these immutable inputs.
 * No nested DataStore read or partial saved-zone/editor migration occurs in this callback.
 */
internal class TimeLimitBootstrapMigration(
    private val clock: () -> AccessClockStamp,
    private val deviceZone: () -> String,
    private val legacyTargets: () -> Set<String>,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        if (readTimeLimitBootstrapContext(currentData) != null) return false
        timeUsageRequire(StrictPrefsCodec.long(currentData, TIME_USAGE_SCHEMA) == null &&
            StrictPrefsCodec.long(currentData, TIME_LIMIT_ZONE_SCHEMA) == null)
        return true
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!shouldMigrate(currentData)) return currentData
        val context = TimeLimitBootstrapContext(deviceZone(), clock(), legacyTargets().toSet())
        return currentData.toMutablePreferences().apply {
            this[TIME_BOOTSTRAP_CONTEXT] = encodeTimeLimitBootstrapContext(context)
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit // SharedPreferences remains the stricter legacy fallback.
}

internal fun encodeTimeLimitBootstrapContext(context: TimeLimitBootstrapContext): String {
    timeUsageValid { TimeLimitDayAccounting.initial(context.capturedAt, context.zoneId) }
    val targets = TimeLimitUsageCodec.targetRegistry(context.legacyTargets)
    val at = context.capturedAt
    return "B|1\nC|${wireAtom(context.zoneId)}|${at.wallEpochMs}|${at.elapsedRealtimeMs}|${wireAtom(at.bootSessionId)}\n" +
        "P|${wireAtom(targets)}"
}

internal fun readTimeLimitBootstrapContext(prefs: Preferences): TimeLimitBootstrapContext? {
    val raw = StrictPrefsCodec.string(prefs, TIME_BOOTSTRAP_CONTEXT) ?: return null
    val rows = wireRows(raw, "B")
    val context = rows.singleOrNull { it[0] == "C" } ?: wireCorrupt()
    val targets = rows.singleOrNull { it[0] == "P" } ?: wireCorrupt()
    timeUsageRequire(rows.size == 2 && context.size == 5 && targets.size == 2)
    return TimeLimitBootstrapContext(readWireAtom(context[1]),
        AccessClockStamp(wireLong(context[2]), wireLong(context[3]), readWireAtom(context[4])),
        TimeLimitUsageCodec.readTargetRegistry(readWireAtom(targets[1]))).also { encodeTimeLimitBootstrapContext(it) }
}
