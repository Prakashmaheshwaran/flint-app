package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.AccessClockStamp

/** Bounded, canonical additive schema. No malformed/future/missing row becomes unused quota. */
internal object TimeLimitUsageCodec {
    private const val MAX_TARGETS = 2_000

    fun encode(ledger: TimeLimitUsageLedger): String {
        timeUsageValid { OpenCreditLedgerPolicy.quotaZone(ledger.migrationZoneId) }
        timeUsageRequire(ledger.targets.size <= MAX_TARGETS)
        return buildList {
            add("T|1")
            add("M|${wireAtom(ledger.migrationZoneId)}|${ledger.targets.size}")
            ledger.targets.toSortedMap().forEach { (target, row) ->
                timeUsageRequire(target.isNotBlank() && target.length <= OpenCreditLedgerPolicy.MAX_ATOM)
                timeUsageValid { TimeLimitDayAccounting.requireValid(row) }
                timeUsageRequire(row.usedMs == 0L || row.lastSample != null)
                val at = row.observedAt
                add("R|${wireAtom(target)}|${wireAtom(row.zoneId)}|${row.periodId}|${row.usedMs}|${row.reset.remainingToResetMs}|" +
                    "${at.wallEpochMs}|${at.elapsedRealtimeMs}|${wireAtom(at.bootSessionId)}|${row.logicalCheckpoint.wallEpochMs}|${row.timeUncertain}|${row.accountingUnknownUntilReset}")
                row.lastSample?.let { sample ->
                    val stamp = sample.observedAt
                    add("S|${wireAtom(target)}|${sample.windowStartEpochMs}|${sample.totalMs}|${stamp.wallEpochMs}|" +
                        "${stamp.elapsedRealtimeMs}|${wireAtom(stamp.bootSessionId)}")
                }
            }
        }.joinToString("\n").also { timeUsageRequire(it.length <= 4_194_304) }
    }

    fun decode(raw: String): TimeLimitUsageLedger {
        val rows = wireRows(raw, "T")
        val meta = rows.singleOrNull { it[0] == "M" } ?: wireCorrupt()
        timeUsageRequire(meta.size == 3)
        val count = wireLong(meta[2])
        timeUsageRequire(count <= MAX_TARGETS)
        val targets = linkedMapOf<String, TimeLimitDayState>()
        val samples = linkedMapOf<String, TimeLimitUsageSample>()
        rows.filter { it[0] != "M" }.forEach { f -> when (f[0]) {
            "R" -> {
                timeUsageRequire(f.size == 12)
                val target = readWireAtom(f[1])
                val stamp = AccessClockStamp(wireLong(f[6]), wireLong(f[7]), readWireAtom(f[8]))
                val uncertain = f[10].toBooleanStrictOrNull() ?: wireCorrupt()
                val unknown = f[11].toBooleanStrictOrNull() ?: wireCorrupt()
                val row = TimeLimitDayState(readWireAtom(f[2]), wireLong(f[3]), OpenCreditPolicy.ResetClock(stamp, wireLong(f[5])),
                    stamp.copy(wallEpochMs = wireLong(f[9])), stamp, wireLong(f[4]), timeUncertain = uncertain,
                    accountingUnknownUntilReset = unknown)
                timeUsageRequire(targets.put(target, row) == null)
            }
            "S" -> {
                timeUsageRequire(f.size == 7)
                val sample = TimeLimitUsageSample(wireLong(f[2]), wireLong(f[3]), AccessClockStamp(wireLong(f[4]), wireLong(f[5]), readWireAtom(f[6])))
                timeUsageRequire(samples.put(readWireAtom(f[1]), sample) == null)
            }
            else -> wireFuture()
        } }
        timeUsageRequire(targets.size.toLong() == count && samples.keys.all { it in targets })
        val ledger = TimeLimitUsageLedger(readWireAtom(meta[1]), targets.mapValues { (target, row) -> row.copy(lastSample = samples[target]) })
        encode(ledger) // Full field validation, including bounds, saved zones and critical sample rows.
        return ledger
    }

    fun archive(limits: String?, tier: String?): String =
        "A|1\nT|${limits?.let { "+${PrefsCodec.escape(it)}" } ?: "-"}\nD|${tier?.let { "+${PrefsCodec.escape(it)}" } ?: "-"}"
            .also(::readArchive)

    /** Append-only identities independently attest to rows added after the legacy archive. */
    fun targetRegistry(targets: Set<String>): String {
        timeUsageRequire(targets.size <= MAX_TARGETS)
        return (listOf("K|1", "N|${targets.size}") + targets.sorted().map {
            timeUsageRequire(it.isNotBlank() && it.length <= OpenCreditLedgerPolicy.MAX_ATOM)
            "P|${wireAtom(it)}"
        }).joinToString("\n")
    }

    fun readTargetRegistry(raw: String): Set<String> {
        val rows = wireRows(raw, "K")
        val count = rows.singleOrNull { it[0] == "N" } ?: wireCorrupt()
        timeUsageRequire(count.size == 2 && wireLong(count[1]) <= MAX_TARGETS)
        val targets = linkedSetOf<String>()
        rows.filter { it[0] != "N" }.forEach {
            if (it[0] != "P") wireFuture()
            timeUsageRequire(it.size == 2 && targets.add(readWireAtom(it[1])))
        }
        timeUsageRequire(targets.size.toLong() == wireLong(count[1]))
        targetRegistry(targets) // Validate bounded, nonblank identities.
        return targets
    }

    fun readArchive(raw: String): Pair<String?, String?> {
        val rows = wireRows(raw, "A")
        timeUsageRequire(rows.size == 2)
        fun value(key: String): String? {
            val row = rows.singleOrNull { it[0] == key } ?: wireCorrupt()
            timeUsageRequire(row.size == 2)
            if (row[1] == "-") return null
            timeUsageRequire(row[1].startsWith("+"))
            return PrefsCodec.unescape(row[1].drop(1)).also { timeUsageRequire(PrefsCodec.escape(it) == row[1].drop(1)) }
        }
        val limits = value("T")
        val tier = value("D")
        StrictPrefsCodec.timeLimits(limits.orEmpty(), StrictPrefsCodec.level(tier))
        return limits to tier
    }
}
