package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** A host is a DNS host, not a URL. Keep its www label; a selected subdomain is not its parent. */
data class ManagedForeground(val packageName: String, val host: String? = null)

/**
 * No display reduction has happened: every applicable managed cause survives. Its identity lease
 * must still match the live account before a host uses this result. Personal exemptions, including
 * emergency passes, cannot authorize this candidate. Stored unblocksAllowed is not a grant.
 */
data class ManagedBlockCause(
    val identity: ManagedIdentity,
    val scope: ManagedCauseScope,
    val target: AccessTarget,
    val ruleName: String,
    val unblocksAllowed: Boolean,
) {
    val ordinaryAccessPolicy: OrdinaryAccessPolicy get() = OrdinaryAccessPolicy.Forbidden
    val personalEmergencyPassAllowed: Boolean get() = false

    /** Reuses the authority-separated access vocabulary without activating an access writer. */
    val accessScope: AccessCauseScope
        get() = AccessCauseScope(
            AccessCounterKey(
                AccessRuleKey(scope.authority, "managed", scope.ruleId),
                "${scope.occurrence.startsAtEpochMs}/${scope.occurrence.endsAtEpochMs}",
            ),
            scope.ruleRevision.toString(),
        )
}

enum class ManagedUnavailableReason { IDENTITY_MISMATCH, UNSUPPORTED_SCHEMA, INVALID_SNAPSHOT, UNCONFIRMED_CLOCK, INVALID_TIME }

sealed interface ManagedEvaluation {
    data class Ready(
        val causes: List<ManagedBlockCause>,
        /** Earliest future boundary among enabled rules, including rules not currently active. */
        val nextRecheckEpochMs: Long?,
        /** An active domain rule could not be examined; it has NOT become an app-wide block. */
        val domainObservationUnavailable: Boolean,
    ) : ManagedEvaluation

    /** An unavailable evaluation is not an authoritative empty snapshot or an Allow verdict. */
    data class Unavailable(val reason: ManagedUnavailableReason) : ManagedEvaluation
}

/**
 * Pure managed evaluator. No network, persistence, personal grants, UI, or device clock reads.
 *
 * Recurrence policy is explicitly Flint's policy (not claimed Opal behavior): ISO weekdays name
 * the local start date; end < start means next local date; intervals are half-open. In a repeated
 * hour, start uses the earlier instant and end the later instant. A nonexistent endpoint clamps to
 * the transition's first valid instant. Empty resolved occurrences, including a skipped date, are
 * omitted. Device timezone changes cannot alter a rule's explicit IANA timezone.
 *
 * Only complete, repository-accepted snapshots belong here. Defensive validation rejects the
 * entire input on inconsistent authority or invalid rules; no partial rule list is returned.
 * This policy is not wired to either detector yet. Android <26 requires core-library desugaring.
 */
class ManagedRulePolicy {
    fun evaluate(
        snapshot: ManagedSnapshot,
        currentIdentity: ManagedIdentity,
        foreground: ManagedForeground,
        selfPackage: String,
        clock: ManagedClock,
        neverBlockPackages: Set<String> = emptySet(),
    ): ManagedEvaluation {
        if (snapshot.identity != currentIdentity) return unavailable(ManagedUnavailableReason.IDENTITY_MISMATCH)
        if (snapshot.schemaVersion != 1) return unavailable(ManagedUnavailableReason.UNSUPPORTED_SCHEMA)
        val rules = validate(snapshot) ?: return unavailable(ManagedUnavailableReason.INVALID_SNAPSHOT)
        if (clock !is ManagedClock.Confirmed) return unavailable(ManagedUnavailableReason.UNCONFIRMED_CLOCK)
        if (foreground.packageName.isBlank() || foreground.packageName == selfPackage ||
            foreground.packageName in SYSTEM_SURFACE_PACKAGES || foreground.packageName in neverBlockPackages
        ) return ManagedEvaluation.Ready(emptyList(), null, false)

        return try {
            evaluateReady(snapshot, rules, foreground, clock.nowEpochMs)
        } catch (_: DateTimeException) {
            unavailable(ManagedUnavailableReason.INVALID_TIME)
        } catch (_: ArithmeticException) {
            unavailable(ManagedUnavailableReason.INVALID_TIME)
        }
    }

    private fun evaluateReady(
        snapshot: ManagedSnapshot,
        rules: List<Pair<ManagedRule, ZoneId>>,
        foreground: ManagedForeground,
        now: Long,
    ): ManagedEvaluation.Ready {
        val host = foreground.host?.lowercase()?.removeSuffix(".")?.takeIf(::validHost)
        val memberships = snapshot.memberships.associateBy { it.groupId }
        val causes = mutableListOf<ManagedBlockCause>()
        var next: Long? = null
        var domainUnavailable = false
        for ((rule, zone) in rules) {
            if (!rule.enabled) continue
            val occurrences = when (val schedule = rule.schedule) {
                is ManagedSchedule.Session -> listOf(ManagedOccurrence(schedule.startsAtEpochMs, schedule.endsAtEpochMs))
                is ManagedSchedule.Recurring -> recurringOccurrences(schedule, zone, now)
            }
            for (occurrence in occurrences) {
                for (boundary in listOf(occurrence.startsAtEpochMs, occurrence.endsAtEpochMs)) {
                    if (boundary > now && (next == null || boundary < next)) next = boundary
                }
                if (now < occurrence.startsAtEpochMs || now >= occurrence.endsAtEpochMs) continue
                val target = when (val targets = rule.targets) {
                    ManagedTargets.AllApps -> AccessTarget.App(foreground.packageName)
                    is ManagedTargets.Selection -> when {
                        foreground.packageName in targets.packages -> AccessTarget.App(foreground.packageName)
                        host != null && targets.domains.any { host == it || host.endsWith(".$it") } ->
                            AccessTarget.Website(foreground.packageName, host)
                        else -> {
                            if (host == null && targets.domains.isNotEmpty()) domainUnavailable = true
                            null
                        }
                    }
                } ?: continue
                val membership = memberships.getValue(rule.targetGroupId)
                causes += ManagedBlockCause(
                    snapshot.identity,
                    ManagedCauseScope(
                        RuleAuthority.Managed(
                            snapshot.identity.projectId, snapshot.identity.accountUid,
                            membership.groupId, membership.membershipId,
                        ),
                        rule.id, rule.revision, occurrence,
                    ),
                    target, rule.name, rule.unblocksAllowed,
                )
            }
        }
        return ManagedEvaluation.Ready(causes, next, domainUnavailable)
    }

    private fun recurringOccurrences(schedule: ManagedSchedule.Recurring, zone: ZoneId, now: Long): List<ManagedOccurrence> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // Include the previous start dates for overnight/offset changes and two future weeks:
        // a wholly skipped weekly occurrence must still arm the next valid week's start.
        return (-2L..14L).mapNotNull { offset ->
            val startDate = today.plusDays(offset)
            if (startDate.dayOfWeek.value !in schedule.isoWeekdays) return@mapNotNull null
            val endDate = if (schedule.endMinute < schedule.startMinute) startDate.plusDays(1) else startDate
            val start = endpoint(startDate, schedule.startMinute, zone, start = true)
            val end = endpoint(endDate, schedule.endMinute, zone, start = false)
            if (end <= start) null else ManagedOccurrence(start, end)
        }.distinct()
    }

    private fun endpoint(date: LocalDate, minute: Int, zone: ZoneId, start: Boolean): Long {
        val local = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60))
        val offsets = zone.rules.getValidOffsets(local)
        val instant = if (offsets.isEmpty()) {
            // Clamp to the start of valid time, rather than silently shifting by the gap length.
            zone.rules.getTransition(local).instant
        } else {
            val instants = offsets.map(local::toInstant)
            if (start) instants.minOrNull()!! else instants.maxOrNull()!!
        }
        return instant.toEpochMilli()
    }

    private fun validate(snapshot: ManagedSnapshot): List<Pair<ManagedRule, ZoneId>>? {
        val identity = snapshot.identity
        if (!atom(identity.projectId) || !atom(identity.accountUid) || identity.sessionGeneration < 0 || snapshot.revision < 0 ||
            snapshot.memberships.size > 20 || snapshot.rules.size > 200 || snapshot.tombstones.size > 1_280
        ) return null
        if (snapshot.memberships.any { !atom(it.groupId) || !atom(it.membershipId) } ||
            snapshot.memberships.map { it.groupId }.toSet().size != snapshot.memberships.size
        ) return null
        val memberships = snapshot.memberships.associateBy { it.groupId }
        val ids = snapshot.rules.map { it.id }
        if (ids.toSet().size != ids.size || snapshot.rules.groupingBy { it.targetGroupId }.eachCount().values.any { it > 10 }) return null
        val tombstoneIds = snapshot.tombstones.map { it.id }
        if (tombstoneIds.toSet().size != tombstoneIds.size || ids.any { it in tombstoneIds } ||
            snapshot.tombstones.groupingBy { it.targetGroupId }.eachCount().values.any { it > 64 } ||
            snapshot.tombstones.any {
                !atom(it.id) || it.revision !in 1..snapshot.revision || memberships[it.targetGroupId]?.role != ManagedRole.MEMBER
            }
        ) return null
        val resolved = mutableListOf<Pair<ManagedRule, ZoneId>>()
        for (rule in snapshot.rules) {
            val ruleScope = rule.scope
            if (!atom(rule.id) || rule.revision !in 1..snapshot.revision || memberships[rule.targetGroupId]?.role != ManagedRole.MEMBER ||
                rule.name.isBlank() || rule.name.length > 80 || rule.name != rule.name.trim() || rule.name.any(::control) ||
                (ruleScope is ManagedRuleScope.Member && ruleScope.userId != identity.accountUid)
            ) return null
            if (!validSchedule(rule.schedule) || !validTargets(rule.targets)) return null
            if (rule.zoneId.length > 64 || (rule.zoneId != "UTC" && !ZONE_NAME.matches(rule.zoneId))) return null
            val zone = try { ZoneId.of(rule.zoneId) } catch (_: DateTimeException) { return null }
            resolved += rule to zone
        }
        return resolved
    }

    private fun validSchedule(schedule: ManagedSchedule): Boolean = when (schedule) {
        is ManagedSchedule.Session -> try {
            Math.subtractExact(schedule.endsAtEpochMs, schedule.startsAtEpochMs) in 1..MAX_SESSION_MILLIS
        } catch (_: ArithmeticException) { false }
        is ManagedSchedule.Recurring -> schedule.startMinute in 0..1439 && schedule.endMinute in 0..1439 &&
            schedule.startMinute != schedule.endMinute && schedule.isoWeekdays.isNotEmpty() && schedule.isoWeekdays.all { it in 1..7 }
    }

    private fun validTargets(targets: ManagedTargets): Boolean = when (targets) {
        ManagedTargets.AllApps -> true
        is ManagedTargets.Selection -> targets.packages.size <= 20 && targets.domains.size <= 20 &&
            (targets.packages.isNotEmpty() || targets.domains.isNotEmpty()) &&
            targets.packages.all { it.length <= 255 && PACKAGE_NAME.matches(it) } && targets.domains.all(::validHost)
    }

    private fun validHost(host: String): Boolean = host.length <= 253 && host == host.lowercase() && '.' in host &&
        !host.all { it.isDigit() || it == '.' } && host.split('.').all { HOST_LABEL.matches(it) }

    private fun atom(value: String): Boolean = value.isNotBlank() && value.length <= 2_048 && value.none(::control)
    private fun control(char: Char): Boolean = char <= '\u001f' || char == '\u007f'
    private fun unavailable(reason: ManagedUnavailableReason) = ManagedEvaluation.Unavailable(reason)

    private companion object {
        const val MAX_SESSION_MILLIS = 7 * 24 * 60 * 60 * 1_000L
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        val ZONE_NAME = Regex("[A-Za-z_]+(?:/[A-Za-z0-9_+\\-]+)+")
    }
}
