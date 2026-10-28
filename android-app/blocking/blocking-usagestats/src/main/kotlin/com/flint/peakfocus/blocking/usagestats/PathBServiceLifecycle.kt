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

    @Synchronized fun startRequested() {
        if (owner == null) {
            reasons = emptySet()
            publish(PathBState.UNKNOWN, reasons, null)
        }
    }

    @Synchronized fun isOwner(instance: Any): Boolean = owner === instance

    @Synchronized fun starting(instance: Any) {
        owner = instance
        foregroundStarted = false
        reasons = emptySet()
        publish(PathBState.UNKNOWN, reasons, null)
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
        publish(if (mode == FallbackMode.STANDBY) PathBState.STANDBY else PathBState.ENFORCING,
            reasons, nowElapsed() + leaseMs)
    }

    @Synchronized fun handoff(instance: Any) {
        if (owner !== instance) return
        reasons = emptySet()
        publish(PathBState.UNKNOWN, reasons, null)
    }

    @Synchronized fun unavailable(instance: Any, reason: String) {
        if (owner !== instance) return
        reasons = setOf(reason)
        publish(PathBState.UNKNOWN, reasons, null)
    }

    @Synchronized fun stopped(instance: Any) {
        if (owner !== instance) return
        owner = null
        foregroundStarted = false
        publish(PathBState.OFF, reasons, null)
    }

    /** OFF requires stopService finding no running service and no live local instance. */
    @Synchronized fun stopRequested(serviceWasRunning: Boolean, degradedReasons: Set<String>) {
        reasons = degradedReasons.toSet()
        publish(if (owner == null && !serviceWasRunning) PathBState.OFF else PathBState.UNKNOWN, reasons, null)
    }

    @Synchronized fun startFailed() {
        if (owner == null) publish(PathBState.UNKNOWN, setOf("foreground-start-failed"), null)
    }

    @Synchronized fun gateFailed(reason: String) {
        reasons = setOf(reason)
        publish(PathBState.UNKNOWN, reasons, null)
    }
}
