package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ManagedCacheStoreTest {
    private val binding = ManagedCacheBinding("demo-flint|localhost", "member")
    private val record = ManagedCachedSnapshot(binding, "session", 4, "a".repeat(64), "{\"complete\":true}", 1_000, 500, "boot-1")
    private val envelope = ManagedCacheEnvelope(ManagedCacheFence("session", binding), record, binding, 4, setOf("left-membership"))
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val current = MutableStateFlow(initial)
        var fail = false
        override val data: Flow<Preferences> = current
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val next = transform(current.value)
            if (fail) throw IOException("Synthetic disk failure")
            current.value = next
            return next
        }
    }
    @Test fun emptyCacheIsReadyWithoutImplicitWrite() = runBlocking {
        val disk = Disk()
        assertEquals(listOf(ManagedCacheState.Loading, ManagedCacheState.Ready(ManagedCacheEnvelope())), DataStoreManagedCacheStore(disk).state.take(2).toList())
        assertTrue(disk.current.value.asMap().isEmpty())
    }
    @Test fun dedicatedSchemaRejectsUnknownPartialWrongTypedAndFutureRecordsWithoutOverwriting() = runBlocking {
        val valid = Disk()
        DataStoreManagedCacheStore(valid).update { envelope }
        val original = valid.current.value
        val bad = mutableListOf<Pair<Preferences, ManagedCacheFailure>>()
        for (key in original.asMap().keys) {
            // Optional watermark binding is valid only as a complete pair, and
            // all snapshot fields are mandatory together.
            bad += original.toMutablePreferences().apply { remove(key) } to ManagedCacheFailure.CORRUPT
        }
        bad += original.toMutablePreferences().apply { this[stringPreferencesKey("alien")] = "x" } to ManagedCacheFailure.CORRUPT
        bad += original.toMutablePreferences().apply { this[stringPreferencesKey("schema")] = "1" } to ManagedCacheFailure.CORRUPT
        bad += original.toMutablePreferences().apply { this[intPreferencesKey("schema")] = 2 } to ManagedCacheFailure.UNSUPPORTED_VERSION
        bad += original.toMutablePreferences().apply { this[stringPreferencesKey("snapshot_session")] = "old-session" } to ManagedCacheFailure.CORRUPT
        bad += original.toMutablePreferences().apply { this[longPreferencesKey("server_time")] = Long.MAX_VALUE } to ManagedCacheFailure.CORRUPT
        for ((value, expected) in bad) {
            val disk = Disk(value)
            val store = DataStoreManagedCacheStore(disk)
            assertEquals("Keys: ${value.asMap().keys}", ManagedCacheState.Unavailable(expected), store.state.drop(1).first())
            var invoked = false
            assertEquals(ManagedCacheCommit.Unavailable(expected), store.update { invoked = true; ManagedCacheEnvelope() })
            assertFalse(invoked)
            assertEquals(value, disk.current.value)
        }
    }
    @Test fun orphanSnapshotMetadataNeverBecomesEmpty() = runBlocking {
        for (key in listOf("snapshot_session", "snapshot_fingerprint", "boot")) {
            val prefs = mutablePreferencesOf(intPreferencesKey("schema") to 1, longPreferencesKey("watermark") to 0L,
                stringSetPreferencesKey("excluded_memberships") to emptySet(), stringPreferencesKey(key) to "orphan")
            assertEquals(ManagedCacheState.Unavailable(ManagedCacheFailure.CORRUPT), DataStoreManagedCacheStore(Disk(prefs)).state.drop(1).first())
        }
    }
    @Test fun failedCommitRetainsWholePreviousRecordAndWatermarkCannotRewind() = runBlocking {
        val disk = Disk()
        val store = DataStoreManagedCacheStore(disk)
        store.update { envelope }
        disk.fail = true
        assertEquals(ManagedCacheCommit.Unavailable(ManagedCacheFailure.IO), store.update { it.copy(snapshot = null) })
        assertEquals(ManagedCacheState.Ready(envelope), store.state.drop(1).first())
        disk.fail = false
        try { store.update { it.copy(snapshot = null, watermark = 3) }; fail("Rewind accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(ManagedCacheState.Ready(envelope), store.state.drop(1).first())
    }
    @Test fun unavailableDiskIsNeverReadyEmpty() = runBlocking {
        val disk = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException() }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException()
        }
        val store = DataStoreManagedCacheStore(disk)
        assertEquals(listOf(ManagedCacheState.Loading, ManagedCacheState.Unavailable(ManagedCacheFailure.IO)), store.state.toList())
        assertEquals(ManagedCacheCommit.Unavailable(ManagedCacheFailure.IO), store.update { envelope })
    }
    @Test fun realDataStoreCloseReopenPreservesFenceSnapshotAnchorAndWatermarkTogether() = runBlocking {
        val directory = createTempDirectory("flint-managed-").toFile()
        val file = File(directory, "managed.preferences_pb")
        var owner = SupervisorJob()
        try {
            val first = DataStoreManagedCacheStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + owner), produceFile = { file }))
            assertTrue(first.update { envelope } is ManagedCacheCommit.Committed)
            owner.cancelAndJoin()
            owner = SupervisorJob()
            val reopened = DataStoreManagedCacheStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + owner), produceFile = { file }))
            assertEquals(envelope, reopened.state.filterIsInstance<ManagedCacheState.Ready>().first().value)
        } finally { owner.cancelAndJoin(); directory.deleteRecursively() }
    }
}
