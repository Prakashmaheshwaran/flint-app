package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProtectionTransactionStoreTest {
    private val stamp = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var fail = false
        var loseAck = false
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(values.value)
            if (fail) throw IOException("injected")
            values.value = next
            if (loseAck) throw CancellationException("simulated death after durable commit")
            return next
        }
    }
    private fun store(disk: Disk) = DataStoreProtectionTransactionStore(disk, { stamp }) { "UTC" }
    private suspend fun initialize(disk: Disk) = store(disk).transact { ProtectionMutation(it.focus, Unit) }
    private fun consumed(s: FocusSnapshot): FocusSnapshot {
        val ledger = checkNotNull(s.protection)
        val event = ProtectionLifecycleEvent("pass:p1-id", "p1-id", ProtectionLifecycleKind.PASS_COMMITTED,
            setOf("session-one", "work"), stamp, untilEpochMs = stamp.wallEpochMs + 3_600_000)
        return s.copy(emergencyPass = s.emergencyPass.copy(usedAtEpochMs = stamp.wallEpochMs), protection = ledger.copy(
            cooldown = ProtectionCooldown("p1-id", stamp.wallEpochMs, stamp, 604_800_000),
            recovery = ProtectionRecovery("p1-id", stamp, setOf("rule:session-one", "rule:work"),
                setOf("session-one", "work"), setOf("session-one"), 3_600_000),
            operations = mapOf("p1-id" to ProtectionPassReceipt("p1-id", stamp, stamp, "scope")),
            outbox = mapOf(event.id to event), lastUsedAtEpochMs = stamp.wallEpochMs))
    }

    @Test fun PC_47_PC_70_literalUnusedRecordHasRequiredRowsAndLegacyFocusFormatsRemainReadable() {
        val raw = "P|1\nM|0%7C0%7Cboot|0%7C0%7Cboot|0|false|0|0|\nC\nR"
        val expected = ProtectionLedger(AccessClockStamp(0, 0, "boot"), AccessClockStamp(0, 0, "boot"))
        assertEquals(expected, ProtectionLedgerCodec.decode(raw))
        assertEquals(raw, ProtectionLedgerCodec.encode(expected))
        for (row in listOf("M|", "C", "R")) {
            try { ProtectionLedgerCodec.decode(raw.lines().filterNot { it.startsWith(row) }.joinToString("\n")); fail("Missing row accepted") }
            catch (e: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, e.reason) }
        }
        for (old in listOf("H|1", "H|1|7", "H|2|7|0")) assertFalse(FocusLedgerCodec.decode(old).protectionInitialized)
        assertTrue(FocusLedgerCodec.decode("H|3|7|0").protectionInitialized)
    }

    @Test fun PC_69_PC_70_validatedMigrationArchivesTheLiteralLegacyUseWithoutAMondayRefillAndRunsOnce() = runBlocking {
        val used = stamp.wallEpochMs - 86_400_000
        val original = mutablePreferencesOf(PrefsKeys.FOCUS_LEDGER to "H|2|7|0", PrefsKeys.EMERGENCY_PASS_USED_AT to used,
            PrefsKeys.EMERGENCY_PASS_WEEK_START to 1L, PrefsKeys.BREAK_SESSION to "3||")
        // Use the current codec's literal field layout for this independent legacy key.
        original[PrefsKeys.BREAK_SESSION] = PrefsCodec.encodeBreakSession(BreakSessionState(breaksTaken = 3))
        val disk = Disk(original)
        val committed = initialize(disk) as ProtectionCommit.Committed
        assertEquals(6 * 86_400_000L, committed.snapshot.focus.protection!!.cooldown!!.remainingAtCheckpointMs)
        assertEquals(used, committed.snapshot.focus.emergencyPass.usedAtEpochMs)
        assertTrue(disk.values.value[PrefsKeys.PROTECTION_ARCHIVE]!!.contains("focus_exemptions_v1|true|H%7C2%7C7%7C0"))
        assertEquals("H|3|8|0", disk.values.value[PrefsKeys.FOCUS_LEDGER])
        val bytes = disk.values.value
        initialize(disk)
        assertEquals(bytes, disk.values.value)
    }

    @Test fun PC_47_PC_69_futureUsageOrContradictoryUnusedLegacyStateCannotInitialize() = runBlocking {
        val receipt = PersistedExemption("old", "app", "cause", ExemptionKind.EMERGENCY_PASS, stamp.wallEpochMs + 1)
        for (initial in listOf(
            mutablePreferencesOf(PrefsKeys.EMERGENCY_PASS_USED_AT to stamp.wallEpochMs + 1),
            mutablePreferencesOf(PrefsKeys.FOCUS_LEDGER to FocusLedgerCodec.encode(FocusLedgerCodec.Ledger(mapOf("old" to receipt)))),
        )) {
            val disk = Disk(initial)
            assertEquals(ProtectionCommit.Unavailable(StorageFailure.CORRUPT), initialize(disk))
            assertEquals(initial, disk.values.value)
        }
    }

    @Test fun PC_37_PC_47_PC_69_afterInitializationEveryMissingCriticalKeyIsUnavailableAndNeverReimports() = runBlocking {
        val disk = Disk()
        initialize(disk)
        store(disk).transact { ProtectionMutation(consumed(it.focus), Unit) }
        val good = disk.values.value
        for (key in listOf(PrefsKeys.PROTECTION_SCHEMA, PrefsKeys.PROTECTION_LEDGER, PrefsKeys.PROTECTION_ARCHIVE,
            PrefsKeys.FOCUS_LEDGER, PrefsKeys.EMERGENCY_PASS_USED_AT)) {
            val missing = good.toMutablePreferences().apply { remove(key) }
            disk.values.value = missing
            var called = false
            assertEquals(ProtectionCommit.Unavailable(StorageFailure.CORRUPT), store(disk).transact { called = true; ProtectionMutation(it.focus, Unit) })
            assertFalse(called)
            assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), DataStoreFocusStateStore(disk).state.drop(1).first())
            assertEquals(missing, disk.values.value)
        }
    }

    @Test fun PC_70_futureOrPartialNewSchemaPreservesAllRawBytes() = runBlocking {
        for (initial in listOf(
            mutablePreferencesOf(PrefsKeys.PROTECTION_SCHEMA to 2L),
            mutablePreferencesOf(PrefsKeys.PROTECTION_LEDGER to "P|2"),
            mutablePreferencesOf(PrefsKeys.PROTECTION_SCHEMA to 1L),
        )) {
            val disk = Disk(initial)
            val result = initialize(disk)
            assertTrue(result is ProtectionCommit.Unavailable)
            assertEquals(initial, disk.values.value)
        }
    }

    @Test fun PC_44_PC_65_passReceiptOutboxAndRuleRetirementCommitTogetherOrNonePublish() = runBlocking {
        val rule = BlockRule("session-one", "One", BlockTargets(apps = setOf(AppRef("a"))), expiresAtEpochMs = stamp.wallEpochMs + 60_000)
        val disk = Disk(mutablePreferencesOf(PrefsKeys.BLOCK_RULES to PrefsCodec.encodeRules(listOf(rule))))
        initialize(disk)
        val before = disk.values.value
        disk.fail = true
        val failed = store(disk).transact { ProtectionMutation(consumed(it.focus), Unit, rules = emptyList()) }
        assertEquals(ProtectionCommit.Unavailable(StorageFailure.IO), failed)
        assertEquals(before, disk.values.value)
        disk.fail = false
        val accepted = store(disk).transact { ProtectionMutation(consumed(it.focus), Unit, rules = emptyList()) } as ProtectionCommit.Committed
        assertTrue(accepted.snapshot.rules.isEmpty())
        val restarted = store(disk).state.snapshotOrNull()!!
        assertEquals(accepted.snapshot.focus, restarted.focus)
        assertEquals(1, restarted.focus.protection!!.outbox.size)
        assertNotNull(restarted.focus.protection!!.recovery)
    }

    @Test fun PC_45_PC_63_PC_65_cancelledAcknowledgementRestoresTheSameReceiptAndEventAfterRestart() = runBlocking {
        val disk = Disk()
        initialize(disk)
        disk.loseAck = true
        try { store(disk).transact { ProtectionMutation(consumed(it.focus), Unit) }; fail("Lost acknowledgement did not cancel") }
        catch (_: CancellationException) { }
        disk.loseAck = false
        val recovered = store(disk).state.snapshotOrNull()!!.focus
        assertEquals("p1-id", recovered.protection!!.recovery!!.operationId)
        assertEquals(setOf("pass:p1-id"), recovered.protection!!.outbox.keys)
        assertEquals(stamp.wallEpochMs, recovered.emergencyPass.usedAtEpochMs)
    }

    @Test fun PC_65_acknowledgedPassAndOutboxSnapshotsFreezeCallerOwnedSets() = runBlocking {
        val disk = Disk(); initialize(disk)
        val ids = mutableSetOf("session-one", "work")
        val accepted = store(disk).transact {
            val next = consumed(it.focus)
            val ledger = next.protection!!
            ProtectionMutation(next.copy(protection = ledger.copy(recovery = ledger.recovery!!.copy(affectedRuleIds = ids),
                outbox = ledger.outbox.mapValues { e -> e.value.copy(affectedRuleIds = ids) })), Unit)
        } as ProtectionCommit.Committed
        ids.clear()
        assertEquals(setOf("session-one", "work"), accepted.snapshot.focus.protection!!.recovery!!.affectedRuleIds)
        assertEquals(setOf("session-one", "work"), accepted.snapshot.focus.protection!!.outbox.values.single().affectedRuleIds)
    }
}
