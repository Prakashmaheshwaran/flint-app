package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.*

/** Required, bounded, canonical pass/outbox record. Missing rows never imply unused state. */
internal object ProtectionLedgerCodec {
    private const val MAX_ROWS = 5_000
    private const val MAX_RECORDS = 2_000
    private const val MAX_BYTES = 4_194_304

    fun encode(s: ProtectionLedger): String = buildList {
        add("P|1")
        add(row("M", clock(s.initializedAt), clock(s.checkpoint), s.droppedEvents, s.previousAccessEnded, s.operations.size, s.outbox.size,
            s.lastUsedAtEpochMs?.toString().orEmpty()))
        add(s.cooldown?.let { row("C", atom(it.operationId), it.usedAtWallEpochMs, clock(it.checkpoint), it.remainingAtCheckpointMs, it.wallUncertain) } ?: "C")
        add(s.recovery?.let { row("R", atom(it.operationId), clock(it.startedAt), strings(it.affectedCauseKeys),
            strings(it.affectedRuleIds), strings(it.endedTransientIds), it.durationMs) } ?: "R")
        s.operations.toSortedMap().forEach { (id, receipt) ->
            require(id == receipt.operationId)
            add(row("O", atom(id), clock(receipt.issuedAt), clock(receipt.acceptedAt), atom(receipt.scopeIdentity)))
        }
        s.outbox.toSortedMap().forEach { (id, event) ->
            require(id == event.id)
            add(row("E", atom(id), atom(event.operationId), event.kind.name, strings(event.affectedRuleIds), clock(event.at),
                event.grantId.orEmpty(), event.untilEpochMs?.toString().orEmpty(), event.breakEndReason?.name.orEmpty()))
        }
    }.joinToString("\n").also { require(it.length <= MAX_BYTES); decode(it) }

    fun decode(raw: String): ProtectionLedger {
        checkWire(raw.length <= MAX_BYTES)
        val lines = raw.split('\n')
        checkWire(lines.size <= MAX_ROWS)
        val header = fields(lines.first(), 2)
        checkWire(header[0] == "P" && header[1].toIntOrNull() != null)
        if (header[1] != "1") wireFuture()
        var meta: List<String>? = null
        var cooldown: ProtectionCooldown? = null
        var recovery: ProtectionRecovery? = null
        var hadCooldown = false
        var hadRecovery = false
        val operations = linkedMapOf<String, ProtectionPassReceipt>()
        val events = linkedMapOf<String, ProtectionLifecycleEvent>()
        for (line in lines.drop(1)) {
            when (line.substringBefore('|')) {
                "M" -> { checkWire(meta == null); meta = fields(line, 8) }
                "C" -> {
                    checkWire(!hadCooldown); hadCooldown = true
                    if (line != "C") {
                        val f = fields(line, 6)
                        cooldown = ProtectionCooldown(atom(f[1]), number(f[2]), readClock(f[3]),
                            number(f[4]).also { checkWire(it <= 604_800_000) }, bool(f[5]))
                    }
                }
                "R" -> {
                    checkWire(!hadRecovery); hadRecovery = true
                    if (line != "R") {
                        val f = fields(line, 7)
                        recovery = ProtectionRecovery(atom(f[1]), readClock(f[2]), readStrings(f[3]), readStrings(f[4]), readStrings(f[5]),
                            number(f[6]).also { checkWire(it in 1..3_600_000) })
                    }
                }
                "O" -> {
                    val f = fields(line, 5); val id = atom(f[1])
                    checkWire(operations.put(id, ProtectionPassReceipt(id, readClock(f[2]), readClock(f[3]), atom(f[4]))) == null)
                }
                "E" -> {
                    val f = fields(line, 9); val id = atom(f[1])
                    val event = ProtectionLifecycleEvent(id, atom(f[2]), enum(f[3]), readStrings(f[4]), readClock(f[5]),
                        f[6].takeIf(String::isNotEmpty)?.let(::atom), f[7].takeIf(String::isNotEmpty)?.let(::number),
                        f[8].takeIf(String::isNotEmpty)?.let { enum<ProtectionBreakEndReason>(it) })
                    val until = event.untilEpochMs
                    checkWire(when (event.kind) {
                        ProtectionLifecycleKind.PASS_COMMITTED -> event.grantId == null && event.breakEndReason == null && until != null && until >= event.at.wallEpochMs
                        ProtectionLifecycleKind.BREAK_STARTED -> event.grantId != null && event.breakEndReason == null && until != null && until > event.at.wallEpochMs
                        ProtectionLifecycleKind.BREAK_ENDED -> event.grantId != null && event.breakEndReason != null && until == null
                    })
                    checkWire(events.put(id, event) == null)
                }
                else -> wireFuture()
            }
        }
        val m = meta ?: wireCorrupt()
        checkWire(hadCooldown && hadRecovery && operations.size == integer(m[5]) && events.size == integer(m[6]))
        checkWire(operations.size <= MAX_RECORDS && events.size <= MAX_RECORDS)
        val lastUsed = m[7].takeIf(String::isNotEmpty)?.let(::number)
        checkWire(cooldown == null || cooldown.usedAtWallEpochMs == lastUsed)
        recovery?.let { r ->
            checkWire(r.affectedCauseKeys.isNotEmpty() && r.endedTransientIds.all { it in r.affectedRuleIds })
            checkWire(cooldown?.operationId == r.operationId && cooldown?.usedAtWallEpochMs == r.startedAt.wallEpochMs)
        }
        return ProtectionLedger(readClock(m[1]), readClock(m[2]), cooldown, recovery, operations.toMap(), events.toMap(), number(m[3]), bool(m[4]), lastUsed)
    }

    private fun clock(c: AccessClockStamp) = row(c.wallEpochMs, c.elapsedRealtimeMs, atom(c.bootSessionId))
    private fun readClock(raw: String) = fields(raw, 3).let { AccessClockStamp(number(it[0]), number(it[1]), atom(it[2])) }
    private fun strings(values: Set<String>) = values.sorted().joinToString("\n") { row(atom(it)) }
    private fun readStrings(raw: String): Set<String> {
        if (raw.isEmpty()) return emptySet()
        val values = raw.split('\n')
        checkWire(values.size <= MAX_RECORDS)
        return values.map { atom(fields(it, 1).single()) }.also { checkWire(it.distinct().size == it.size) }.toSet()
    }
    private fun row(vararg values: Any): String = values.joinToString("|") { PrefsCodec.escape(it.toString()) }
    private fun fields(raw: String, count: Int): List<String> = raw.split('|').also { checkWire(it.size == count) }.map { value ->
        PrefsCodec.unescape(value).also { checkWire(PrefsCodec.escape(it) == value) }
    }
    private fun atom(value: String) = value.also { checkWire(it.isNotBlank() && it.length <= 2_048) }
    private fun number(value: String): Long = value.toLongOrNull()?.takeIf { it >= 0 && it.toString() == value } ?: wireCorrupt()
    private fun integer(value: String) = number(value).also { checkWire(it <= Int.MAX_VALUE) }.toInt()
    private fun bool(value: String) = when (value) { "true" -> true; "false" -> false; else -> wireCorrupt() }
    private inline fun <reified T : Enum<T>> enum(value: String): T = enumValues<T>().firstOrNull { it.name == value } ?: wireFuture()
    private fun checkWire(value: Boolean) { if (!value) wireCorrupt() }
}
