package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TimeLimitBootstrapMigrationTest {
    private val at = AccessClockStamp(1_704_067_200_000, 10_000, "boot")

    @Test fun PC_56_PC_70_firstOpenCapturesInputsOnceWithoutRewritingEditorOrLegacyAuthority() = runBlocking {
        var zone = "UTC"
        var targets = setOf("legacy-only", "same-target")
        var queries = 0
        val migration = TimeLimitBootstrapMigration({ at }, { zone }, { queries++; targets })
        val original = preferencesOf(PrefsKeys.TIME_LIMITS to "same-target~30~HARDER",
            EDITOR_VERSION_KEY to 1L, EDITOR_COMMITMENTS_KEY to "exact saved editor bytes")
        assertTrue(migration.shouldMigrate(original))
        val migrated = migration.migrate(original)
        assertEquals(original.asMap(), migrated.asMap().filterKeys { it != TIME_BOOTSTRAP_CONTEXT })
        val saved = readTimeLimitBootstrapContext(migrated)!!
        assertEquals(TimeLimitBootstrapContext("UTC", at, targets), saved)
        zone = "Pacific/Auckland"; targets = setOf("later-target")
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
        migration.cleanUp()
        assertEquals(1, queries)
        assertNull(migrated[TIME_USAGE_SCHEMA])
        assertNull(migrated[TIME_LIMIT_ZONE_SCHEMA]) // No partial editor/zone migration.
    }

    @Test fun PC_70_PC_71_invalidSnapshotsCannotPartiallyPublishOrRecaptureTheZone() = runBlocking {
        for (targets in listOf(setOf(""), (0..2_000).map { "target-$it" }.toSet())) {
            val original = emptyPreferences()
            val migration = TimeLimitBootstrapMigration({ at }, { "UTC" }, { targets })
            val result = migration.migrate(original)
            assertEquals(TimeLimitBootstrapCapture.Unavailable(StorageFailure.CORRUPT), readTimeLimitBootstrapCapture(result))
            assertTrue(original.asMap().isEmpty())
            assertFalse(migration.shouldMigrate(result))
            assertEquals(result, migration.migrate(result))
        }
        val migration = TimeLimitBootstrapMigration({ at }, { "UTC" }, { emptySet() })
        for (prefs in listOf(preferencesOf(TIME_USAGE_SCHEMA to 1L),
            preferencesOf(TIME_LIMIT_ZONE_SCHEMA to 1L),
            preferencesOf(TIME_BOOTSTRAP_CONTEXT to "B|2"),
            preferencesOf(TIME_BOOTSTRAP_CONTEXT to "B|1\nC|UTC|0|0|boot"),
            preferencesOf(longPreferencesKey(TIME_BOOTSTRAP_CONTEXT.name) to 8L))) {
            val result = migration.migrate(prefs)
            assertEquals(prefs.asMap(), result.asMap().filterKeys { it.name != TIME_BOOTSTRAP_CONTEXT.name } +
                prefs.asMap().filterKeys { it.name == TIME_BOOTSTRAP_CONTEXT.name })
            assertTrue(readTimeLimitBootstrapCapture(result) is TimeLimitBootstrapCapture.Unavailable)
            assertFalse(migration.shouldMigrate(result))
        }
    }

    @Test fun PC_70_targetNamesAreStructuralAndBoundedRatherThanSplitByLabelText() = runBlocking {
        val targets = setOf("app|with~wire\\text", "app\nline", "ordinary")
        val migrated = TimeLimitBootstrapMigration({ at }, { "America/New_York" }, { targets }).migrate(emptyPreferences())
        assertEquals(targets, readTimeLimitBootstrapContext(migrated)!!.legacyTargets)
        val capacity = (1..2_000).map { "com.example.${"a".repeat(150)}.$it" }.toSet()
        val full = TimeLimitBootstrapMigration({ at }, { "UTC" }, { capacity }).migrate(emptyPreferences())
        assertEquals(capacity, readTimeLimitBootstrapContext(full)!!.legacyTargets)
    }

    @Test fun PC_70_PC_71_malformedLegacyValuesLeaveTheRealSharedDataStoreReadable() = runBlocking {
        val directory = Files.createTempDirectory("flint-time-bootstrap-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val migration = TimeLimitBootstrapMigration({ at }, { "UTC" }, {
                captureLegacyTimeLimitTargets(mapOf("app" to "malformed old value"))
            })
            val disk = PreferenceDataStoreFactory.create(migrations = listOf(migration), scope = scope,
                produceFile = { File(directory, "flint.preferences_pb") })
            val captured = disk.data.first()
            assertEquals(TimeLimitBootstrapCapture.Unavailable(StorageFailure.CORRUPT), readTimeLimitBootstrapCapture(captured))
            assertTrue(DataStoreBlockRulesStore(disk).rulesState.first { it !is com.flint.peakfocus.core.model.StorageState.Loading }
                is com.flint.peakfocus.core.model.StorageState.Ready)
            assertTrue(DataStoreFocusStateStore(disk).state.first { it !is com.flint.peakfocus.core.model.StorageState.Loading }
                is com.flint.peakfocus.core.model.StorageState.Ready)
            assertTrue(DataStoreLimitsStore(disk).openLimitsState.first { it !is com.flint.peakfocus.core.model.StorageState.Loading }
                is com.flint.peakfocus.core.model.StorageState.Ready)
            assertTrue(DataStoreTimeLimitUsageStore(disk).state.first { it !is com.flint.peakfocus.core.model.StorageState.Loading }
                is com.flint.peakfocus.core.model.StorageState.Unavailable)
            disk.edit { it[stringPreferencesKey("unrelated")] = "still writable" }
            assertEquals("still writable", disk.data.first()[stringPreferencesKey("unrelated")])
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
            directory.deleteRecursively()
        }
    }

    @Test fun PC_56_PC_70_literalB34422eAndCurrentIntegrationStoresAreAddOnlyAcrossTwoMigrations() = runBlocking {
        val old = preferencesOf(stringPreferencesKey("block_rules_v1") to "",
            stringPreferencesKey("app_groups_v1") to "",
            stringPreferencesKey("time_limits_v1") to "app~30",
            stringPreferencesKey("open_limits_v1") to "app~3~HARDER",
            stringPreferencesKey("default_break_level_v1") to "HARDER",
            longPreferencesKey("open_counts_day_v1") to 19_723L,
            stringPreferencesKey("open_counts_v1") to "app~2")
        val values = MutableStateFlow<Preferences>(old)
        val disk = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(values.value).also { values.value = it }
        }
        DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" }).reconcile(at)
        val current = values.value
        for (source in listOf(old, current)) {
            val migration = TimeLimitBootstrapMigration({ at }, { "UTC" }, { setOf("legacy-only") })
            val first = migration.migrate(source)
            val second = migration.migrate(first)
            assertEquals(source.asMap(), first.asMap().filterKeys { it != TIME_BOOTSTRAP_CONTEXT })
            assertEquals(first, second)
        }
    }

    @Test fun PC_54_PC_56_captureBeforeAClockJumpOrRebootDoesNotTurnLaterOsZeroIntoKnownUnusedQuota() = runBlocking {
        for (boot in listOf("boot", "next-boot")) {
            val migrated = TimeLimitBootstrapMigration({ at }, { "UTC" }, { setOf("legacy-only") })
                .migrate(preferencesOf(PrefsKeys.TIME_LIMITS to "canonical~1~HARDCORE"))
            val values = MutableStateFlow(migrated)
            val disk = object : DataStore<Preferences> {
                override val data: Flow<Preferences> = values
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(values.value).also { values.value = it }
            }
            val later = AccessClockStamp(at.wallEpochMs + 172_800_000, at.elapsedRealtimeMs + 1_000, boot)
            val reader = DataStoreTimeLimitUsageStore(disk, deviceZone = { "Pacific/Auckland" })
            for (target in listOf("canonical", "legacy-only")) {
                val ticket = reader.measurementContext(target, later)
                val result = reader.record(ticket, 0, later)
                assertEquals("UTC", result.state.zoneId)
                assertTrue(result.state.accountingUnknownUntilReset)
                assertNull(com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting.remainingMs(result.state, 1))
                assertTrue(result.state.reset.remainingToResetMs > 86_000_000)
            }
        }
    }
}
