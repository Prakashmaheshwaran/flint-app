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
        val duration = event.untilEpochMs?.let { end ->
            runCatching { Math.subtractExact(end, event.at.wallEpochMs) }.getOrNull()?.takeIf { it > 0 }
        }
        val until = duration?.let { runCatching { Math.addExact(effective.wallEpochMs, it) }.getOrNull() }
        val invalidInterval = event.untilEpochMs != null && until == null
        // Saturated audit bounds only: no release authority reads this event. Keep the
        // record writable and explicitly uncertain when its interval is unrepresentable,
        // instead of throwing from the reducer or emitting a corrupt negative interval.
        val boundedAt = if (invalidInterval) effective.copy(wallEpochMs = effective.wallEpochMs.coerceIn(0, Long.MAX_VALUE - 1)) else effective
        val boundedUntil = if (invalidInterval) {
            if (duration == null) boundedAt.wallEpochMs + 1 else Long.MAX_VALUE
        } else until
        val confidenceReason = if (invalidInterval) "invalid_interval" else reason
        return event.copy(at = boundedAt,
            untilEpochMs = boundedUntil,
            observedAtEpochMs = now.wallEpochMs,
            timeConfidence = confidenceReason?.let(ProtectionEventTimeConfidence::Uncertain) ?: ProtectionEventTimeConfidence.Certain,
            timeUncertain = confidenceReason != null,
            reconciled = event.breakEndReason == ProtectionBreakEndReason.EXPIRED &&
                (lifetimeElapsed == null || !expiredAtDeadline || effectiveElapsed < now.elapsedRealtimeMs))
    }
}
