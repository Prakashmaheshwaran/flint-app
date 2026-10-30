package com.flint.peakfocus.core.model

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-local live binding, not the Accessibility grant setting. Process death clears it;
 * an old service's delayed destruction cannot disconnect a newer binding. Detectors subscribe
 * only while alive and marshal callbacks onto their own event loop.
 */
class DetectorSignals {
    private var pathAOwner: Any? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val pathAConnected: Boolean
        get() = synchronized(this) { pathAOwner != null }

    fun connected(owner: Any) {
        synchronized(this) { pathAOwner = owner }
        requestEvaluation()
    }

    fun disconnected(owner: Any) {
        val changed = synchronized(this) {
            if (pathAOwner === owner) { pathAOwner = null; true } else false
        }
        if (changed) requestEvaluation()
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Boot and clock changes wake whichever detector currently owns enforcement. */
    fun requestEvaluation() { listeners.forEach { runCatching(it) } }
}

object EnforcementSignals {
    val detectors = DetectorSignals()
}

/** The standby FGS stays running; only one detector may evaluate/show a shield at a time. */
enum class FallbackMode { OFF, STANDBY, ENFORCING }

object DetectorFailover {
    fun mode(usageAccess: Boolean, pathAConnected: Boolean): FallbackMode = when {
        !usageAccess -> FallbackMode.OFF
        pathAConnected -> FallbackMode.STANDBY
        else -> FallbackMode.ENFORCING
    }
}
