package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.*
import com.flint.peakfocus.core.model.StorageFailure
import org.junit.Assert.*
import org.junit.Test

class EditorMigrationArchiveTest {
    // Literal bytes are independent of the encoder, including optional absence and present-empty.
    private val unused = """
        AM|1
        K|app_groups_v1|0|
        K|block_rules_v1|0|
        K|break_session_v1|0|
        K|default_break_level_v1|0|
        K|editor_migration_rules_v1|0|
        K|editor_migration_time_limits_v1|0|
        K|harder_wait_ledger_v1|0|
        K|open_limits_v1|0|
        K|time_limits_v1|0|
    """.trimIndent()

    private fun failure(raw: String, reason: StorageFailure = StorageFailure.CORRUPT) {
        try { EditorMigrationArchiveCodec.decode(raw); fail("Invalid archive accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(reason, failure.reason) }
    }

    @Test fun `PC-70 literal archive distinguishes absent keys from explicit empty configuration`() {
        val literal = unused.replace("K|block_rules_v1|0|", "K|block_rules_v1|1|")
        val record = EditorMigrationArchiveCodec.decode(literal)
        assertNull(record.values.getValue("app_groups_v1"))
        assertEquals("", record.values.getValue("block_rules_v1"))
        assertEquals(literal, EditorMigrationArchiveCodec.encode(record))
        assertEquals(unused, EditorMigrationArchiveCodec.encode(EditorMigrationArchiveCodec.capture(emptyPreferences())))
    }

    @Test fun `PC-70 literal malformed originals and delimiters survive without permissive decoding`() {
        val literal = unused
            .replace("K|block_rules_v1|0|", "K|block_rules_v1|1|broken%7Crule%0Aduplicate%7Cid%7C%25literal")
            .replace("K|app_groups_v1|0|", "K|app_groups_v1|1|saved%7CGroup%7Capp%7EName%2Cother")
            .replace("K|time_limits_v1|0|", "K|time_limits_v1|1|app%7E30%7ENOT_A_TIER")
        val record = EditorMigrationArchiveCodec.decode(literal)
        assertEquals("broken|rule\nduplicate|id|%literal", record.values.getValue("block_rules_v1"))
        assertEquals("app~30~NOT_A_TIER", record.values.getValue("time_limits_v1"))
        assertEquals(literal, EditorMigrationArchiveCodec.encode(record))
    }

    @Test fun `PC-70 capture retains groups Open limits default count pending and previous editor archives`() {
        val prefs = preferencesOf(PrefsKeys.BLOCK_RULES to "literal ten-field rule", PrefsKeys.APP_GROUPS to "saved group",
            PrefsKeys.TIME_LIMITS to "app~30", PrefsKeys.OPEN_LIMITS to "app~2~HARDER",
            PrefsKeys.DEFAULT_BREAK_LEVEL to "HARDCORE", PrefsKeys.BREAK_SESSION to "4~100~300",
            HARDER_WAIT_KEY to "H|1\nC|rule%3Awork|occurrence|4",
            stringPreferencesKey("editor_migration_rules_v1") to "older original rules",
            stringPreferencesKey("editor_migration_time_limits_v1") to "older original times",
            stringPreferencesKey("fictional_pin_fixture") to "fictional protected bytes",
            stringPreferencesKey("fictional_history_fixture") to "fictional retained history")
        val before = prefs.asMap().toMap()
        val archive = EditorMigrationArchiveCodec.capture(prefs)
        assertEquals(9, archive.values.size)
        for ((key, value) in before) if (key.name in archive.values) assertEquals(value, archive.values.getValue(key.name))
        assertFalse(archive.values.containsKey("fictional_pin_fixture"))
        assertFalse(archive.values.containsKey("fictional_history_fixture"))
        assertEquals(archive, EditorMigrationArchiveCodec.decode(EditorMigrationArchiveCodec.encode(archive)))
        assertEquals(before, prefs.asMap())
    }

    @Test fun `PC-71 every missing or duplicated archive row is unavailable`() {
        for (row in unused.lines().drop(1)) {
            failure(unused.lines().filterNot { it == row }.joinToString("\n"))
            failure("$unused\n$row")
        }
    }

    @Test fun `PC-71 future archive version keys and row kinds are never downgraded`() {
        failure(unused.replace("AM|1", "AM|2"), StorageFailure.UNSUPPORTED_VERSION)
        failure("$unused\nK|future_input|1|original", StorageFailure.UNSUPPORTED_VERSION)
        failure("$unused\nF|unknown", StorageFailure.UNSUPPORTED_VERSION)
        assertEquals(unused, EditorMigrationArchiveCodec.encode(EditorMigrationArchiveCodec.decode(unused)))
    }

    @Test fun `PC-71 corrupt presence or noncanonical escapes never erase an original`() {
        for (replacement in listOf("K|block_rules_v1|0|original", "K|block_rules_v1|true|", "K|block_rules_v1|1|%7c", "K|block_rules_v1|1"))
            failure(unused.replace("K|block_rules_v1|0|", replacement))
    }

    @Test fun `PC-71 wrong typed source stays intact and cannot masquerade as optional absence`() {
        val prefs = preferencesOf(intPreferencesKey("block_rules_v1") to 7)
        val before = prefs.asMap().toMap()
        try { EditorMigrationArchiveCodec.capture(prefs); fail("Wrong source type accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
        assertEquals(before, prefs.asMap())
    }

    @Test fun `PC-71 incomplete caller archive cannot publish a partial initialized record`() {
        try { EditorMigrationArchiveCodec.encode(EditorMigrationArchive(mapOf("block_rules_v1" to "original"))); fail("Partial archive accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
    }
}
