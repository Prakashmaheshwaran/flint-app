package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.StorageFailure
import kotlinx.coroutines.runBlocking
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
            try {
                TimeLimitBootstrapMigration({ at }, { "UTC" }, { targets }).migrate(original)
                fail("invalid target snapshot accepted")
            } catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
            assertTrue(original.asMap().isEmpty())
        }
        val migration = TimeLimitBootstrapMigration({ at }, { "UTC" }, { emptySet() })
        for (prefs in listOf(preferencesOf(TIME_USAGE_SCHEMA to 1L),
            preferencesOf(TIME_LIMIT_ZONE_SCHEMA to 1L),
            preferencesOf(TIME_BOOTSTRAP_CONTEXT to "B|2"),
            preferencesOf(TIME_BOOTSTRAP_CONTEXT to "B|1\nC|UTC|0|0|boot"))) {
            try { migration.shouldMigrate(prefs); fail("damaged established snapshot repaired") }
            catch (_: StorageUnavailableException) { }
        }
    }

    @Test fun PC_70_targetNamesAreStructuralAndBoundedRatherThanSplitByLabelText() = runBlocking {
        val targets = setOf("app|with~wire\\text", "app\nline", "ordinary")
        val migrated = TimeLimitBootstrapMigration({ at }, { "America/New_York" }, { targets }).migrate(emptyPreferences())
        assertEquals(targets, readTimeLimitBootstrapContext(migrated)!!.legacyTargets)
    }
}
