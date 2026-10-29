package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.clockDiscontinuity
import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.ruleCauseKey
import com.flint.peakfocus.core.datastore.timeCauseKey
import com.flint.peakfocus.core.datastore.openCauseKey
import com.flint.peakfocus.core.model.*
import java.util.Calendar
import java.util.TimeZone

/** A read-only evaluation of actual store snapshots, never consulted to grant access. */
internal object ProtectionStatusProjection {
    const val FRESHNESS_MS = 2_000L
    const val TICK_MS = FRESHNESS_MS / 2

    fun evaluate(
        focus: StorageState<FocusSnapshot>,
        rulesReadiness: StorageState<List<BlockRule>>,
        times: StorageState<List<TimeLimit>>,
        opens: StorageState<List<OpenLimit>>,
        editor: StorageState<EditorCommitmentState>,
        effectiveRules: List<BlockRule>,
        floor: SafetyFloorGate.RuntimeResolution,
        pathAConnected: Boolean,
        now: AccessClockStamp,
        legacyZone: String = TimeZone.getDefault().id,
        timeLimitTier: (TimeLimit) -> BreakLevel = { it.breakLevel },
    ): ProtectionStatus {
        val inputs = listOf(focus, rulesReadiness, times, opens, editor)
        val readiness = when {
            inputs.any { it is StorageState.Unavailable } -> ProtectionStatus.Readiness.UNAVAILABLE
            inputs.any { it is StorageState.Loading } -> ProtectionStatus.Readiness.LOADING
            else -> ProtectionStatus.Readiness.READY
        }
        val reasons = linkedSetOf<String>()
        if (!pathAConnected) reasons += "path-a-disconnected"
        if (readiness != ProtectionStatus.Readiness.READY) reasons += "policy-storage-${readiness.name.lowercase()}"
        val checked = floor.checkedAtElapsedMs
        val floorStale = checked == null || now.elapsedRealtimeMs < checked || now.elapsedRealtimeMs - checked >= SafetyFloorGate.DEFAULT_REFRESH_INTERVAL_MS
        if (floor.degraded || checked == null) reasons += "safety-floor-unresolved"
        if (floor.failedSources.isNotEmpty()) reasons += "safety-floor-refresh-failed"
        if (floorStale) reasons += "safety-floor-check-stale"
        // Until the rolling/global pass transaction is activated, the weekly v1 state is
        // not evidence for ADR-012 availability or global recovery. Never advertise it as such.
        reasons += "pass-contract-unavailable"
        val causes = mutableListOf<ProtectionStatus.ActiveCause>()
        val sidecar = (editor as? StorageState.Ready)?.value
        val configured = (rulesReadiness as? StorageState.Ready)?.value.orEmpty()
        val configuredIds = configured.map { it.id }.toSet()
        val currentRules = configured + effectiveRules.filter { it.id !in configuredIds }
        if (readiness == ProtectionStatus.Readiness.READY && sidecar != null) {
            fun add(key: String, ruleId: String?, name: String, kind: ProtectionStatus.CauseKind, tier: BreakLevel) {
                val occurrence = sidecar.causes[key]
                if (occurrence == null) {
                    reasons += "occurrence-state-unavailable"
                    return
                }
                if (occurrence.occurrenceId == "inactive") return
                val interval = occurrence.interval
                val remaining = interval?.remainingMs(now)
                if (remaining == 0L) return
                causes += ProtectionStatus.ActiveCause(ruleId, ProtectionStatus.sanitizeDisplayName(name), kind, tier,
                    remaining, remaining?.let { addBounded(now.elapsedRealtimeMs, it) },
                    remaining?.let { addBounded(now.wallEpochMs, it) },
                    interval?.let { clockDiscontinuity(it.anchor, now) } ?: true, occurrence.occurrenceId)
            }
            currentRules.filter { it.enabled }.forEach { rule ->
                val kind = when {
                    rule.id.startsWith("sleep-") -> ProtectionStatus.CauseKind.SLEEP
                    rule.schedule != null -> ProtectionStatus.CauseKind.SCHEDULE
                    rule.id.startsWith("session-timer-") -> ProtectionStatus.CauseKind.TIMER
                    rule.expiresAtEpochMs != null && !rule.id.startsWith(BlockRule.SESSION_ID_PREFIX) -> ProtectionStatus.CauseKind.SCHEDULE
                    else -> ProtectionStatus.CauseKind.SESSION
                }
                if (rule.id in configuredIds) add(ruleCauseKey(rule.id), rule.id, rule.name, kind, rule.breakLevel)
                else foreignActivity(rule, now, legacyZone)?.let { activity ->
                    causes += ProtectionStatus.ActiveCause(rule.id, ProtectionStatus.sanitizeDisplayName(rule.name), kind, rule.breakLevel,
                        activity.end?.let { (it - now.wallEpochMs).coerceAtLeast(0) },
                        // The foreign legacy source has no durable elapsed occurrence anchor.
                        null, activity.end, true)
                }
            }
            (times as StorageState.Ready).value.forEach { limit ->
                add(timeCauseKey(limit.packageName), null, "Time limit", ProtectionStatus.CauseKind.TIME_LIMIT, timeLimitTier(limit))
            }
            (opens as StorageState.Ready).value.forEach { limit ->
                add(openCauseKey(limit.packageName), null, "Open limit", ProtectionStatus.CauseKind.OPEN_LIMIT, limit.breakLevel)
            }
        }
        val derivedReadiness = if (readiness == ProtectionStatus.Readiness.READY && "occurrence-state-unavailable" in reasons)
            ProtectionStatus.Readiness.UNAVAILABLE else readiness
        val next = if (derivedReadiness == ProtectionStatus.Readiness.READY && sidecar != null)
            nextOccurrence(currentRules, configuredIds, sidecar, now, legacyZone) else null
        val deadline = (causes.mapNotNull { it.logicalEndElapsedRealtimeMs } + listOfNotNull(
            next?.let { addBounded(now.elapsedRealtimeMs, (it.startWallEstimateEpochMs - now.wallEpochMs).coerceAtLeast(0)) },
            addBounded(now.elapsedRealtimeMs, FRESHNESS_MS),
        )).minOrNull()
        return ProtectionStatus(derivedReadiness, causes.sortedWith(compareByDescending<ProtectionStatus.ActiveCause> { it.tier.ordinal }
            .thenByDescending { it.remainingMs ?: Long.MAX_VALUE }.thenBy { it.ruleId.orEmpty() }), next,
            ProtectionStatus.PassStatus.Unavailable,
            ProtectionStatus.DetectorHealth(pathAConnected, ProtectionStatus.PathBState.UNKNOWN, reasons.toSet()),
            floor.hasResolvedEverySource && checked != null, now, deadline,
            ProtectionStatus.SafetyFloorStatus(floor.requiredSources.toSet(), floor.resolvedSources.toSet(), floor.failedSources.toSet(), checked,
                floor.degraded || floorStale))
    }

    private data class ForeignActivity(val end: Long?)

    /** Legacy quick-block scheduling follows device wall time, not the editor's saved-zone ledger. */
    private fun foreignActivity(rule: BlockRule, now: AccessClockStamp, zone: String): ForeignActivity? {
        val expiry = rule.expiresAtEpochMs
        if (expiry != null && expiry <= now.wallEpochMs) return null
        val schedule = rule.schedule ?: return ForeignActivity(expiry)
        val cal = Calendar.getInstance(TimeZone.getTimeZone(zone)).apply { timeInMillis = now.wallEpochMs }
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        val overnight = schedule.startMinuteOfDay > schedule.endMinuteOfDay
        val occurrenceDay = if (overnight && minute < schedule.endMinuteOfDay) if (day == 1) 7 else day - 1 else day
        val inside = if (overnight) minute >= schedule.startMinuteOfDay || minute < schedule.endMinuteOfDay
            else minute in schedule.startMinuteOfDay until schedule.endMinuteOfDay
        if (!inside || (schedule.daysOfWeek.isNotEmpty() && occurrenceDay !in schedule.daysOfWeek)) return null
        val end = (cal.clone() as Calendar).apply {
            if (overnight && minute >= schedule.startMinuteOfDay) add(Calendar.DATE, 1)
            set(Calendar.HOUR_OF_DAY, schedule.endMinuteOfDay / 60); set(Calendar.MINUTE, schedule.endMinuteOfDay % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ForeignActivity(minOf(end, expiry ?: Long.MAX_VALUE))
    }

    private fun nextOccurrence(rules: List<BlockRule>, configuredIds: Set<String>, editor: EditorCommitmentState,
        now: AccessClockStamp, legacyZone: String): ProtectionStatus.NextOccurrence? {
        val anchor = editor.timeline
        val elapsed = anchor?.let { trustedElapsedMs(it, now) }
        val logicalWall = if (anchor != null && elapsed != null) addBounded(anchor.wallEpochMs, elapsed) else now.wallEpochMs
        return rules.filter { it.enabled && it.schedule != null && it.expiresAtEpochMs == null }.mapNotNull { rule ->
            // The saved occurrence owns its zone, including when the device travels.
            val owned = rule.id in configuredIds
            val saved = editor.causes[ruleCauseKey(rule.id)]
            if (owned && saved == null) return@mapNotNull null
            val schedule = checkNotNull(rule.schedule)
            val ruleWall = if (owned) logicalWall else now.wallEpochMs
            val today = Calendar.getInstance(TimeZone.getTimeZone(saved?.zoneId ?: legacyZone)).apply { timeInMillis = ruleWall }
            val start = (0..7).firstNotNullOfOrNull { offset ->
                (today.clone() as Calendar).apply {
                    add(Calendar.DATE, offset); set(Calendar.HOUR_OF_DAY, schedule.startMinuteOfDay / 60)
                    set(Calendar.MINUTE, schedule.startMinuteOfDay % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.takeIf { c -> c.timeInMillis > ruleWall &&
                    (schedule.daysOfWeek.isEmpty() || ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 in schedule.daysOfWeek) }
            } ?: return@mapNotNull null
            ProtectionStatus.NextOccurrence(rule.id, ProtectionStatus.sanitizeDisplayName(rule.name),
                if (rule.id.startsWith("sleep-")) ProtectionStatus.CauseKind.SLEEP else ProtectionStatus.CauseKind.SCHEDULE,
                addBounded(now.wallEpochMs, start.timeInMillis - ruleWall))
        }.minByOrNull { it.startWallEstimateEpochMs }
    }

    private fun addBounded(value: Long, duration: Long): Long = if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration
}
