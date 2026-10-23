package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AccessCauseScope
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AccessCounterKey
import com.flint.peakfocus.core.model.AccessDuration
import com.flint.peakfocus.core.model.AccessLedgerV2
import com.flint.peakfocus.core.model.AccessLedgerValidation
import com.flint.peakfocus.core.model.AccessRuleKey
import com.flint.peakfocus.core.model.AccessTarget
import com.flint.peakfocus.core.model.PendingAccess
import com.flint.peakfocus.core.model.PersonalGrantKind
import com.flint.peakfocus.core.model.RuleAuthority
import com.flint.peakfocus.core.model.TemporaryAccess

internal class AccessCodecException(val failure: AccessStoreFailure) :
    IllegalArgumentException("Access ledger could not be decoded: $failure")

/**
 * Independent versioned format; never extends or rewrites any v1 record. H is the header,
 * C a counter, P/G a pending request/grant, and S an exact cause scope for that sequence.
 * Unknown versions/tags are unavailable, and malformed records invalidate the entire ledger.
 */
internal object AccessLedgerCodec {
    const val MAX_ENCODED_BYTES = 2 * 1024 * 1024

    fun encode(ledger: AccessLedgerV2): String {
        AccessLedgerValidation.requireValid(ledger)
        val rows = mutableListOf<String>()
        ledger.ordinaryUnblocks.forEach { (key, count) -> rows += row(listOf("C") + counter(key) + count.toString()) }
        ledger.pending.values.forEach { pending ->
            rows += row(listOf("P", pending.sequence.toString()) + target(pending.target) +
                pending.requestedDurationMs.toString() + duration(pending.wait))
            rows += scopes(pending.sequence, pending.scopes)
        }
        ledger.grants.values.forEach { grant ->
            rows += row(listOf("G", grant.sequence.toString()) + target(grant.target) +
                grant.kind.name + duration(grant.lifetime))
            rows += scopes(grant.sequence, grant.scopes)
        }
        val encoded = (listOf("H|2|${ledger.revision}|${ledger.nextSequence}") + rows.sorted()).joinToString("\n")
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES)
        return encoded
    }

    fun decode(encoded: String?): AccessLedgerV2 {
        if (encoded == null) return AccessLedgerV2()
        try {
            require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES)
            val lines = encoded.split('\n')
            val header = fields(lines.first())
            require(header.size == 4 && header[0] == "H")
            if (header[1] != "2") unsupported()
            val counts = linkedMapOf<AccessCounterKey, Long>()
            val pending = linkedMapOf<Long, PendingAccess>()
            val grants = linkedMapOf<Long, TemporaryAccess>()
            val scopes = linkedMapOf<Long, MutableSet<AccessCauseScope>>()
            lines.drop(1).forEach { line ->
                val f = fields(line)
                when (f[0]) {
                    "C" -> {
                        require(f.size == 10)
                        require(counts.put(readCounter(f, 1), f[9].toLong()) == null)
                    }
                    "P" -> {
                        require(f.size == 10)
                        val id = f[1].toLong()
                        require(pending.put(id, PendingAccess(id, readTarget(f), emptySet(),
                            f[5].toLong(), readDuration(f))) == null)
                    }
                    "G" -> {
                        require(f.size == 10)
                        val id = f[1].toLong()
                        val kind = PersonalGrantKind.entries.firstOrNull { it.name == f[5] } ?: unsupported()
                        require(grants.put(id, TemporaryAccess(id, readTarget(f), emptySet(), kind,
                            readDuration(f))) == null)
                    }
                    "S" -> {
                        require(f.size == 11)
                        val scope = AccessCauseScope(readCounter(f, 2), f[10])
                        require(scopes.getOrPut(f[1].toLong()) { linkedSetOf() }.add(scope))
                    }
                    else -> unsupported()
                }
            }
            require(scopes.keys.all { it in pending || it in grants })
            return AccessLedgerV2(
                revision = header[2].toLong(),
                nextSequence = header[3].toLong(),
                ordinaryUnblocks = counts.toMap(),
                pending = pending.mapValues { (id, value) -> value.copy(scopes = scopes[id].orEmpty().toSet()) },
                grants = grants.mapValues { (id, value) -> value.copy(scopes = scopes[id].orEmpty().toSet()) },
            ).also(AccessLedgerValidation::requireValid)
        } catch (e: AccessCodecException) {
            throw e
        } catch (_: IllegalArgumentException) {
            throw AccessCodecException(AccessStoreFailure.CORRUPT)
        }
    }

    private fun counter(key: AccessCounterKey): List<String> {
        val authority = when (val a = key.rule.authority) {
            RuleAuthority.Personal -> listOf("personal", "", "", "", "")
            is RuleAuthority.Managed -> listOf("managed", a.projectId, a.accountUid, a.groupId, a.membershipId)
        }
        return authority + listOf(key.rule.source, key.rule.ruleId, key.periodId)
    }

    private fun readCounter(f: List<String>, at: Int): AccessCounterKey {
        val authority = when (f[at]) {
            "personal" -> { require(f.subList(at + 1, at + 5).all(String::isEmpty)); RuleAuthority.Personal }
            "managed" -> RuleAuthority.Managed(f[at + 1], f[at + 2], f[at + 3], f[at + 4])
            else -> unsupported()
        }
        return AccessCounterKey(AccessRuleKey(authority, f[at + 5], f[at + 6]), f[at + 7])
    }

    private fun scopes(id: Long, scopes: Set<AccessCauseScope>): List<String> = scopes.map {
        row(listOf("S", id.toString()) + counter(it.counter) + it.definitionRevision)
    }

    private fun target(target: AccessTarget): List<String> = when (target) {
        is AccessTarget.App -> listOf("app", target.packageName, "")
        is AccessTarget.Website -> listOf("website", target.browserPackageName, target.normalizedHost)
    }

    private fun readTarget(f: List<String>): AccessTarget = when (f[2]) {
        "app" -> { require(f[4].isEmpty()); AccessTarget.App(f[3]) }
        "website" -> AccessTarget.Website(f[3], f[4])
        else -> unsupported()
    }

    private fun duration(duration: AccessDuration): List<String> = listOf(
        duration.startedAt.wallEpochMs.toString(), duration.startedAt.elapsedRealtimeMs.toString(),
        duration.startedAt.bootSessionId, duration.durationMs.toString(),
    )

    private fun readDuration(f: List<String>) = AccessDuration(
        AccessClockStamp(f[6].toLong(), f[7].toLong(), f[8]), f[9].toLong(),
    )

    private fun row(atoms: List<String>): String = atoms.joinToString("|") {
        it.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A").replace("\r", "%0D")
    }

    private fun fields(line: String): List<String> = line.split('|').map { atom ->
        buildString {
            var i = 0
            while (i < atom.length) {
                if (atom[i] != '%') append(atom[i++])
                else {
                    require(i + 2 < atom.length)
                    append(when (atom.substring(i + 1, i + 3)) {
                        "25" -> '%'; "7C" -> '|'; "0A" -> '\n'; "0D" -> '\r'
                        else -> throw IllegalArgumentException("Invalid escape")
                    })
                    i += 3
                }
            }
        }
    }

    private fun unsupported(): Nothing = throw AccessCodecException(AccessStoreFailure.UNSUPPORTED_VERSION)
}
