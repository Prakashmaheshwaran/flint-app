package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.flint.peakfocus.blocking.engine.OpenCreditPolicy
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProtectionSnapshotReaderTest {
    private var now = AccessClockStamp(1_800_000_000_000L, 10_000, "boot")

    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val bytes = MutableStateFlow(initial)
        var writes = 0
        var forbidWrites = false
        override val data: Flow<Preferences> = bytes
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            check(!forbidWrites) { "A display read attempted a write" }
            writes++
            return transform(bytes.value).also { bytes.value = it }
        }
    }

    private suspend fun initialized(): Disk = Disk().also { disk ->
        val store = DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }
        assertTrue(store.transact { snapshot ->
            val credits = checkNotNull(snapshot.openCredits)
            ProtectionMutation(snapshot.focus, Unit, openCredits = credits.copy(counters = mapOf(
                "app" to OpenCreditPolicy.Counter("app", credits.periodId, 2, credits.reset),
            )))
        } is ProtectionCommit.Committed)
        disk.forbidWrites = true
    }

    @Test fun freshReadEmitsLoadingThenValidatedLegacyWithoutInitializingAnything() = runBlocking {
        val disk = Disk().apply { forbidWrites = true }
        val reader = DataStoreProtectionSnapshotReader(disk) { now }
        val states = reader.state.take(2).toList()
        assertEquals(StorageState.Loading, states.first())
        assertNull((states.last() as StorageState.Ready).value.focus.protection)
        assertNull((reader.openCredits.first { it !is StorageState.Loading } as StorageState.Ready).value)
        assertTrue(disk.bytes.value.asMap().isEmpty())
        assertEquals(0, disk.writes)
    }

    @Test fun initializedCreditReadsNeverCheckpointOrResetTheStoredLedger() = runBlocking {
        val disk = initialized()
        val before = disk.bytes.value.asMap().toMap()
        val writes = disk.writes
        now = now.copy(wallEpochMs = now.wallEpochMs + 2 * 86_400_000L,
            elapsedRealtimeMs = now.elapsedRealtimeMs + 2 * 86_400_000L)
        val reader = DataStoreProtectionSnapshotReader(disk) { now }
        val snapshot = (reader.state.first { it is StorageState.Ready } as StorageState.Ready).value
        val credits = (reader.openCredits.first { it is StorageState.Ready } as StorageState.Ready).value!!
        assertEquals(now, snapshot.observedAt)
        assertEquals(2, credits.counters.getValue("app").used)
        assertNotEquals(now, credits.observedAt)
        assertEquals(before, disk.bytes.value.asMap())
        assertEquals(writes, disk.writes)
    }

    @Test fun missingInitializedCreditChildIsUnavailableWithoutRepairingTheFile() = runBlocking {
        val disk = initialized()
        disk.bytes.value = disk.bytes.value.toMutablePreferences().apply { remove(OPEN_CREDIT_COUNTS) }
        val before = disk.bytes.value.asMap().toMap()
        val writes = disk.writes
        val reader = DataStoreProtectionSnapshotReader(disk) { now }
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), reader.state.first { it !is StorageState.Loading })
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), reader.openCredits.first { it !is StorageState.Loading })
        assertEquals(before, disk.bytes.value.asMap())
        assertEquals(writes, disk.writes)
    }

    @Test fun futureProtectionSchemaRemainsUnavailableAndUntouched() = runBlocking {
        val disk = initialized()
        disk.bytes.value = disk.bytes.value.toMutablePreferences().apply { this[PrefsKeys.PROTECTION_SCHEMA] = 2L }
        val before = disk.bytes.value.asMap().toMap()
        val writes = disk.writes
        val reader = DataStoreProtectionSnapshotReader(disk) { now }
        assertEquals(StorageState.Unavailable(StorageFailure.UNSUPPORTED_VERSION), reader.state.first { it !is StorageState.Loading })
        assertEquals(before, disk.bytes.value.asMap())
        assertEquals(writes, disk.writes)
    }

    @Test fun readIoFailureIsUnavailableAndCannotAttemptAWrite() = runBlocking {
        val disk = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("offline fixture") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Read attempted a write")
        }
        val reader = DataStoreProtectionSnapshotReader(disk) { now }
        assertEquals(StorageState.Unavailable(StorageFailure.IO), reader.state.first { it !is StorageState.Loading })
    }

    @Test fun cancellationIsNotConvertedToAnEmptyOrUnavailableSnapshot() = runBlocking {
        val disk = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw CancellationException("cancel fixture") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Read attempted a write")
        }
        try {
            DataStoreProtectionSnapshotReader(disk) { now }.state.first { it !is StorageState.Loading }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { /* The caller still owns cancellation. */ }
    }
}
