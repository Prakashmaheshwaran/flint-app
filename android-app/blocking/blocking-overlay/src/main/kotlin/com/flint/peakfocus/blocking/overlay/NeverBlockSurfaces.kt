package com.flint.peakfocus.blocking.overlay

import android.content.Context
import com.flint.peakfocus.core.common.RuntimeSafetySurfaces

/**
 * The device's *actual* default/system dialer and home apps — the runtime half of the
 * enforcement safety floor (`core-model` SafetyFloor holds the static half). Blocking the
 * launcher turns the Home button into a block-screen loop during allow-list windows (Sleep
 * Mode, brick-phone mode), and blocking the dialer blocks emergency calling.
 *
 * Detectors do not consult this directly any more: [BlockScreenCoordinator.isSafetyFloor] owns
 * a cached [SafetyFloorGate] over it and applies the floor before every block cause. Kept as
 * the overlay module's resolution entry point.
 */
object NeverBlockSurfaces {
    fun resolve(context: Context): Set<String> = RuntimeSafetySurfaces.resolve(context)
}
