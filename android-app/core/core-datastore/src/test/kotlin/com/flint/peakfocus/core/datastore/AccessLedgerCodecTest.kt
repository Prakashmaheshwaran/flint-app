package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class AccessLedgerCodecTest {
    private val counter = AccessCounterKey(AccessRuleKey(RuleAuthority.Personal, "rules", "id|%,~\n雪"), "period\r1")
    private val scope = AccessCauseScope(counter, "revision%0A")
    private val clock = AccessClockStamp(1_000, 100, "boot|%\n1")
    private val pending = PendingAccess(1, AccessTarget.App("com.app"), setOf(scope), 60_000, AccessDuration(clock, 5_000))
    private val grant = TemporaryAccess(2, AccessTarget.Website("browser", "example.com"), setOf(scope),
        PersonalGrantKind.ORDINARY_BREAK, AccessDuration(clock, 60_000))
    private val ledger = AccessLedgerV2(3, 3, mapOf(counter to 2), mapOf(1L to pending), mapOf(2L to grant))

    @Test fun completeLedgerRoundTripsEscapedAtomsAndBothTargets() {
        val encoded = AccessLedgerCodec.encode(ledger)
        assertEquals(ledger, AccessLedgerCodec.decode(encoded))
        assertEquals(encoded, AccessLedgerCodec.encode(AccessLedgerCodec.decode(encoded)))
    }

    @Test fun canonicalOrderingDoesNotDependOnMapInsertionOrder() {
        val other = counter.copy(periodId = "second")
        val a = ledger.copy(ordinaryUnblocks = linkedMapOf(counter to 2L, other to 1L))
        val b = ledger.copy(ordinaryUnblocks = linkedMapOf(other to 1L, counter to 2L))
        assertEquals(AccessLedgerCodec.encode(a), AccessLedgerCodec.encode(b))
    }

    @Test fun onlyAbsentValueMeansAnEmptyLedger() {
        assertEquals(AccessLedgerV2(), AccessLedgerCodec.decode(null))
        assertEquals("H|2|0|1", AccessLedgerCodec.encode(AccessLedgerV2()))
        failure("", AccessStoreFailure.CORRUPT)
        failure("H|2|0|1\n", AccessStoreFailure.UNSUPPORTED_VERSION)
    }

    @Test fun futureVersionsTagsAuthoritiesAndKindsAreUnavailable() {
        failure("H|3|0|1", AccessStoreFailure.UNSUPPORTED_VERSION)
        failure("H|2|0|1\nFUTURE|data", AccessStoreFailure.UNSUPPORTED_VERSION)
        failure(AccessLedgerCodec.encode(ledger).replace("|personal|", "|future|"), AccessStoreFailure.UNSUPPORTED_VERSION)
        failure(AccessLedgerCodec.encode(ledger).replace("ORDINARY_BREAK", "FUTURE_GRANT"), AccessStoreFailure.UNSUPPORTED_VERSION)
    }

    @Test fun malformedAndDuplicateRecordsInvalidateWholeLedger() {
        val encoded = AccessLedgerCodec.encode(ledger)
        val count = encoded.lines().first { it.startsWith("C|") }
        val scopeRow = encoded.lines().first { it.startsWith("S|") }
        val pendingRow = encoded.lines().first { it.startsWith("P|") }
        listOf(
            "$encoded\n$count", "$encoded\n$scopeRow", "$encoded\n$pendingRow",
            encoded.replace("H|2|3|3", "H|2|-1|3"),
            encoded.replace("H|2|3|3", "H|2|3|2"),
            encoded.replace("%7C", "%ZZ"),
            encoded.replace(count, count.substringBeforeLast('|') + "|-1"),
            encoded.replace(scopeRow, scopeRow.replaceFirst("S|", "S|999|")),
            encoded.lines().filterNot { it.startsWith("S|") }.joinToString("\n"),
            "H|2|0|2\nC|personal|||||rules|id|period|not-a-number",
        ).forEach { failure(it, AccessStoreFailure.CORRUPT) }
    }

    @Test fun orphanScopeAndAmbiguousRevisionsAreRejected() {
        val encoded = AccessLedgerCodec.encode(ledger)
        val scopeRow = encoded.lines().first { it.startsWith("S|1|") }
        failure("H|2|0|1\n$scopeRow", AccessStoreFailure.CORRUPT)
        failure("$encoded\n${scopeRow.substringBeforeLast('|')}|another-revision", AccessStoreFailure.CORRUPT)
    }

    @Test fun oversizedPayloadAndAtomAreRejected() {
        failure("x".repeat(AccessLedgerCodec.MAX_ENCODED_BYTES + 1), AccessStoreFailure.CORRUPT)
        val tooLong = ledger.copy(ordinaryUnblocks = mapOf(counter.copy(periodId = "x".repeat(2_049)) to 1))
        assertThrows(IllegalArgumentException::class.java) { AccessLedgerCodec.encode(tooLong) }
    }

    @Test fun managedNamespaceCanPreserveCounterIdentityButCannotBecomePersonalGrant() {
        val managed = counter.copy(rule = counter.rule.copy(authority = RuleAuthority.Managed("p", "u", "g", "membership-2")))
        val state = AccessLedgerV2(ordinaryUnblocks = mapOf(managed to 3))
        assertEquals(state, AccessLedgerCodec.decode(AccessLedgerCodec.encode(state)))
        assertThrows(IllegalArgumentException::class.java) {
            AccessLedgerCodec.encode(ledger.copy(grants = mapOf(2L to grant.copy(scopes = setOf(scope.copy(counter = managed))))))
        }
    }

    @Test fun sequenceCollisionAndDuplicatePendingRequestAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AccessLedgerCodec.encode(ledger.copy(grants = mapOf(1L to grant.copy(sequence = 1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessLedgerCodec.encode(ledger.copy(nextSequence = 4, pending = mapOf(1L to pending, 3L to pending.copy(sequence = 3))))
        }
    }

    private fun failure(encoded: String, expected: AccessStoreFailure) {
        val error = assertThrows(AccessCodecException::class.java) { AccessLedgerCodec.decode(encoded) }
        assertEquals(expected, error.failure)
    }
}
