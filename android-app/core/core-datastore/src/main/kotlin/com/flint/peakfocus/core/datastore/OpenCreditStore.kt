package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.ExemptionKind

internal val OPEN_CREDIT_SCHEMA = longPreferencesKey("open_credit_schema_v1")
internal val OPEN_CREDIT_STATE = stringPreferencesKey("open_credit_state_v1")
internal val OPEN_CREDIT_COUNTS = stringPreferencesKey("open_credit_counts_v1")
internal val OPEN_CREDIT_ARCHIVE = stringPreferencesKey("open_credit_legacy_archive_v1")
internal val QUOTA_ZONE = stringPreferencesKey("quota_zone_v1")

/** Same-edit seam. These helpers never open a DataStore transaction or acknowledge a grant. */
internal fun readOpenCredits(prefs: Preferences): OpenCreditLedger {
    val version = StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) ?: wireCorrupt()
    if (version > 1) wireFuture()
    wireRequire(version == 1L)
    val zone = StrictPrefsCodec.string(prefs, QUOTA_ZONE) ?: wireCorrupt()
    OpenCreditCodec.readArchive(StrictPrefsCodec.string(prefs, OPEN_CREDIT_ARCHIVE) ?: wireCorrupt())
    return OpenCreditCodec.decode(StrictPrefsCodec.string(prefs, OPEN_CREDIT_STATE) ?: wireCorrupt(),
        StrictPrefsCodec.string(prefs, OPEN_CREDIT_COUNTS) ?: wireCorrupt()).also { wireRequire(it.quotaZone == zone) }
}

/** First-limit setup and legacy import share this key with Time Limits; travel never rewrites it. */
internal fun prepareQuotaZone(prefs: MutablePreferences, deviceZone: String): String {
    val stored = StrictPrefsCodec.string(prefs, QUOTA_ZONE)
    if (stored == null && StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) != null) wireCorrupt()
    return (stored ?: deviceZone).also { zone ->
        validOpenCredit { OpenCreditLedgerPolicy.quotaZone(zone) }
        if (stored == null) prefs[QUOTA_ZONE] = zone
    }
}

/** Validate/import/checkpoint inside the caller's existing flint_preferences edit. */
internal fun prepareOpenCredits(prefs: MutablePreferences, now: AccessClockStamp, quotaZoneIfAbsent: String): OpenCreditLedger =
    prepareOpenCreditPeriod(prefs, now, quotaZoneIfAbsent).next

/** Detailed variant lets the shared grant transaction order period-end/deferred-change events. */
internal fun prepareOpenCreditPeriod(prefs: MutablePreferences, now: AccessClockStamp, quotaZoneIfAbsent: String): OpenCreditReconciliation {
    if (StrictPrefsCodec.long(prefs, OPEN_CREDIT_SCHEMA) != null) {
        val result = validOpenCredit { OpenCreditLedgerPolicy.reconcile(readOpenCredits(prefs), now) }
        prefs.putOpenCredits(result.next)
        return result
    }
    wireRequire(listOf(OPEN_CREDIT_STATE, OPEN_CREDIT_COUNTS, OPEN_CREDIT_ARCHIVE).all {
        StrictPrefsCodec.string(prefs, it) == null
    })
    val zone = StrictPrefsCodec.string(prefs, QUOTA_ZONE) ?: quotaZoneIfAbsent
    val rawCounts = StrictPrefsCodec.string(prefs, PrefsKeys.OPEN_COUNTS)
    val day = StrictPrefsCodec.long(prefs, PrefsKeys.OPEN_COUNTS_DAY)
    val opens = StrictPrefsCodec.openLimits(StrictPrefsCodec.string(prefs, PrefsKeys.OPEN_LIMITS).orEmpty())
    val receipts = FocusLedgerCodec.decode(StrictPrefsCodec.string(prefs, PrefsKeys.FOCUS_LEDGER))
    // Only a complete, provably unused legacy Open domain gets the one-time optional-key exception.
    val legacyAccess = receipts.exemptions.values.any { it.kind == ExemptionKind.OPEN_LIMIT }
    val unused = rawCounts == null && day == null && opens.isEmpty() && !legacyAccess
    wireRequire(unused || (rawCounts != null && day != null))
    val counts = rawCounts?.let(StrictPrefsCodec::counts).orEmpty()
    val today = validOpenCredit { OpenCreditLedgerPolicy.epochDay(now.wallEpochMs, zone) }
    if (day != null) wireRequire(day in 0..today)
    // A previous-day label proves no elapsed midnight. Preserve every count until a bounded reset.
    val initial = validOpenCredit { OpenCreditLedgerPolicy.initial(now, zone, counts, day ?: today) }
        .copy(timeUncertain = day != null && day != today, accessEndedAfterRestart = legacyAccess)
    val archive = OpenCreditCodec.archive(rawCounts, day)
    // All validation/encoding happens before touching mutable preferences.
    OpenCreditCodec.encodeState(initial); OpenCreditCodec.encodeCounts(initial)
    prefs[OPEN_CREDIT_ARCHIVE] = archive
    prefs.putOpenCredits(initial)
    return OpenCreditReconciliation(initial, false, emptySet())
}

/** Write alongside the consuming receipt; a result is authoritative only after the outer edit returns. */
internal fun MutablePreferences.putOpenCredits(value: OpenCreditLedger) {
    val state = OpenCreditCodec.encodeState(value)
    val counts = OpenCreditCodec.encodeCounts(value)
    if (StrictPrefsCodec.long(this, OPEN_CREDIT_SCHEMA) != null) readOpenCredits(this)
    OpenCreditCodec.readArchive(StrictPrefsCodec.string(this, OPEN_CREDIT_ARCHIVE) ?: wireCorrupt())
    val storedZone = StrictPrefsCodec.string(this, QUOTA_ZONE)
    wireRequire(storedZone == null || storedZone == value.quotaZone)
    this[QUOTA_ZONE] = value.quotaZone
    this[OPEN_CREDIT_STATE] = state
    this[OPEN_CREDIT_COUNTS] = counts
    this[OPEN_CREDIT_SCHEMA] = 1L
}

internal inline fun <T> validOpenCredit(action: () -> T): T = try { action() }
catch (_: IllegalArgumentException) { wireCorrupt() }
catch (_: ArithmeticException) { wireCorrupt() }
catch (_: java.time.DateTimeException) { wireCorrupt() }

/** Canonical bounded records. Missing, duplicate or future material never decodes as zero usage. */
internal object OpenCreditCodec {
    fun encodeState(value: OpenCreditLedger): String {
        validOpenCredit { OpenCreditLedgerPolicy.requireValid(value) }
        val at = value.observedAt
        return "S|1\nM|${wireAtom(value.quotaZone)}|${value.periodId}|${value.reset.remainingToResetMs}|" +
            "${at.wallEpochMs}|${at.elapsedRealtimeMs}|${wireAtom(at.bootSessionId)}|${value.logicalCheckpoint.wallEpochMs}|" +
            "${value.timeUncertain}|${value.accessEndedAfterRestart}"
    }

    fun encodeCounts(value: OpenCreditLedger): String {
        validOpenCredit { OpenCreditLedgerPolicy.requireValid(value) }
        return buildList {
            add("O|1")
            value.counters.toSortedMap().forEach { (target, counter) ->
                add("C|${wireAtom(target)}|${counter.used}")
                counter.consumedOperationIds.sorted().forEach { add("I|${wireAtom(target)}|${wireAtom(it)}") }
                counter.previousPeriodOperationIds.sorted().forEach { add("P|${wireAtom(target)}|${wireAtom(it)}") }
            }
            value.active.toSortedMap().forEach { (target, access) ->
                val grant = access.grant; val at = grant.startedAt
                add("G|${wireAtom(target)}|${wireAtom(grant.operationId)}|${at.wallEpochMs}|${at.elapsedRealtimeMs}|" +
                    "${wireAtom(at.bootSessionId)}|${grant.durationMs}|${access.interval.remainingAtAnchorMs}|${access.interval.wallEndEpochMs}")
            }
        }.joinToString("\n").also { wireRequire(it.length <= 4_194_304) }
    }

    fun decode(state: String, counts: String): OpenCreditLedger {
        val meta = wireRows(state, "S").singleOrNull() ?: wireCorrupt()
        wireRequire(meta.size == 10 && meta[0] == "M")
        val at = AccessClockStamp(wireLong(meta[4]), wireLong(meta[5]), readWireAtom(meta[6]))
        val reset = OpenCreditPolicy.ResetClock(at, wireLong(meta[3]))
        val period = wireLong(meta[2])
        val used = linkedMapOf<String, Int>()
        val accepted = linkedMapOf<String, MutableSet<String>>()
        val previous = linkedMapOf<String, MutableSet<String>>()
        val active = linkedMapOf<String, OpenCreditAccess>()
        wireRows(counts, "O").forEach { f -> when (f[0]) {
            "C" -> {
                wireRequire(f.size == 3)
                val n = f[2].toIntOrNull()?.takeIf { it >= 0 } ?: wireCorrupt()
                wireRequire(used.put(readWireAtom(f[1]), n) == null)
            }
            "I", "P" -> {
                wireRequire(f.size == 3)
                val into = if (f[0] == "I") accepted else previous
                wireRequire(into.getOrPut(readWireAtom(f[1])) { linkedSetOf() }.add(readWireAtom(f[2])))
            }
            "G" -> {
                wireRequire(f.size == 9)
                val target = readWireAtom(f[1])
                val start = AccessClockStamp(wireLong(f[3]), wireLong(f[4]), readWireAtom(f[5]))
                val grant = OpenCreditPolicy.Grant(target, readWireAtom(f[2]), start, wireLong(f[6]))
                wireRequire(active.put(target, OpenCreditAccess(grant,
                    AnchoredInterval(start, wireLong(f[7]), wireLong(f[8])))) == null)
            }
            else -> wireFuture()
        } }
        wireRequire((accepted.keys + previous.keys + active.keys).all { it in used })
        val counters = used.mapValues { (target, n) -> OpenCreditPolicy.Counter(target, period, n, reset,
            accepted[target].orEmpty().toSet(), previous[target].orEmpty().toSet()) }
        val value = OpenCreditLedger(readWireAtom(meta[1]), period, reset, at.copy(wallEpochMs = wireLong(meta[7])), at,
            bool(meta[8]), counters, active.toMap(), bool(meta[9]))
        validOpenCredit { OpenCreditLedgerPolicy.requireValid(value) }
        return value
    }

    fun archive(counts: String?, day: Long?): String =
        "A|1\nD|${day ?: "-"}\nC|${if (counts == null) "-" else "+${PrefsCodec.escape(counts)}"}"
            .also(::readArchive)

    fun readArchive(raw: String): Pair<String?, Long?> {
        val rows = wireRows(raw, "A")
        wireRequire(rows.size == 2)
        val dayRow = rows.singleOrNull { it[0] == "D" } ?: wireCorrupt()
        val countRow = rows.singleOrNull { it[0] == "C" } ?: wireCorrupt()
        wireRequire(dayRow.size == 2 && countRow.size == 2)
        val day = dayRow[1].takeUnless { it == "-" }?.let(::wireLong)
        val encoded = countRow[1]
        val counts = if (encoded == "-") null else {
            wireRequire(encoded.startsWith("+"))
            PrefsCodec.unescape(encoded.drop(1)).also {
                wireRequire(PrefsCodec.escape(it) == encoded.drop(1)); StrictPrefsCodec.counts(it)
            }
        }
        wireRequire((day == null) == (counts == null))
        return counts to day
    }

    private fun bool(value: String) = value.toBooleanStrictOrNull() ?: wireCorrupt()
}
