package com.flint.peakfocus.blocking.overlay

import android.content.Context
import com.flint.peakfocus.core.common.RuntimeSafetySurfaces

/**
 * The device's *actual* default home app and default/system dialer — the runtime half of the
 * enforcement safety floor (`core-model` SafetyFloor holds the static half). Blocking the
 * launcher turns the Home button into a block-screen loop during allow-list windows (Sleep
 * Mode, brick-phone mode), and blocking the dialer blocks emergency calling.
 *
 * Detectors do not consult this directly any more: [BlockScreenCoordinator.isSafetyFloor] owns
 * a cached [SafetyFloorGate] over [resolveBySource] and applies the floor before every cause.
 */
object NeverBlockSurfaces {
    fun resolveBySource(context: Context): Map<String, Set<String>?> =
        RuntimeSafetySurfaces.resolveBySource(context)

    fun resolve(context: Context): Set<String> = RuntimeSafetySurfaces.resolve(context)
}
