package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerModelsTest {
    private val app = AppRef("example.app", "Example")
    private val draft = TimerDraft(apps = setOf(app))

    @Test fun `start instant is read back from timestamped ids and ignored otherwise`() {
        val start = 1_790_000_000_000L
        val timed = timerRule(draft, start, "$start-7f3c9a2e-0000-4000-8000-000000000000")
        assertEquals(start, timerStartEpochMs(timed))
        // Pre-redesign ids are a bare UUID, even one whose first block is all digits.
        assertNull(timerStartEpochMs(timerRule(draft, start, "12345678-aaaa-4bbb-8ccc-dddddddddddd")))
        assertNull(timerStartEpochMs(timerRule(draft, start, "7f3c9a2e-aaaa-4bbb-8ccc-dddddddddddd")))
        // A claimed start at or after the deadline is not trusted.
        assertNull(timerStartEpochMs(timed.copy(expiresAtEpochMs = start)))
        assertNull(timerStartEpochMs(timed.copy(id = BlockRule.SESSION_ID_PREFIX + "$start-x")))
    }

    @Test fun `finite duration choices have five minute then hourly steps`() {
        assertEquals((5..120 step 5).toList() + (3..24).map { it * 60 }, timerDurationChoices)
        assertFalse(0 in timerDurationChoices)
        assertFalse(validTimerDraft(draft.copy(durationMinutes = 0)))
        assertFalse(validTimerDraft(draft.copy(durationMinutes = 7)))
        assertFalse(validTimerDraft(draft.copy(durationMinutes = Int.MAX_VALUE)))
        assertEquals(30, TimerDraft().durationMinutes)
    }

    @Test fun `every preset is finite valid and keeps explicit app targets`() {
        assertEquals(listOf(90, 30, 60, 30, 60, 480, 1440, 2880, 7200), timerPresets.map { it.minutes })
        timerPresets.forEach { preset ->
            val presetDraft = draft.copy(durationMinutes = preset.minutes, presetId = preset.id)
            assertTrue(validTimerDraft(presetDraft))
            val rule = timerRule(presetDraft, 1000, preset.id)
            assertEquals(preset.name, rule.name)
            assertEquals(1000 + preset.minutes * 60_000L, rule.expiresAtEpochMs)
            assertEquals(setOf(app), rule.targets.apps)
            assertTrue(rule.targets.domains.isEmpty())
            assertFalse(rule.targets.allowListMode)
            assertNull(rule.schedule)
        }
    }

    @Test fun `timer snapshots targets and preserves each Flint tier`() {
        val mutableApps = mutableSetOf(app)
        val rule = timerRule(TimerDraft(apps = mutableApps), 1000, "one")
        mutableApps.clear()
        assertEquals(setOf(app), rule.targets.apps)
        assertTrue(rule.id.startsWith(BlockRule.SESSION_ID_PREFIX))
        BreakLevel.entries.forEach { tier ->
            assertEquals(tier, timerRule(draft.copy(breakLevel = tier), 1000, tier.name).breakLevel)
        }
    }

    @Test fun `empty or blank app selection is rejected`() {
        assertFalse(validTimerDraft(TimerDraft()))
        assertFalse(validTimerDraft(TimerDraft(apps = setOf(AppRef(" ")))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deadline overflow is rejected`() {
        timerRule(draft, Long.MAX_VALUE - 10, "overflow")
    }

    @Test fun `restore filters exact expiry disabled and unrelated rules but retains malformed old session`() {
        val active = timerRule(draft, 1000, "active")
        val expired = active.copy(id = "session-expired", expiresAtEpochMs = 5000)
        val disabled = active.copy(id = "session-disabled", enabled = false)
        val permanent = active.copy(id = "work", expiresAtEpochMs = null)
        val unknownEnd = active.copy(id = "session-older", expiresAtEpochMs = null)
        assertEquals(listOf(active, unknownEnd), activeTimerSessions(listOf(unknownEnd, disabled, permanent, expired, active), 5000))
    }

    @Test fun `legacy cancellation never applies to stronger tiers or native timers`() {
        val native = timerRule(draft, 1000, "one")
        assertFalse(canCancelTimer(native))
        val rule = native.copy(id = "session-legacy")
        assertTrue(canCancelTimer(rule))
        assertFalse(canCancelTimer(rule.copy(breakLevel = BreakLevel.HARDER)))
        assertFalse(canCancelTimer(rule.copy(breakLevel = BreakLevel.HARDCORE)))
        assertFalse(canCancelTimer(rule.copy(id = "work")))
    }

    @Test fun `native timer ending permits every tier but excludes legacy and scheduled rules`() {
        val native = timerRule(draft, 1000, "one")
        BreakLevel.entries.forEach { assertTrue(canEndNativeTimer(native.copy(breakLevel = it))) }
        assertFalse(canEndNativeTimer(native.copy(id = "session-legacy")))
        assertFalse(canEndNativeTimer(native.copy(id = "session-timer-")))
        assertFalse(canEndNativeTimer(native.copy(id = "work")))
        assertFalse(canEndNativeTimer(native.copy(enabled = false)))
    }

    @Test fun `countdown rounds up until exact boundary and includes days`() {
        assertEquals("00:01", timerCountdown(1))
        assertEquals("00:01", timerCountdown(1000))
        assertEquals("01:01", timerCountdown(60_001))
        assertEquals("01:00:00", timerCountdown(3_600_000))
        assertEquals("2d 00:00:00", timerCountdown(2 * 86_400_000L))
        assertEquals("00:00", timerCountdown(0))
        assertEquals("00:00", timerCountdown(-1))
    }

    @Test fun `duration labels cover short and detox sessions`() {
        assertEquals("30m", timerDurationLabel(30))
        assertEquals("1h 30m", timerDurationLabel(90))
        assertEquals("8h", timerDurationLabel(480))
        assertEquals("1d", timerDurationLabel(1440))
        assertEquals("5d", timerDurationLabel(7200))
    }
}
