package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.model.*
import com.google.gson.JsonArray
import org.junit.Assert.*
import org.junit.Test

class ManagedWireCodecTest {
    private fun reject(payload: String) {
        try { ManagedWireCodec.snapshot(payload, ManagedFixture.identity); fail("Invalid snapshot accepted") }
        catch (error: ManagedRequestFailure) { assertEquals(ManagedFailureKind.PROTOCOL, error.kind) }
    }
    @Test fun completeSnapshotRoundTripsTypedSelectionAndRecurrence() {
        val snapshot = ManagedWireCodec.snapshot(ManagedFixture.snapshot().toString(), ManagedFixture.identity)
        assertEquals(ManagedFixture.identity, snapshot.identity)
        assertEquals(2L, snapshot.revision)
        assertEquals(ManagedFixture.input.schedule, snapshot.rules.single().schedule)
        assertEquals(ManagedFixture.input.targets, snapshot.rules.single().targets)
    }
    @Test fun unknownMissingDuplicateNullAndCoercedFieldsAreRejected() {
        val valid = ManagedFixture.snapshot().toString()
        reject(valid.replace("\"complete\":true", "\"complete\":true,\"alien\":1"))
        reject(valid.replace("\"complete\":true,", ""))
        reject(valid.replace("\"complete\":true", "\"complete\":true,\"complete\":true"))
        reject(valid.replace("\"complete\":true", "\"complete\":null"))
        reject(valid.replace("\"complete\":true", "\"complete\":\"true\""))
        reject(valid.replace("\"revision\":2", "\"revision\":\"2\""))
        reject(valid.replace("\"revision\":2", "\"revision\":2.0"))
        reject(valid.replace("\"revision\":2", "\"revision\":2e0"))
        reject(valid + "{}")
    }
    @Test fun weekdayOverflowAndOutOfRangeAreRejectedBeforeNarrowing() {
        for (number in listOf("4294967297", "0", "8", "-1", "9007199254740992")) {
            val row = ManagedFixture.snapshot()
            row.getAsJsonArray("rules")[0].asJsonObject.getAsJsonObject("config").add("weekdays", JsonArray().apply { add(com.google.gson.JsonParser.parseString(number)) })
            reject(row.toString())
        }
    }
    @Test fun leapSecondsInvalidCalendarAndNormalizedMidnightAreRejected() {
        for (time in listOf("2026-10-11T23:59:60.000Z", "2026-10-11T24:00:00.000Z", "2026-02-30T12:00:00.000Z",
            "2026-10-11T00:00:00.0001Z", "2026-10-11T00:00:00+00:00", "1969-12-31T23:59:59.000Z")) {
            reject(ManagedFixture.snapshot().apply { addProperty("serverTime", time) }.toString())
        }
    }
    @Test fun wrongActorOwnerTargetDuplicateRowsAndFutureRuleRevisionAreRejected() {
        reject(ManagedFixture.snapshot().apply { addProperty("userId", "foreign") }.toString())
        reject(ManagedFixture.snapshot().apply { getAsJsonArray("memberships")[0].asJsonObject.addProperty("role", "owner") }.toString())
        reject(ManagedFixture.snapshot().apply { getAsJsonArray("rules").add(ManagedFixture.rule()) }.toString())
        reject(ManagedFixture.snapshot().apply { getAsJsonArray("rules")[0].asJsonObject.addProperty("revision", 3) }.toString())
        reject(ManagedFixture.snapshot().apply { getAsJsonArray("rules")[0].asJsonObject.getAsJsonObject("scope").apply {
            addProperty("kind", "member"); addProperty("userId", "foreign")
        } }.toString())
    }
    @Test fun inputErrorsAreValidationAndUtf8ByteLimitIsApplied() {
        for (input in listOf(ManagedFixture.input.copy(name = "\n"), ManagedFixture.input.copy(zoneId = "Bad/Zone"),
            ManagedFixture.input.copy(targets = ManagedTargets.Selection((1..20).map { "com.example." + "a".repeat(150) + it }.toSet(), emptySet())))) {
            try { ManagedWireCodec.input(input); fail("Invalid input accepted") }
            catch (error: ManagedRequestFailure) { assertEquals(ManagedFailureKind.VALIDATION, error.kind) }
        }
    }
    @Test fun fingerprintIgnoresTransportTimeAndRuntimeGenerationButTracksPolicyContent() {
        val first = ManagedWireCodec.snapshot(ManagedFixture.snapshot().toString(), ManagedFixture.identity)
        val later = first.copy(identity = first.identity.copy(sessionGeneration = 99), serverTimeEpochMs = first.serverTimeEpochMs + 1000)
        assertEquals(ManagedWireCodec.fingerprint(first), ManagedWireCodec.fingerprint(later))
        assertNotEquals(ManagedWireCodec.fingerprint(first), ManagedWireCodec.fingerprint(first.copy(rules = emptyList())))
    }
    @Test fun payloadLimitAndUnsupportedSchemaAreRejected() {
        reject(" ".repeat(ManagedWireCodec.SNAPSHOT_LIMIT) + "{}")
        reject(ManagedFixture.snapshot().apply { addProperty("schemaVersion", 2) }.toString())
        reject(ManagedFixture.snapshot().apply { addProperty("complete", false) }.toString())
    }
}
