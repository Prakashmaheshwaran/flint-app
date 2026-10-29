package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.*

/** Required, bounded, canonical pass/outbox record. Missing rows never imply unused state. */
internal object ProtectionLedgerCodec {
    private const val MAX_ROWS = 5_000
    private const val MAX_RECORDS = 2_000
    private const val MAX_BYTES = 4_194_304

    fun encode(s: ProtectionLedger): String = buildList {
        add("P|2")
        add(row("M", clock(s.initializedAt), clock(s.checkpoint), s.droppedEvents, s.previousAccessEnded, s.operations.size, s.outbox.size,
            s.lastUsedAtEpochMs?.toString().orEmpty(), s.ordinaryGrants.size, s.pendingCreditEnds.size))
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
                event.grantId.orEmpty(), event.untilEpochMs?.toString().orEmpty(), event.breakEndReason?.name.orEmpty(),
                strings(event.endedTransientIds), event.revision, event.timeUncertain))
        }
        s.ordinaryGrants.toSortedMap().forEach { (id, grant) ->
            require(id == grant.operationId)
            // Reuse the bounded, versioned exact-scope codec; this fragment carries one
            // grant and no counters or pending authority. The outer operation ID owns it.
            val one = TemporaryAccess(1, grant.target, grant.scopes, PersonalGrantKind.ORDINARY_BREAK, grant.lifetime)
            add(row("G", atom(id), AccessLedgerCodec.encode(AccessLedgerV2(nextSequence = 2, grants = mapOf(1L to one))), strings(grant.affectedRuleIds)))
        }
        s.pendingCreditEnds.toSortedMap().forEach { (id, receipt) ->
            checkWire(id == receipt.operationId)
            add(row("L", atom(id), atom(receipt.target), clock(receipt.lifetime.startedAt), receipt.lifetime.durationMs))
        }
    }.joinToString("\n").also { checkWire(it.length <= MAX_BYTES); decode(it) }

    fun decode(raw: String): ProtectionLedger {
        checkWire(raw.length <= MAX_BYTES)
        val lines = raw.split('\n')
        checkWire(lines.size <= MAX_ROWS)
        val header = fields(lines.first(), 2)
        checkWire(header[0] == "P" && header[1].toIntOrNull() != null)
        val version = header[1].toInt()
        if (version !in 1..2) wireFuture()
        var meta: List<String>? = null
        var cooldown: ProtectionCooldown? = null
        var recovery: ProtectionRecovery? = null
        var hadCooldown = false
        var hadRecovery = false
        val operations = linkedMapOf<String, ProtectionPassReceipt>()
        val events = linkedMapOf<String, ProtectionLifecycleEvent>()
        val grants = linkedMapOf<String, ProtectionOrdinaryGrant>()
        val creditEnds = linkedMapOf<String, ProtectionCreditEventReceipt>()
        for (line in lines.drop(1)) {
            when (line.substringBefore('|')) {
                "M" -> { checkWire(meta == null); meta = fields(line, if (version == 1) 9 else 10) }
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
                    val f = fields(line, 12); val id = atom(f[1])
                    val event = ProtectionLifecycleEvent(id, atom(f[2]), enum(f[3]), readStrings(f[4]), readClock(f[5]),
                        f[6].takeIf(String::isNotEmpty)?.let(::atom), f[7].takeIf(String::isNotEmpty)?.let(::number),
                        f[8].takeIf(String::isNotEmpty)?.let { enum<ProtectionBreakEndReason>(it) },
                        readStrings(f[9]), number(f[10]), bool(f[11]))
                    checkWire(event.endedTransientIds.all { it in event.affectedRuleIds })
                    val until = event.untilEpochMs
                    checkWire(when (event.kind) {
                        ProtectionLifecycleKind.PASS_COMMITTED -> event.grantId == null && event.breakEndReason == null && until != null && until >= event.at.wallEpochMs
                        ProtectionLifecycleKind.BREAK_STARTED, ProtectionLifecycleKind.ACCESS_STARTED -> event.grantId != null && event.breakEndReason == null && until != null && until > event.at.wallEpochMs
                        ProtectionLifecycleKind.BREAK_ENDED, ProtectionLifecycleKind.ACCESS_ENDED -> event.grantId != null && event.breakEndReason != null && until == null
                    })
                    checkWire(events.put(id, event) == null)
                }
                "G" -> {
                    val f = fields(line, 4); val id = atom(f[1])
                    val nested = try { AccessLedgerCodec.decode(f[2]) } catch (e: AccessCodecException) {
                        if (e.failure == AccessStoreFailure.UNSUPPORTED_VERSION) wireFuture() else wireCorrupt()
                    }
                    checkWire(nested.revision == 0L && nested.nextSequence == 2L && nested.ordinaryUnblocks.isEmpty() && nested.pending.isEmpty() && nested.grants.keys == setOf(1L))
                    val grant = nested.grants.getValue(1)
                    checkWire(grant.kind == PersonalGrantKind.ORDINARY_BREAK && grant.lifetime.durationMs in 1..300_000)
                    val rules = readStrings(f[3])
                    checkWire(rules == grant.scopes.filter { it.counter.rule.source == "rule" || it.counter.rule.source == "foreign" }.map { it.counter.rule.ruleId }.toSet())
                    checkWire(grants.put(id, ProtectionOrdinaryGrant(id, grant.target, grant.lifetime, grant.scopes, rules)) == null)
                }
                "L" -> {
                    checkWire(version == 2)
                    val f = fields(line, 5); val id = atom(f[1])
                    val duration = number(f[4]).also { checkWire(it in 1..300_000) }
                    val start = readClock(f[3])
                    checkWire(start.wallEpochMs <= Long.MAX_VALUE - duration && start.elapsedRealtimeMs <= Long.MAX_VALUE - duration)
                    checkWire(creditEnds.put(id, ProtectionCreditEventReceipt(id, atom(f[2]), AccessDuration(start, duration))) == null)
                }
                else -> wireFuture()
            }
        }
        val m = meta ?: wireCorrupt()
        checkWire(hadCooldown && hadRecovery && operations.size == integer(m[5]) && events.size == integer(m[6]))
        checkWire(operations.size <= MAX_RECORDS && events.size <= MAX_RECORDS && grants.size == integer(m[8]) && grants.size <= MAX_RECORDS)
        val lastUsed = m[7].takeIf(String::isNotEmpty)?.let(::number)
        checkWire(cooldown == null || cooldown.usedAtWallEpochMs == lastUsed)
        recovery?.let { r ->
            checkWire(r.affectedCauseKeys.isNotEmpty() && r.endedTransientIds.all { it in r.affectedRuleIds })
            checkWire(cooldown?.operationId == r.operationId && cooldown?.usedAtWallEpochMs == r.startedAt.wallEpochMs)
            checkWire(operations[r.operationId]?.acceptedAt == r.startedAt)
        }
        checkWire(grants.values.map { it.target }.distinct().size == grants.size)
        checkWire(grants.values.all { operations[it.operationId]?.acceptedAt == it.lifetime.startedAt })
        checkWire(creditEnds.size == (if (version == 1) 0 else integer(m[9])) && creditEnds.size <= 128)
        checkWire(creditEnds.values.map { it.target }.distinct().size == creditEnds.size)
        return ProtectionLedger(readClock(m[1]), readClock(m[2]), cooldown, recovery, operations.toMap(), events.toMap(), number(m[3]), bool(m[4]), lastUsed, grants.toMap(), creditEnds.toMap())
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
