package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TimeLimitTransactionGuardsTest {
    private class Disk : DataStore<Preferences> {
        val values = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(values.value).also { values.value = it }
    }
    private val now = AccessClockStamp(Instant.parse("2026-10-28T23:00:00Z").toEpochMilli(), 10_000, "boot")
    private fun backend(disk: Disk) = DataStoreProtectionTransactionStore(disk, { now }, { "UTC" })
    private suspend fun seed(disk: Disk) {
        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit("app", 30, BreakLevel.HARDER, "UTC"))
        DataStoreLimitsStore(disk).setOpenLimit(OpenLimit("open-app", 1))
        assertTrue(backend(disk).transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        val store = DataStoreTimeLimitUsageStore(disk, deviceZone = { "UTC" })
        val ticket = store.measurementContext("app", now)
        store.record(ticket, 25 * 60_000, now)
    }
    private fun corrupt(disk: Disk) {
        disk.values.value = disk.values.value.toMutablePreferences().apply { this[TIME_USAGE_STATE] = "corrupt Time evidence" }.toPreferences()
    }
    private fun grant(snapshot: ProtectionSnapshot): ProtectionMutation<String> {
        val scope = AccessCauseScope(AccessCounterKey(AccessRuleKey(RuleAuthority.Personal, "time", "app"), "period"), "revision")
        val grant = ProtectionOrdinaryGrant("time-grant", AccessTarget.App("app"), AccessDuration(now, 60_000), setOf(scope), emptySet())
        val receipt = ProtectionPassReceipt(grant.operationId, now, now, "scope")
        return ProtectionMutation(snapshot.focus.copy(protection = snapshot.focus.protection!!.copy(
            ordinaryGrants = mapOf(grant.operationId to grant), operations = mapOf(receipt.operationId to receipt))), "granted")
    }

    @Test fun PC_37_corruptTimeEvidenceDeniesDirectTimeGrantWithoutMakingTheSharedSnapshotUnavailable() = runBlocking {
        val healthy = Disk(); seed(healthy)
        assertTrue(backend(healthy).transact(::grant) is ProtectionCommit.Committed)
        val disk = Disk(); seed(disk); corrupt(disk)
        val backend = backend(disk)
        assertTrue(backend.state.first { it !is StorageState.Loading } is StorageState.Ready)
        val before = disk.values.value
        val denied = backend.transact(::grant)
        assertEquals(ProtectionCommit.Unavailable(StorageFailure.CORRUPT), denied)
        assertEquals(before, disk.values.value)
        assertTrue(backend.state.first { it !is StorageState.Loading } is StorageState.Ready)
    }

    @Test fun PC_48_corruptTimeEvidenceStillAllowsPassAndOpenCreditInTheSameBackend() = runBlocking {
        val disk = Disk(); seed(disk); corrupt(disk)
        val backend = backend(disk)
        val pass = backend.transact { snapshot ->
            val ledger = snapshot.focus.protection!!
            val pass = ProtectionPassReceipt("pass", now, now, "scope")
            ProtectionMutation(snapshot.focus.copy(emergencyPass = snapshot.focus.emergencyPass.copy(usedAtEpochMs = now.wallEpochMs),
                protection = ledger.copy(cooldown = ProtectionCooldown("pass", now.wallEpochMs, now, ProtectionContract.PASS_COOLDOWN_MS),
                    recovery = ProtectionRecovery("pass", now, setOf(timeCauseKey("app")), emptySet(), emptySet(), ProtectionContract.PASS_RECOVERY_MS),
                    operations = mapOf("pass" to pass), lastUsedAtEpochMs = now.wallEpochMs)), "pass accepted")
        }
        assertTrue("$pass", pass is ProtectionCommit.Committed)
        val open = backend.transact { snapshot ->
            val credit = OpenCreditLedgerPolicy.acceptOpenCredit(snapshot.openCredits!!, "open-app", "credit", listOf(1), emptyList(), false, now)
            ProtectionMutation(snapshot.focus, credit.acceptance, openCredits = credit.next)
        }
        assertTrue("$open", open is ProtectionCommit.Committed)
        assertTrue((open as ProtectionCommit.Committed).result is OpenCreditPolicy.Acceptance.Accepted)
        assertEquals("corrupt Time evidence", disk.values.value[TIME_USAGE_STATE])
        assertTrue(backend.state.first { it !is StorageState.Loading } is StorageState.Ready)
    }

    @Test fun PC_56_PC_60_restoredTimeMutationNormalizesAndReplayNeverResetsRegisteredUsage() = runBlocking {
        val disk = Disk(); seed(disk)
        val backend = backend(disk)
        backend.transact { ProtectionMutation(it.focus, Unit, times = emptyList()) }
        val restored = backend.transact { ProtectionMutation(it.focus, Unit, times = listOf(TimeLimit("app", 30, BreakLevel.HARDER))) }
        assertTrue(restored is ProtectionCommit.Committed)
        assertEquals("UTC", (restored as ProtectionCommit.Committed).snapshot.times.single().zoneId)
        val persisted = disk.values.value
        val replayed = backend.transact { ProtectionMutation(it.focus, Unit, times = listOf(TimeLimit("app", 30, BreakLevel.HARDER))) }
        assertTrue(replayed is ProtectionCommit.Committed)
        assertEquals("UTC", (replayed as ProtectionCommit.Committed).snapshot.times.single().zoneId)
        val ledger = readTimeLimitUsage(disk.values.value)
        assertEquals(25 * 60_000L, ledger.targets.getValue("app").usedMs)
        assertEquals(persisted[TIME_USAGE_STATE], disk.values.value[TIME_USAGE_STATE])
    }
}
