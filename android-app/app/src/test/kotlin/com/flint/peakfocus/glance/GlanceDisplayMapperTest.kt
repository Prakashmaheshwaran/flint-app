package com.flint.peakfocus.glance

import java.time.ZoneId
import java.time.Instant
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class GlanceDisplayMapperTest {
    private val now = 100_000L
    private val cause = ActiveGlanceCause("occurrence-1", "Focus", GlanceTier.HARDCORE, now + 300_000)
    private val active = ProtectionReading(ProtectionStatus.ACTIVE, now, now + 60_000, cause,
        sameBootVerified = true)
    private fun display(reading: ProtectionReading, focus: FocusTodayReading = FocusTodayReading()) =
        GlanceDisplayMapper.map(reading, focus, now, 1_800_000_000_000, ZoneId.of("UTC"), Locale.US)

    @Test fun unavailableDoesNotInventInactiveOrZero() {
        val result = display(ProtectionReading())
        assertEquals("Protection state unavailable", result.status)
        assertEquals("Next session unavailable", result.next)
        assertEquals("Focus history unavailable", result.focusedToday)
        assertNull(result.countdownEndElapsedMs)
    }

    @Test fun allTiersUseAuthoritativeLogicalDeadlineAndObservationLabel() {
        for (tier in GlanceTier.entries) {
            val result = display(active.copy(cause = cause.copy(tier = tier)))
            assertEquals("Protection active at last check", result.status)
            assertTrue(result.detail.contains(tier.label))
            assertEquals(cause.logicalEndElapsedMs, result.countdownEndElapsedMs)
            assertTrue(result.checked.contains("Checked"))
            assertEquals(now + 60_000, result.refreshAtElapsedMs)
        }
    }

    @Test fun expiredIsNotProtectionOrCommittedCompletion() {
        val result = display(active.copy(cause = cause.copy(logicalEndElapsedMs = now)))
        assertEquals("Planned end reached", result.status)
        assertNull(result.countdownEndElapsedMs)
    }

    @Test fun staleMissingOrFutureChecksAndInvalidValidityNeverClaimActive() {
        listOf(
            active.copy(checkedElapsedMs = now - 60_000),
            active.copy(checkedElapsedMs = now + 1),
            active.copy(checkedElapsedMs = null),
            active.copy(validUntilElapsedMs = now),
            active.copy(validUntilElapsedMs = null),
            active.copy(sameBootVerified = false),
            active.copy(timeUncertain = true),
        ).forEach {
            assertEquals("Protection check needed", display(it).status)
            assertNull(display(it).countdownEndElapsedMs)
        }
    }

    @Test fun brokenCauseAndUnboundedStrictDeadlineAreUnavailable() {
        listOf(active.copy(cause = null), active.copy(cause = cause.copy(occurrenceId = "")),
            active.copy(cause = cause.copy(logicalEndElapsedMs = null))).forEach {
            assertEquals("Protection state unavailable", display(it).status)
        }
        assertTrue(display(active.copy(cause = cause.copy(tier = GlanceTier.EASY,
            logicalEndElapsedMs = null))).detail.contains("Until ended in Flint"))
    }

    @Test fun degradedAndInactiveAreDistinct() {
        assertEquals("Protection degraded", display(active.copy(status = ProtectionStatus.DEGRADED)).status)
        assertEquals("No active protection at last check",
            display(active.copy(status = ProtectionStatus.INACTIVE)).status)
        assertNull(display(active.copy(status = ProtectionStatus.DEGRADED)).countdownEndElapsedMs)
    }

    @Test fun passNeverClaimsProtectionAndMissingOrEndedRecoveryNeedsCheck() {
        val pass = active.copy(status = ProtectionStatus.PASS_RECOVERY, recoveryEndElapsedMs = now + 500)
        assertEquals("Emergency Pass recovery", display(pass).status)
        assertEquals(now + 500, display(pass).refreshAtElapsedMs)
        assertNull(display(pass).countdownEndElapsedMs)
        assertEquals("Recovery time ended", display(pass.copy(recoveryEndElapsedMs = now)).status)
        assertEquals("Protection state unavailable", display(pass.copy(recoveryEndElapsedMs = null)).status)
    }

    @Test fun focusLinesPreserveFAbsenceZeroEmptyPartialAndInProgressCopy() {
        listOf("Focus history unavailable", "No focus sessions yet", "0m", "5m · in progress").forEach { line ->
            val day = Instant.ofEpochMilli(1_800_000_000_000).atZone(ZoneId.of("UTC")).toLocalDate()
            val result = display(active, FocusTodayReading(line, "Some history couldn't be read", day))
            assertEquals(line, result.focusedToday)
            assertEquals("Some history couldn't be read", result.historyNote)
        }
    }

    @Test fun historyFromYesterdayIsUnavailableAndDateAlwaysQualifiesCachedTodayLabel() {
        val day = Instant.ofEpochMilli(1_800_000_000_000).atZone(ZoneId.of("UTC")).toLocalDate()
        val stale = display(active, FocusTodayReading("5m", day = day.minusDays(1)))
        assertEquals("Focus history unavailable", stale.focusedToday)
        assertEquals("Open Flint to refresh", stale.historyNote)
        assertTrue(stale.focusedTodayLabel.contains("Jan 15"))
        val valid = display(ProtectionReading(), FocusTodayReading("0m", day = day))
        assertNotNull(valid.refreshAtElapsedMs) // History midnight refresh even when C is unavailable.
    }

    @Test fun nextSessionUsesOnlyFutureAuthoritativeReading() {
        val epoch = 1_800_000_000_000L
        assertTrue(display(active.copy(next = NextGlanceSession("Study", epoch + 60_000))).next.startsWith("Next: Study"))
        assertEquals("Next session needs a new check", display(active.copy(next = NextGlanceSession("Old", epoch))).next)
        assertEquals("No upcoming session", display(active).next)
    }
}
