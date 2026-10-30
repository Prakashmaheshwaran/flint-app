package com.flint.peakfocus.glance

import com.flint.peakfocus.blocking.overlay.ProtectionStatus as C
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.BreakLevel
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** R18b's new machine reason is safe before the separate H-A short-copy mapping ships. */
class TimeLimitMeasurementFallbackTest {
    @Test fun PC_60_unmappedMeasurementFailureUsesGenericDegradedInsteadOfProtected() {
        val now = 100_000L
        val status = C(C.Readiness.READY,
            listOf(C.ActiveCause("timer", "Study", C.CauseKind.TIMER, BreakLevel.HARDCORE,
                300_000, 400_000, 1_800_000_300_000, false, "occurrence")), null,
            C.PassStatus.Available,
            C.DetectorHealth(true, C.PathBState.OFF, setOf("time-limit-measurement-unavailable")),
            true, AccessClockStamp(1_800_000_000_000, now, "boot"), 130_000,
            C.SafetyFloorStatus(setOf("home"), setOf("home"), emptySet(), now, false))
        val display = GlanceDisplayMapper.map(coordinatorGlanceReading(status), FocusTodayReading(), now,
            1_800_000_000_000, ZoneId.of("UTC"), Locale.US)
        assertEquals("Protection degraded", display.status)
        assertNull(display.session)
        assertNull(display.countdownEndElapsedMs)
        assertEquals("Flint · Protection degraded", OngoingNotificationPlan.from(display, now).title)
    }
}
