package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.clockDiscontinuity
import com.flint.peakfocus.blocking.engine.trustedElapsedMs
import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.model.*

/** History evidence only: never changes an access lifetime, quota, or enforcement clock. */
internal object ProtectionLifecycleTiming {
    fun record(event: ProtectionLifecycleEvent, snapshot: ProtectionSnapshot,
               lifetime: AccessDuration? = null): ProtectionLifecycleEvent {
        val now = snapshot.observedAt
        // The editor timeline survives same-boot clock edits and process restarts. The
        // ledger initialization anchor supports snapshots without an editor timeline.
        // A raw, continually replaced ledger checkpoint would hide an earlier wall jump.
        val anchor = snapshot.editor.timeline ?: snapshot.focus.protection?.initializedAt
        fun known(stamp: AccessClockStamp) = stamp.bootSessionId.isNotBlank() &&
            !stamp.bootSessionId.startsWith("process-") // ProtectionClock's unknown-boot fallback.
        val start = lifetime?.startedAt
        val elapsed = anchor?.let { trustedElapsedMs(it, now) }
        val lifetimeElapsed = start?.let { trustedElapsedMs(it, now) }
        val expiredAtDeadline = event.breakEndReason == ProtectionBreakEndReason.EXPIRED &&
            lifetime != null && lifetimeElapsed != null && lifetimeElapsed >= lifetime.durationMs
        val effectiveElapsed = if (expiredAtDeadline) start!!.elapsedRealtimeMs + lifetime!!.durationMs else now.elapsedRealtimeMs
        val offset = anchor?.let { effectiveElapsed - it.elapsedRealtimeMs }
        val logicalWall = if (elapsed != null && offset != null && offset >= 0)
            runCatching { Math.addExact(anchor.wallEpochMs, offset) }.getOrNull()?.takeIf { it >= 0 } else null
        val reason = when {
            !known(now) || anchor?.let { !known(it) } == true || start?.let { !known(it) } == true -> "unknown_boot"
            anchor == null -> "missing_anchor"
            anchor.bootSessionId != now.bootSessionId || start?.let { it.bootSessionId != now.bootSessionId } == true -> "reboot_without_anchor"
            elapsed == null || (start != null && lifetimeElapsed == null) -> "monotonic_regression"
            logicalWall == null -> "invalid_anchor"
            clockDiscontinuity(anchor, now) -> "same_boot_drift"
            else -> null
        }
        // A measured wall edit still has a useful logical timestamp. Across a reboot
        // the old release ended conservatively at observation; do not invent its end.
        val effective = if (logicalWall != null && reason in setOf(null, "same_boot_drift"))
            now.copy(wallEpochMs = logicalWall, elapsedRealtimeMs = effectiveElapsed) else now
        val duration = event.untilEpochMs?.let { it - event.at.wallEpochMs }
        return event.copy(at = effective,
            untilEpochMs = duration?.let { Math.addExact(effective.wallEpochMs, it) },
            observedAtEpochMs = now.wallEpochMs,
            timeConfidence = reason?.let(ProtectionEventTimeConfidence::Uncertain) ?: ProtectionEventTimeConfidence.Certain,
            timeUncertain = reason != null,
            reconciled = event.breakEndReason == ProtectionBreakEndReason.EXPIRED &&
                (lifetimeElapsed == null || !expiredAtDeadline || effectiveElapsed < now.elapsedRealtimeMs))
    }
}
