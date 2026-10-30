package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/** Original editor-domain bytes, including malformed text, retained independently of decoding. */
internal data class EditorMigrationArchive(val values: Map<String, String?>)

/** Archive-only format; this codec neither changes configuration nor grants migration rights. */
internal object EditorMigrationArchiveCodec {
    private val keys = listOf(
        PrefsKeys.BLOCK_RULES, PrefsKeys.APP_GROUPS, PrefsKeys.TIME_LIMITS, PrefsKeys.OPEN_LIMITS,
        PrefsKeys.DEFAULT_BREAK_LEVEL, PrefsKeys.BREAK_SESSION, HARDER_WAIT_KEY,
        stringPreferencesKey("editor_migration_rules_v1"),
        stringPreferencesKey("editor_migration_time_limits_v1"),
    ).sortedBy { it.name }
    private val names = keys.map { it.name }.toSet()

    fun capture(prefs: Preferences): EditorMigrationArchive =
        EditorMigrationArchive(keys.associate { it.name to StrictPrefsCodec.string(prefs, it) })

    fun encode(value: EditorMigrationArchive): String {
        wireRequire(value.values.keys == names)
        return buildList {
            add("AM|1")
            keys.forEach { key ->
                val raw = value.values[key.name]
                add("K|${wireAtom(key.name)}|${if (raw == null) "0" else "1"}|${PrefsCodec.escape(raw.orEmpty())}")
            }
        }.joinToString("\n").also { decode(it) }
    }

    fun decode(raw: String): EditorMigrationArchive {
        val values = linkedMapOf<String, String?>()
        wireRows(raw, "AM").forEach { fields ->
            if (fields.firstOrNull() != "K") wireFuture()
            wireRequire(fields.size == 4)
            val key = readWireAtom(fields[1])
            if (key !in names) wireFuture()
            wireRequire(!values.containsKey(key))
            val original = PrefsCodec.unescape(fields[3])
            wireRequire(PrefsCodec.escape(original) == fields[3])
            values[key] = when (fields[2]) {
                "0" -> { wireRequire(original.isEmpty()); null }
                "1" -> original
                else -> wireCorrupt()
            }
        }
        wireRequire(values.keys == names)
        return EditorMigrationArchive(values.toMap())
    }
}
