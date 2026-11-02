package com.flint.peakfocus.blocking.resilience

import com.flint.peakfocus.core.model.DetectorSignals
import com.flint.peakfocus.core.model.EnforcementSignals

/** Wake the live detector even if a platform restart hook is denied or unavailable. */
internal fun rearmDetectors(
    actions: List<() -> Unit>,
    signals: DetectorSignals = EnforcementSignals.detectors,
) {
    actions.forEach { runCatching(it) }
    signals.requestEvaluation()
}
