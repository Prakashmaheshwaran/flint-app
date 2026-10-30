package com.flint.peakfocus.core.datastore

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageFailure
import java.io.IOException
import java.util.TimeZone

internal val TIME_BOOTSTRAP_CONTEXT = stringPreferencesKey("time_limit_bootstrap_context_v1")

/** First-open inputs only. This snapshot grants no editor, pass, credit or budget authority. */
internal data class TimeLimitBootstrapContext(val zoneId: String, val capturedAt: AccessClockStamp,
    val legacyTargets: Set<String>)

internal fun timeLimitBootstrapMigrations(context: Context): List<DataMigration<Preferences>> = listOf(
    TimeLimitBootstrapMigration(ProtectionClock::now, { TimeZone.getDefault().id }, {
        captureLegacyTimeLimitTargets(context.applicationContext.getSharedPreferences("flint_limits", Context.MODE_PRIVATE).all)
    }),
)

internal fun captureLegacyTimeLimitTargets(raw: Map<String, *>): Set<String> {
    timeUsageRequire(raw.values.all { it is Int })
    return raw.filterValues { (it as Int) >= 0 }.keys.toSet().also { TimeLimitUsageCodec.targetRegistry(it) }
}

internal sealed interface TimeLimitBootstrapCapture {
    data class Ready(val context: TimeLimitBootstrapContext) : TimeLimitBootstrapCapture
    data class Unavailable(val reason: StorageFailure) : TimeLimitBootstrapCapture
}

/**
 * Runs before ANY production preference read/edit. Preserve the legacy source and current
 * editor bytes; serialized marker-v3 preparation later consumes these immutable inputs.
 * No nested DataStore read or partial saved-zone/editor migration occurs in this callback.
 * Malformed input publishes capture-unavailable, so the shared store remains readable.
 */
internal class TimeLimitBootstrapMigration(
    private val clock: () -> AccessClockStamp,
    private val deviceZone: () -> String,
    private val legacyTargets: () -> Set<String>,
) : DataMigration<Preferences> {
    // Presence alone gates this migration. Even a malformed/future own marker is preserved
    // and becomes Time-only unavailable when consumed, never a shared DataStore read error.
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.asMap().keys.none { it.name == TIME_BOOTSTRAP_CONTEXT.name }

    override suspend fun migrate(currentData: Preferences): Preferences {
        if (!shouldMigrate(currentData)) return currentData
        val payload = try {
            timeUsageRequire(currentData.asMap().keys.none { it.name in
                setOf(TIME_USAGE_SCHEMA.name, TIME_LIMIT_ZONE_SCHEMA.name) })
            encodeTimeLimitBootstrapContext(TimeLimitBootstrapContext(deviceZone(), clock(), legacyTargets().toSet()))
        } catch (failure: Exception) {
            val reason = when (failure) {
                is StorageUnavailableException -> failure.reason
                is IOException -> StorageFailure.IO
                else -> StorageFailure.CORRUPT
            }
            "B|1\nU|${reason.name}"
        }
        return currentData.toMutablePreferences().apply {
            this[TIME_BOOTSTRAP_CONTEXT] = payload
        }.toPreferences()
    }

    override suspend fun cleanUp() = Unit // SharedPreferences remains the stricter legacy fallback.
}

internal fun encodeTimeLimitBootstrapContext(context: TimeLimitBootstrapContext): String {
    timeUsageValid { TimeLimitDayAccounting.initial(context.capturedAt, context.zoneId) }
    val targets = TimeLimitUsageCodec.targetRegistry(context.legacyTargets)
    val at = context.capturedAt
    return ("B|1\nC|${wireAtom(context.zoneId)}|${at.wallEpochMs}|${at.elapsedRealtimeMs}|${wireAtom(at.bootSessionId)}\n" +
        targets.lineSequence().drop(1).joinToString("\n")).also { timeUsageRequire(it.length <= 4_194_304) }
}

internal fun readTimeLimitBootstrapCapture(prefs: Preferences): TimeLimitBootstrapCapture? = try {
    val raw = StrictPrefsCodec.string(prefs, TIME_BOOTSTRAP_CONTEXT)
    if (raw == null) null else {
        val rows = wireRows(raw, "B")
        val failure = rows.singleOrNull()?.takeIf { it[0] == "U" }
        if (failure != null) {
            timeUsageRequire(failure.size == 2)
            TimeLimitBootstrapCapture.Unavailable(StorageFailure.entries.singleOrNull { it.name == failure[1] } ?: wireCorrupt())
        } else {
            val context = rows.singleOrNull { it[0] == "C" } ?: wireCorrupt()
            timeUsageRequire(context.size == 5)
            val registry = "K|1\n" + rows.filter { it[0] != "C" }.joinToString("\n") { it.joinToString("|") }
            TimeLimitBootstrapCapture.Ready(TimeLimitBootstrapContext(readWireAtom(context[1]),
                AccessClockStamp(wireLong(context[2]), wireLong(context[3]), readWireAtom(context[4])),
                TimeLimitUsageCodec.readTargetRegistry(registry)).also { encodeTimeLimitBootstrapContext(it) })
        }
    }
} catch (failure: StorageUnavailableException) {
    TimeLimitBootstrapCapture.Unavailable(failure.reason)
} catch (_: Exception) {
    TimeLimitBootstrapCapture.Unavailable(StorageFailure.CORRUPT)
}

/** Used only by Time readers, so failed capture never makes rules/focus/credit reads fail. */
internal fun readTimeLimitBootstrapContext(prefs: Preferences): TimeLimitBootstrapContext? = when (val capture = readTimeLimitBootstrapCapture(prefs)) {
    null -> null // Constructor-injected test stores may omit the production factory migration.
    is TimeLimitBootstrapCapture.Ready -> capture.context
    is TimeLimitBootstrapCapture.Unavailable -> throw StorageUnavailableException(capture.reason)
}
