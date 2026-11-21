package com.flint.peakfocus.quickstart

import com.flint.peakfocus.glance.ActiveGlanceCause
import com.flint.peakfocus.glance.GlanceCauseKind
import com.flint.peakfocus.glance.GlanceTier
import com.flint.peakfocus.glance.ProtectionReading
import com.flint.peakfocus.glance.ProtectionStatus
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTileMapperTest {
    private val now = 100_000L
    private val epoch = 1_800_000_000_000L
    private val cause = ActiveGlanceCause("occurrence-1", "Deep study", GlanceTier.HARDCORE, now + 300_000,
        GlanceCauseKind.FOCUS_SESSION)
    private val active = ProtectionReading(ProtectionStatus.ACTIVE, now - 1_000, now + 30_000, cause,
        sameBootVerified = true)
    private fun tile(reading: ProtectionReading, at: Long = now) =
        QuickTileMapper.map(reading, at, epoch + (at - now)) { "@$it" }

    private fun assertUnconfirmed(display: QuickTileDisplay) {
        assertEquals(QuickTileMode.INACTIVE, display.mode)
        assertEquals("Start focus", display.label)
        assertEquals("Status unconfirmed", display.subtitle)
        assertEquals("Protection not confirmed", display.stateDescription)
        assertNull(display.refreshAtElapsedMs)
    }

    @Test fun unavailableOrStaleReadingsNeverClaimProtection() {
        assertUnconfirmed(tile(ProtectionReading()))
        // Each freshness failure the widget rejects is rejected here too.
        assertUnconfirmed(tile(active.copy(sameBootVerified = false)))
        assertUnconfirmed(tile(active.copy(timeUncertain = true)))
        assertUnconfirmed(tile(active.copy(checkedElapsedMs = null)))
        assertUnconfirmed(tile(active.copy(checkedElapsedMs = now + 1)))
        assertUnconfirmed(tile(active.copy(checkedElapsedMs = now - 60_000)))
        assertUnconfirmed(tile(active.copy(validUntilElapsedMs = now)))
        assertUnconfirmed(tile(active.copy(validUntilElapsedMs = null)))
        assertUnconfirmed(tile(active, at = now + 30_000))
    }

    @Test fun activeFocusShowsItsNameTierAndAuthorityEndWithoutAToggle() {
        val display = tile(active)
        assertEquals(QuickTileMode.ACTIVE, display.mode)
        assertEquals("Deep study", display.label)
        assertEquals("Until @${epoch + 300_000}", display.subtitle)
        assertEquals("Protection active at last check", display.stateDescription)
        assertEquals("Deep study, Hardcore. Protection active at last check, until @${epoch + 300_000}. Opens Flint.",
            display.contentDescription)
        // Re-read when the check ages out (validUntil here), before the end.
        assertEquals(now + 30_000, display.refreshAtElapsedMs)
        // ...or when the check is a minute old, whichever comes first.
        assertEquals(now + 59_000, tile(active.copy(validUntilElapsedMs = now + 500_000)).refreshAtElapsedMs)
        assertEquals(now + 20_000, tile(active.copy(cause = cause.copy(logicalEndElapsedMs = now + 20_000))).refreshAtElapsedMs)
    }

    @Test fun activeEvidenceBarMatchesTheWidget() {
        assertUnconfirmed(tile(active.copy(cause = null)))
        assertUnconfirmed(tile(active.copy(cause = cause.copy(occurrenceId = " "))))
        assertUnconfirmed(tile(active.copy(cause = cause.copy(kind = GlanceCauseKind.UNKNOWN, occurrenceId = null))))
        assertUnconfirmed(tile(active.copy(cause = cause.copy(logicalEndElapsedMs = null))))
        assertUnconfirmed(tile(active.copy(cause = cause.copy(logicalEndElapsedMs = null, tier = GlanceTier.HARDER))))
        val ended = tile(active.copy(cause = cause.copy(logicalEndElapsedMs = now)))
        assertEquals(QuickTileMode.INACTIVE, ended.mode)
        assertEquals("Planned end reached", ended.subtitle)
        // A schedule cause has no occurrence requirement; an open-ended Easy cause says so.
        val easy = tile(active.copy(cause = ActiveGlanceCause(null, "Mornings", GlanceTier.EASY, null, GlanceCauseKind.SCHEDULE)))
        assertEquals(QuickTileMode.ACTIVE, easy.mode)
        assertEquals("Until ended in Flint", easy.subtitle)
    }

    @Test fun degradedInactiveAndRecoveryAreHonestlyInactive() {
        val degraded = tile(active.copy(status = ProtectionStatus.DEGRADED))
        assertEquals(QuickTileMode.INACTIVE, degraded.mode)
        assertEquals("Check protection", degraded.subtitle)
        assertEquals(now + 30_000, degraded.refreshAtElapsedMs)
        val idle = tile(active.copy(status = ProtectionStatus.INACTIVE, cause = null))
        assertEquals(QuickTileMode.INACTIVE, idle.mode)
        assertEquals("Not active", idle.subtitle)
        assertEquals("Start focus. No active protection at last check. Opens Flint.", idle.contentDescription)
        val recovery = tile(active.copy(status = ProtectionStatus.PASS_RECOVERY, recoveryEndElapsedMs = now + 10_000))
        assertEquals(QuickTileMode.INACTIVE, recovery.mode)
        assertEquals("Recovery until @${epoch + 10_000}", recovery.subtitle)
        assertEquals(now + 10_000, recovery.refreshAtElapsedMs)
        assertUnconfirmed(tile(active.copy(status = ProtectionStatus.PASS_RECOVERY, recoveryEndElapsedMs = now)))
        assertUnconfirmed(tile(active.copy(status = ProtectionStatus.PASS_RECOVERY, recoveryEndElapsedMs = null)))
    }

    @Test fun longNamesAreShortenedAndEveryActiveStateIsAnnounced() {
        val long = tile(active.copy(cause = cause.copy(name = "A very long focus session name indeed")))
        assertTrue(long.label.length <= 24)
        assertTrue(long.label.endsWith("…"))
        for (status in ProtectionStatus.entries) {
            val display = tile(active.copy(status = status, recoveryEndElapsedMs = now + 10_000))
            assertTrue(display.contentDescription.endsWith("Opens Flint."))
            assertFalse(display.stateDescription.isBlank())
        }
    }

    @Test fun endTimesHonorTheDayAndClockSetting() {
        val zone = ZoneId.of("UTC")
        val nowEpoch = 1_800_000_000_000L // 2027-01-15 08:00 UTC
        assertEquals("08:30", QuickTileMapper.formatTime(nowEpoch + 1_800_000, nowEpoch, zone, Locale.US, true))
        assertEquals("8:30 AM", QuickTileMapper.formatTime(nowEpoch + 1_800_000, nowEpoch, zone, Locale.US, false))
        assertEquals("Sat 08:00", QuickTileMapper.formatTime(nowEpoch + 86_400_000, nowEpoch, zone, Locale.US, true))
    }
}
