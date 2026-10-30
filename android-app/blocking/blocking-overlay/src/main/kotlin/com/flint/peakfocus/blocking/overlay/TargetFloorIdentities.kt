package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.SafetyFloor
import java.lang.ref.WeakReference

/**
 * Process-wide, read-only floor identities for display queries (App/Site detail). Holds only a
 * weak reference to the most recently constructed [SafetyFloorGate]: it never creates a gate,
 * refreshes roles or keeps a stopped detector alive. With both detectors running the newer gate
 * wins; both resolve the same OS roles and each identity carries its own resolution time.
 * Call [read] off Main so the shield and UI paths never wait on the gate's membership lock.
 */
object TargetFloorIdentities {
    @Volatile private var live: WeakReference<SafetyFloorGate>? = null

    internal fun register(gate: SafetyFloorGate) = register(WeakReference(gate))

    /** Test seam: a cleared reference behaves exactly like a collected gate. */
    internal fun register(reference: WeakReference<SafetyFloorGate>?) {
        live = reference
    }

    /** Proven membership from the live gate; null when no detector's gate is live. */
    fun read(packageName: String): FloorIdentity? = live?.get()?.readIdentity(packageName)

    /**
     * Fallback without a live gate: Flint itself and the static system floor only. Runtime
     * Home, dialer and uninstaller roles stay unknown here and are never claimed.
     */
    fun staticIdentity(packageName: String, selfPackage: String): FloorIdentity? = when {
        packageName == selfPackage -> FloorIdentity(FloorIdentity.Kind.SELF)
        SafetyFloor.isProtected(packageName, selfPackage) -> FloorIdentity(FloorIdentity.Kind.STATIC_SYSTEM)
        else -> null
    }
}
