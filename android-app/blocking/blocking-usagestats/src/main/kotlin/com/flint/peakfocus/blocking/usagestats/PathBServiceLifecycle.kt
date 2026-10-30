package com.flint.peakfocus.blocking.usagestats

import com.flint.peakfocus.blocking.overlay.ProtectionStatus.PathBState
import com.flint.peakfocus.core.model.FallbackMode

/** Service-owned health; late callbacks from an old instance cannot overwrite its replacement. */
internal class PathBServiceLifecycle(
    private val nowElapsed: () -> Long,
    private val leaseMs: Long = 90_000L,
    private val publish: (PathBState, Set<String>, Long?) -> Unit,
) {
    private var owner: Any? = null
    private var foregroundStarted = false
    private var reasons: Set<String> = emptySet()
    private var generation = 0L
    private var confirmedMode: FallbackMode? = null
    private var leaseUntil: Long? = null

    private fun unleased(state: PathBState) {
        confirmedMode = null
        leaseUntil = null
        publish(state, reasons, null)
    }

    @Synchronized fun startRequested() {
        if (owner == null) {
            generation++
            reasons = emptySet()
            unleased(PathBState.UNKNOWN)
        }
    }

    @Synchronized fun isOwner(instance: Any): Boolean = owner === instance

    @Synchronized fun starting(instance: Any) {
        generation++
        owner = instance
        foregroundStarted = false
        reasons = emptySet()
        unleased(PathBState.UNKNOWN)
    }

    @Synchronized fun foregroundStarted(instance: Any) {
        if (owner === instance) foregroundStarted = true
    }

    /** Called only after a completed permission/foreground evaluation, not by notification refresh. */
    @Synchronized fun mode(instance: Any, mode: FallbackMode) {
        if (owner !== instance || !foregroundStarted) return
        if (mode == FallbackMode.OFF) {
            unavailable(instance, "usage-access-missing")
            return
        }
        reasons = emptySet()
        val now = nowElapsed()
        // Successful ticks remain frequent; stable health is renewed only when half its
        // lease remains. A transition/failure always invalidates this coalescing state.
        if (confirmedMode == mode && leaseUntil?.let { it - now > leaseMs / 2 } == true) return
        confirmedMode = mode
        leaseUntil = now + leaseMs
        publish(if (mode == FallbackMode.STANDBY) PathBState.STANDBY else PathBState.ENFORCING,
            reasons, leaseUntil)
    }

    @Synchronized fun handoff(instance: Any) {
        if (owner !== instance) return
        reasons = emptySet()
        unleased(PathBState.UNKNOWN)
    }

    @Synchronized fun unavailable(instance: Any, reason: String) {
        if (owner !== instance) return
        reasons = setOf(reason)
        unleased(PathBState.UNKNOWN)
    }

    @Synchronized fun stopped(instance: Any) {
        if (owner !== instance) return
        generation++
        owner = null
        foregroundStarted = false
        // Stopping the service does not restore a revoked permission. Keep that observed
        // condition until a new permission check/start replaces it; clean stops stay clean.
        // An off service does not poll permissions: a regrant without a gate sync/start
        // retains conservative health until that next observation, rather than inventing it.
        reasons = reasons.intersect(setOf("usage-access-missing"))
        unleased(PathBState.OFF)
    }

    /** Capture before calling Android: onDestroy or a replacement may run during the binder call. */
    @Synchronized fun stopGeneration(): Long = generation

    /** OFF requires stopService finding no running service and no live local instance. */
    @Synchronized fun stopRequested(observedGeneration: Long, serviceWasRunning: Boolean, degradedReasons: Set<String>) {
        if (observedGeneration != generation) return
        reasons = degradedReasons.toSet()
        unleased(if (owner == null && !serviceWasRunning) PathBState.OFF else PathBState.UNKNOWN)
    }

    @Synchronized fun stopFailed(observedGeneration: Long) {
        if (observedGeneration == generation) gateFailed("foreground-stop-failed")
    }

    @Synchronized fun startFailed() {
        if (owner == null) publish(PathBState.UNKNOWN, setOf("foreground-start-failed"), null)
    }

    @Synchronized fun gateFailed(reason: String) {
        // The service's own permission observation remains authoritative while it is alive.
        if (owner != null) return
        reasons = setOf(reason)
        unleased(PathBState.UNKNOWN)
    }
}
