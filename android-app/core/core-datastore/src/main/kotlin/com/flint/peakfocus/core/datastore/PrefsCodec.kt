package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit

/**
 * String codec for the typed models kept in Preferences DataStore.
 *
 * Why hand-rolled: the version catalog (hot file, owned by A-SCAFFOLD) has no serialization
 * library, so we encode to a small line format instead of adding one. Layers: records are
 * separated by `\n`, fields by `|`, list items by `,`, item sub-fields by `~`; every
 * user-supplied atom is %-escaped so names/domains may contain any delimiter. Decoding is
 * defensive: malformed records are dropped, never thrown on.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM.
 */
internal object PrefsCodec {

    private const val FIELD = "|"
    private const val ITEM = ","
    private const val SUB = "~"
    private const val RECORD = "\n"

    /** Sentinel for "no schedule" in the start/end minute fields. */
    private const val NO_SCHEDULE = -1

    /** Sentinel for "no expiry" (permanent rule) in the expiresAt field. */
    private const val NO_EXPIRY = -1L

    // MARK: atoms

    /** Escape one atom. `%` must be first so later codes never double-encode. */
    fun escape(raw: String): String = raw
        .replace("%", "%25")
        .replace(FIELD, "%7C")
        .replace(ITEM, "%2C")
        .replace(SUB, "%7E")
        .replace(RECORD, "%0A")

    /** Inverse of [escape]. `%25` must be last so decoded `%` can't spawn new codes. */
    fun unescape(encoded: String): String = encoded
        .replace("%0A", RECORD)
        .replace("%7E", SUB)
        .replace("%2C", ITEM)
        .replace("%7C", FIELD)
        .replace("%25", "%")

    // MARK: BlockRule — id|name|enabled|breakLevel|allowListMode|apps|domains|days|start|end[|expiresAt]
    // (expiresAt appended later for one-shot sessions; decode accepts 10-field legacy lines)

    fun encodeRules(rules: List<BlockRule>): String =
        rules.joinToString(RECORD) { encodeRule(it) }

    fun decodeRules(encoded: String): List<BlockRule> =
        encoded.split(RECORD).filter { it.isNotBlank() }.mapNotNull { decodeRule(it) }

    private fun encodeRule(rule: BlockRule): String {
        val apps = rule.targets.apps.joinToString(ITEM) { app ->
            escape(app.packageName) + SUB + escape(app.label.orEmpty())
        }
        val domains = rule.targets.domains.joinToString(ITEM) { escape(it.domain) }
        val days = (rule.schedule?.daysOfWeek ?: emptySet()).joinToString(ITEM)
        return listOf(
            escape(rule.id),
            escape(rule.name),
            rule.enabled.toString(),
            rule.breakLevel.name,
            rule.targets.allowListMode.toString(),
            apps,
            domains,
            days,
            (rule.schedule?.startMinuteOfDay ?: NO_SCHEDULE).toString(),
            (rule.schedule?.endMinuteOfDay ?: NO_SCHEDULE).toString(),
            (rule.expiresAtEpochMs ?: NO_EXPIRY).toString(),
        ).joinToString(FIELD)
    }

    private fun decodeRule(line: String): BlockRule? {
        val f = line.split(FIELD)
        if (f.size != 10 && f.size != 11) return null
        val id = unescape(f[0])
        if (id.isEmpty()) return null

        val apps = f[5].split(ITEM).filter { it.isNotEmpty() }.map { item ->
            val sub = item.split(SUB)
            AppRef(
                packageName = unescape(sub[0]),
                label = sub.getOrNull(1)?.let(::unescape)?.takeIf { it.isNotEmpty() },
            )
        }.toSet()
        val domains = f[6].split(ITEM).filter { it.isNotEmpty() }
            .map { DomainRef(unescape(it)) }.toSet()

        val start = f[8].toIntOrNull() ?: NO_SCHEDULE
        val end = f[9].toIntOrNull() ?: NO_SCHEDULE
        val schedule = if (start == NO_SCHEDULE) {
            null
        } else {
            Schedule(
                daysOfWeek = f[7].split(ITEM).mapNotNull { it.toIntOrNull() }.toSet(),
                startMinuteOfDay = start,
                endMinuteOfDay = end,
            )
        }

        return BlockRule(
            id = id,
            name = unescape(f[1]),
            targets = BlockTargets(
                apps = apps,
                domains = domains,
                allowListMode = f[4].toBooleanStrictOrNull() ?: false,
            ),
            schedule = schedule,
            breakLevel = decodeBreakLevel(f[3]),
            enabled = f[2].toBooleanStrictOrNull() ?: true,
            expiresAtEpochMs = f.getOrNull(10)?.toLongOrNull()?.takeIf { it != NO_EXPIRY },
        )
    }

    // MARK: AppGroup — id|name|apps|domains (same item encodings as rules)

    fun encodeGroups(groups: List<AppGroup>): String =
        groups.joinToString(RECORD) { group ->
            val apps = group.apps.joinToString(ITEM) { app ->
                escape(app.packageName) + SUB + escape(app.label.orEmpty())
            }
            val domains = group.domains.joinToString(ITEM) { escape(it.domain) }
            listOf(escape(group.id), escape(group.name), apps, domains).joinToString(FIELD)
        }

    fun decodeGroups(encoded: String): List<AppGroup> =
        encoded.split(RECORD).filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split(FIELD)
            if (f.size != 4) return@mapNotNull null
            val id = unescape(f[0])
            if (id.isEmpty()) return@mapNotNull null
            val apps = f[2].split(ITEM).filter { it.isNotEmpty() }.map { item ->
                val sub = item.split(SUB)
                AppRef(
                    packageName = unescape(sub[0]),
                    label = sub.getOrNull(1)?.let(::unescape)?.takeIf { it.isNotEmpty() },
                )
            }.toSet()
            val domains = f[3].split(ITEM).filter { it.isNotEmpty() }
                .map { DomainRef(unescape(it)) }.toSet()
            AppGroup(id = id, name = unescape(f[1]), apps = apps, domains = domains)
        }

    // MARK: limits — one record per package

    /** `pkg~minutes` per line. */
    fun encodeTimeLimits(limits: List<TimeLimit>): String =
        limits.joinToString(RECORD) { escape(it.packageName) + SUB + it.dailyMinutes }

    fun decodeTimeLimits(encoded: String): List<TimeLimit> =
        encoded.split(RECORD).filter { it.isNotBlank() }.mapNotNull { line ->
            val sub = line.split(SUB)
            if (sub.size != 2) return@mapNotNull null
            val minutes = sub[1].toIntOrNull() ?: return@mapNotNull null
            val pkg = unescape(sub[0])
            if (pkg.isEmpty()) null else TimeLimit(packageName = pkg, dailyMinutes = minutes)
        }

    /** `pkg~opens~breakLevel` per line. */
    fun encodeOpenLimits(limits: List<OpenLimit>): String =
        limits.joinToString(RECORD) {
            escape(it.packageName) + SUB + it.dailyOpens + SUB + it.breakLevel.name
        }

    fun decodeOpenLimits(encoded: String): List<OpenLimit> =
        encoded.split(RECORD).filter { it.isNotBlank() }.mapNotNull { line ->
            val sub = line.split(SUB)
            if (sub.size != 3) return@mapNotNull null
            val opens = sub[1].toIntOrNull() ?: return@mapNotNull null
            val pkg = unescape(sub[0])
            if (pkg.isEmpty()) {
                null
            } else {
                OpenLimit(packageName = pkg, dailyOpens = opens, breakLevel = decodeBreakLevel(sub[2]))
            }
        }

    // MARK: open counts — `pkg~count` per line (the day lives in its own Long preference)

    fun encodeOpenCounts(opensByPackage: Map<String, Int>): String =
        opensByPackage.entries.joinToString(RECORD) { (pkg, count) -> escape(pkg) + SUB + count }

    fun decodeOpenCounts(encoded: String): Map<String, Int> =
        encoded.split(RECORD).filter { it.isNotBlank() }.mapNotNull { line ->
            val sub = line.split(SUB)
            if (sub.size != 2) return@mapNotNull null
            val count = sub[1].toIntOrNull() ?: return@mapNotNull null
            val pkg = unescape(sub[0])
            if (pkg.isEmpty()) null else pkg to count
        }.toMap()

    // MARK: break session — `breaksTaken` or `breaksTaken~requestedAt~effectiveAt`

    fun encodeBreakSession(state: BreakSessionState): String {
        val pending = state.pending ?: return state.breaksTaken.toString()
        return state.breaksTaken.toString() + SUB +
            pending.requestedAtEpochMs + SUB + pending.effectiveAtEpochMs
    }

    fun decodeBreakSession(encoded: String): BreakSessionState {
        val sub = encoded.split(SUB)
        val breaksTaken = sub.getOrNull(0)?.toIntOrNull() ?: return BreakSessionState()
        if (sub.size < 3) return BreakSessionState(breaksTaken = breaksTaken)
        val requestedAt = sub[1].toLongOrNull() ?: return BreakSessionState(breaksTaken = breaksTaken)
        val effectiveAt = sub[2].toLongOrNull() ?: return BreakSessionState(breaksTaken = breaksTaken)
        return BreakSessionState(
            breaksTaken = breaksTaken,
            pending = BreakRequestState(requestedAtEpochMs = requestedAt, effectiveAtEpochMs = effectiveAt),
        )
    }

    // MARK: enums

    fun decodeBreakLevel(name: String?): BreakLevel =
        BreakLevel.entries.firstOrNull { it.name == name } ?: BreakLevel.EASY
}
