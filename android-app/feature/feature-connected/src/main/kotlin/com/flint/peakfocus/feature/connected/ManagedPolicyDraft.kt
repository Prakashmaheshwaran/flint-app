package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.model.ManagedRule
import com.flint.peakfocus.core.model.ManagedRuleScope
import com.flint.peakfocus.core.model.ManagedSchedule
import com.flint.peakfocus.core.model.ManagedTargets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal enum class ManagedPolicyKind { SESSION, RECURRING }

/** An editor snapshot. [expectedRevision] never follows background refreshes. */
internal data class ManagedPolicyDraft(
    val id: String? = null,
    val expectedRevision: Long? = null,
    val name: String = "",
    val enabled: Boolean = true,
    val kind: ManagedPolicyKind = ManagedPolicyKind.RECURRING,
    val memberUserId: String? = null,
    val zoneId: String = "UTC",
    val allApps: Boolean = false,
    val packagesText: String = "",
    val domainsText: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val startTime: String = "09:00",
    val endTime: String = "15:00",
    val isoWeekdays: Set<Int> = setOf(1, 2, 3, 4, 5),
    val unblocksAllowed: Boolean = false,
)

internal fun newManagedPolicyDraft(nowEpochMs: Long): ManagedPolicyDraft {
    val start = Instant.ofEpochMilli(nowEpochMs).atOffset(ZoneOffset.UTC)
    val end = start.plusHours(1)
    return ManagedPolicyDraft(
        startDate = start.toLocalDate().toString(), endDate = end.toLocalDate().toString(),
        startTime = policyTime(start.hour * 60 + start.minute),
        endTime = policyTime(end.hour * 60 + end.minute),
    )
}

internal fun ManagedRule.toPolicyDraft(): ManagedPolicyDraft {
    val selected = targets as? ManagedTargets.Selection
    val base = ManagedPolicyDraft(
        id = id, expectedRevision = revision, name = name, enabled = enabled,
        memberUserId = (scope as? ManagedRuleScope.Member)?.userId, zoneId = zoneId,
        allApps = targets is ManagedTargets.AllApps,
        packagesText = selected?.packages?.sorted()?.joinToString("\n").orEmpty(),
        domainsText = selected?.domains?.sorted()?.joinToString("\n").orEmpty(),
        unblocksAllowed = unblocksAllowed,
    )
    return when (val value = schedule) {
        is ManagedSchedule.Recurring -> base.copy(
            kind = ManagedPolicyKind.RECURRING,
            startTime = policyTime(value.startMinute), endTime = policyTime(value.endMinute),
            isoWeekdays = value.isoWeekdays,
        )
        is ManagedSchedule.Session -> {
            val start = Instant.ofEpochMilli(value.startsAtEpochMs).atOffset(ZoneOffset.UTC)
            val end = Instant.ofEpochMilli(value.endsAtEpochMs).atOffset(ZoneOffset.UTC)
            base.copy(
                kind = ManagedPolicyKind.SESSION,
                startDate = start.toLocalDate().toString(), endDate = end.toLocalDate().toString(),
                // Preserve sub-minute precision from server-authored records. The editor may
                // show seconds/milliseconds even though new entries normally use HH:mm.
                startTime = policyInstantTime(start.toLocalTime()),
                endTime = policyInstantTime(end.toLocalTime()),
            )
        }
    }
}

private fun policyInstantTime(time: LocalTime): String =
    if (time.nano != 0) time.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
    else if (time.second != 0) time.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    else time.format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun policyTime(minute: Int): String =
    "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"

internal fun managedPolicyInput(
    draft: ManagedPolicyDraft,
    members: List<ConnectedMember>,
    ownerId: String,
): ManagedRuleInput {
    val name = ConnectedInput.name(draft.name)
    require(draft.zoneId.length <= 64 && (draft.zoneId == "UTC" ||
        Regex("[A-Za-z_]+(?:/[A-Za-z0-9_+\\-]+)+").matches(draft.zoneId))) {
        "Choose an IANA time zone such as America/New_York or UTC."
    }
    try { ZoneId.of(draft.zoneId) } catch (_: Exception) {
        throw IllegalArgumentException("Choose a recognized IANA time zone.")
    }
    val scope = draft.memberUserId?.let { uid ->
        require(uid != ownerId && members.any { it.userId == uid && it.role == "member" }) {
            "Choose a current non-owner member, or apply the policy to the group."
        }
        ManagedRuleScope.Member(uid)
    } ?: ManagedRuleScope.Group
    val targets = if (draft.allApps) ManagedTargets.AllApps else {
        val packages = policyTargetLines(draft.packagesText)
        val domains = policyTargetLines(draft.domainsText)
        require(packages.size <= 20 && domains.size <= 20) { "Use at most 20 packages and 20 websites." }
        require(packages.distinct().size == packages.size && domains.distinct().size == domains.size) {
            "Remove duplicate packages or websites."
        }
        require(packages.isNotEmpty() || domains.isNotEmpty()) { "Add at least one package or website." }
        require(packages.all { it.length <= 255 && Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+").matches(it) }) {
            "Use Android package names such as com.example.app, one per line."
        }
        require(domains.all(::validPolicyDomain)) { "Use lowercase website hostnames without paths, ports or wildcards." }
        ManagedTargets.Selection(packages.toSet(), domains.toSet())
    }
    val schedule = if (draft.kind == ManagedPolicyKind.RECURRING) {
        val start = parsePolicyMinute(draft.startTime)
        val end = parsePolicyMinute(draft.endTime)
        require(start != end) { "Recurring start and end times must be different." }
        require(draft.isoWeekdays.isNotEmpty() && draft.isoWeekdays.all { it in 1..7 }) { "Choose at least one weekday." }
        ManagedSchedule.Recurring(start, end, draft.isoWeekdays.toSet())
    } else {
        val start = parsePolicyInstant(draft.startDate, draft.startTime)
        val end = parsePolicyInstant(draft.endDate, draft.endTime)
        require(end > start && end - start <= 7 * 86_400_000L) { "A session must end after its start and last at most seven days." }
        ManagedSchedule.Session(start, end)
    }
    return ManagedRuleInput(name, draft.zoneId, draft.enabled, scope, schedule, targets, draft.unblocksAllowed)
}

private fun policyTargetLines(value: String): List<String> =
    value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

private fun validPolicyDomain(value: String): Boolean =
    value.length <= 253 && value == value.lowercase(java.util.Locale.ROOT) && '.' in value &&
        !Regex("[0-9.]+").matches(value) && value.split('.').all {
            Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?").matches(it)
        }

private fun parsePolicyMinute(value: String): Int {
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)) { "Use 24-hour times in HH:mm format." }
    return value.substring(0, 2).toInt() * 60 + value.substring(3, 5).toInt()
}

private fun parsePolicyInstant(date: String, time: String): Long {
    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date)) { "Use dates in YYYY-MM-DD format." }
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\\.[0-9]{3})?)?").matches(time)) {
        "Use UTC time HH:mm, optionally with seconds and milliseconds."
    }
    return try {
        LocalDate.parse(date).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: Exception) {
        throw IllegalArgumentException("Choose a valid calendar date and time.")
    }
}
