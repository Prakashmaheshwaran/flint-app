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
            fun add(key: String, ruleId: String?, name: String, kind: ProtectionStatus.CauseKind, tier: BreakLevel,
                legacyOpenEnded: Boolean = false) {
                val occurrence = sidecar.causes[key]
                if (occurrence == null) {
                    if (legacyOpenEnded) causes += ProtectionStatus.ActiveCause(ruleId, ProtectionStatus.sanitizeDisplayName(name),
                        kind, tier, null, null, null, true)
                    else reasons += "occurrence-state-unavailable"
                    return
                }
                if (occurrence.occurrenceId == "inactive") return
                val interval = occurrence.interval
                val remaining = interval?.remainingMs(now)
                if (remaining == 0L) return
                causes += ProtectionStatus.ActiveCause(ruleId, ProtectionStatus.sanitizeDisplayName(name), kind, tier,
                    remaining, remaining?.let { addBounded(now.elapsedRealtimeMs, it) },
                    remaining?.let { addBounded(now.wallEpochMs, it) },
                    interval?.let { clockDiscontinuity(it.anchor, now) } ?: true)
            }
            currentRules.filter { it.enabled }.forEach { rule ->
                val kind = when {
                    rule.id.startsWith("sleep-") -> ProtectionStatus.CauseKind.SLEEP
                    rule.schedule != null -> ProtectionStatus.CauseKind.SCHEDULE
                    rule.id.startsWith("session-timer-") -> ProtectionStatus.CauseKind.TIMER
                    rule.expiresAtEpochMs != null && !rule.id.startsWith(BlockRule.SESSION_ID_PREFIX) -> ProtectionStatus.CauseKind.SCHEDULE
                    else -> ProtectionStatus.CauseKind.SESSION
                }
                add(ruleCauseKey(rule.id), rule.id, rule.name, kind, rule.breakLevel,
                    legacyOpenEnded = rule.schedule == null && rule.expiresAtEpochMs == null)
            }
            (times as StorageState.Ready).value.forEach { limit ->
                add(timeCauseKey(limit.packageName), null, "Time limit", ProtectionStatus.CauseKind.TIME_LIMIT, limit.breakLevel)
            }
            (opens as StorageState.Ready).value.forEach { limit ->
                add(openCauseKey(limit.packageName), null, "Open limit", ProtectionStatus.CauseKind.OPEN_LIMIT, limit.breakLevel)
            }
        }
        val derivedReadiness = if (readiness == ProtectionStatus.Readiness.READY && "occurrence-state-unavailable" in reasons)
            ProtectionStatus.Readiness.UNAVAILABLE else readiness
        val next = if (derivedReadiness == ProtectionStatus.Readiness.READY && sidecar != null) nextOccurrence(currentRules, sidecar, now) else null
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

    private fun nextOccurrence(rules: List<BlockRule>, editor: EditorCommitmentState, now: AccessClockStamp): ProtectionStatus.NextOccurrence? {
        val anchor = editor.timeline
        val elapsed = anchor?.let { trustedElapsedMs(it, now) }
        val logicalWall = if (anchor != null && elapsed != null) addBounded(anchor.wallEpochMs, elapsed) else now.wallEpochMs
        return rules.filter { it.enabled && it.schedule != null && it.expiresAtEpochMs == null }.mapNotNull { rule ->
            // The saved occurrence owns its zone, including when the device travels.
            val saved = editor.causes[ruleCauseKey(rule.id)] ?: return@mapNotNull null
            val schedule = checkNotNull(rule.schedule)
            val today = Calendar.getInstance(TimeZone.getTimeZone(saved.zoneId)).apply { timeInMillis = logicalWall }
            val start = (0..7).firstNotNullOfOrNull { offset ->
                (today.clone() as Calendar).apply {
                    add(Calendar.DATE, offset); set(Calendar.HOUR_OF_DAY, schedule.startMinuteOfDay / 60)
                    set(Calendar.MINUTE, schedule.startMinuteOfDay % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.takeIf { c -> c.timeInMillis > logicalWall &&
                    (schedule.daysOfWeek.isEmpty() || ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 in schedule.daysOfWeek) }
            } ?: return@mapNotNull null
            ProtectionStatus.NextOccurrence(rule.id, ProtectionStatus.sanitizeDisplayName(rule.name),
                if (rule.id.startsWith("sleep-")) ProtectionStatus.CauseKind.SLEEP else ProtectionStatus.CauseKind.SCHEDULE,
                addBounded(now.wallEpochMs, start.timeInMillis - logicalWall))
        }.minByOrNull { it.startWallEstimateEpochMs }
    }

    private fun addBounded(value: Long, duration: Long): Long = if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration
}
