package com.flint.peakfocus.core.datastore

import androidx.datastore.preferences.core.Preferences
import com.flint.peakfocus.core.model.*

/**
 * The v1 wire format is unchanged. Unlike the historical lenient decoder, live reads and
 * read/modify/write operations refuse the WHOLE affected value if any record is unknown or
 * malformed. This prevents a later unrelated upsert from erasing undecodable records.
 */
internal object StrictPrefsCodec {
    private fun corrupt(): Nothing = throw StorageUnavailableException(StorageFailure.CORRUPT)
    private fun future(): Nothing = throw StorageUnavailableException(StorageFailure.UNSUPPORTED_VERSION)
    private fun check(valid: Boolean) { if (!valid) corrupt() }

    fun <T> value(prefs: Preferences, key: Preferences.Key<T>, type: Class<T>): T? {
        val raw = prefs.asMap()[key] ?: return null
        if (!type.isInstance(raw)) corrupt()
        @Suppress("UNCHECKED_CAST")
        return raw as T
    }

    fun string(prefs: Preferences, key: Preferences.Key<String>): String? = value(prefs, key, String::class.java)
    fun long(prefs: Preferences, key: Preferences.Key<Long>): Long? {
        val raw = prefs.asMap()[key] ?: return null
        return raw as? Long ?: corrupt()
    }

    private fun rows(encoded: String): List<String> = if (encoded.isEmpty()) emptyList() else
        encoded.split('\n').also { list -> check(list.none { it.isBlank() }) }

    private fun atom(encoded: String, required: Boolean = false) {
        check(!required || encoded.isNotBlank())
        check(PrefsCodec.escape(PrefsCodec.unescape(encoded)) == encoded)
    }

    private fun atoms(encoded: String, separator: Char, validate: (String) -> Unit) {
        if (encoded.isEmpty()) return
        val items = encoded.split(separator)
        check(items.distinct().size == items.size)
        items.forEach { check(it.isNotEmpty()); validate(it) }
    }

    private fun targets(apps: String, domains: String) {
        atoms(apps, ',') { app ->
            val fields = app.split('~')
            check(fields.size in 1..2)
            atom(fields[0], true)
            fields.getOrNull(1)?.let { atom(it) }
        }
        atoms(domains, ',') { atom(it, true) }
    }

    fun level(encoded: String?): BreakLevel {
        if (encoded == null) return BreakLevel.EASY
        return BreakLevel.entries.firstOrNull { it.name == encoded } ?: future()
    }

    fun rules(encoded: String): List<BlockRule> {
        rows(encoded).forEach { row ->
            val f = row.split('|')
            if (f.size > 11) future()
            check(f.size == 10 || f.size == 11)
            atom(f[0], true); atom(f[1])
            check(f[2].toBooleanStrictOrNull() != null && f[4].toBooleanStrictOrNull() != null)
            level(f[3]); targets(f[5], f[6])
            atoms(f[7], ',') { check(it.toIntOrNull() in 1..7) }
            val start = f[8].toIntOrNull() ?: corrupt()
            val end = f[9].toIntOrNull() ?: corrupt()
            check((start == -1 && end == -1 && f[7].isEmpty()) || (start in 0..1439 && end in 0..1439))
            if (f.size == 11) check(f[10].toLongOrNull()?.let { it == -1L || it >= 0 } == true)
        }
        return PrefsCodec.decodeRules(encoded).also { result -> check(result.map { it.id }.distinct().size == result.size) }
    }

    fun groups(encoded: String): List<AppGroup> {
        rows(encoded).forEach { row ->
            val f = row.split('|')
            if (f.size > 4) future()
            check(f.size == 4)
            atom(f[0], true); atom(f[1]); targets(f[2], f[3])
        }
        return PrefsCodec.decodeGroups(encoded).also { check(it.map { g -> g.id }.distinct().size == it.size) }
    }

    private fun limits(encoded: String, size: Int) {
        val packages = rows(encoded).map { row ->
            val f = row.split('~')
            if (f.size > size) future()
            check(f.size == size)
            atom(f[0], true)
            check(f[1].toIntOrNull()?.let { it >= 0 } == true)
            if (size == 3) level(f[2])
            f[0]
        }
        check(packages.distinct().size == packages.size)
    }

    fun timeLimits(encoded: String): List<TimeLimit> { limits(encoded, 2); return PrefsCodec.decodeTimeLimits(encoded) }
    fun openLimits(encoded: String): List<OpenLimit> { limits(encoded, 3); return PrefsCodec.decodeOpenLimits(encoded) }
    fun counts(encoded: String): Map<String, Int> { limits(encoded, 2); return PrefsCodec.decodeOpenCounts(encoded) }

    fun session(encoded: String?): BreakSessionState {
        if (encoded == null) return BreakSessionState()
        val f = encoded.split('~')
        if (f.size > 3) future()
        check(f.size == 1 || f.size == 3)
        check(f[0].toIntOrNull()?.let { it >= 0 } == true)
        if (f.size == 3) {
            val requested = f[1].toLongOrNull() ?: corrupt()
            val effective = f[2].toLongOrNull() ?: corrupt()
            check(requested >= 0 && effective >= requested)
        }
        return PrefsCodec.decodeBreakSession(encoded)
    }
}
