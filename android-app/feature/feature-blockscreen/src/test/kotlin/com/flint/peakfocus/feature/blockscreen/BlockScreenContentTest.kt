package com.flint.peakfocus.feature.blockscreen

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the state→content mapping that drives [BlockScreen]. The Compose layer
 * is a thin projection of [BlockScreenContent]; this is where the actual logic lives.
 */
class BlockScreenContentTest {

    private fun state(
        breakLevel: BreakLevel = BreakLevel.EASY,
        reason: BlockScreenReason = BlockScreenReason.SCHEDULE,
        packageName: String = "com.instagram.android",
        appLabel: String? = "Instagram",
        remainingMillis: Long? = null,
        breakWaitRemainingMillis: Long? = null,
        emergencyPassAvailable: Boolean = true,
    ) = BlockScreenState(
        packageName = packageName,
        appLabel = appLabel,
        reason = reason,
        breakLevel = breakLevel,
        remainingMillis = remainingMillis,
        breakWaitRemainingMillis = breakWaitRemainingMillis,
        emergencyPassAvailable = emergencyPassAvailable,
    )

    // ---- Break-level → affordance mapping ----

    @Test
    fun `easy offers a free break`() {
        val content = blockScreenContent(state(breakLevel = BreakLevel.EASY))
        assertEquals(BreakAffordance.TakeABreak, content.breakAffordance)
    }

    @Test
    fun `easy ignores a stray cooldown - breaks stay free`() {
        val content = blockScreenContent(
            state(breakLevel = BreakLevel.EASY, breakWaitRemainingMillis = 60_000L),
        )
        assertEquals(BreakAffordance.TakeABreak, content.breakAffordance)
    }

    @Test
    fun `harder without cooldown offers hold-to-request with the module hold duration`() {
        val content = blockScreenContent(state(breakLevel = BreakLevel.HARDER))
        assertEquals(BreakAffordance.HoldToRequest(BREAK_HOLD_MILLIS), content.breakAffordance)
    }

    @Test
    fun `uninstall guard offers no affordance at any tier`() {
        // The guard re-shields regardless of exemptions, so any offered exit — especially
        // the weekly Emergency Pass — would be spent for nothing (review regression).
        for (level in BreakLevel.entries) {
            val content = blockScreenContent(
                state(breakLevel = level, reason = BlockScreenReason.UNINSTALL_GUARD),
            )
            assertEquals(BreakAffordance.None, content.breakAffordance)
        }
    }

    @Test
    fun `harder with zero cooldown offers hold-to-request`() {
        val content = blockScreenContent(
            state(breakLevel = BreakLevel.HARDER, breakWaitRemainingMillis = 0L),
        )
        assertEquals(BreakAffordance.HoldToRequest(BREAK_HOLD_MILLIS), content.breakAffordance)
    }

    @Test
    fun `harder with pending cooldown shows the wait notice with the host's remaining time`() {
        val content = blockScreenContent(
            state(breakLevel = BreakLevel.HARDER, breakWaitRemainingMillis = 272_000L),
        )
        assertEquals(BreakAffordance.WaitBeforeBreak(272_000L), content.breakAffordance)
    }

    @Test
    fun `hardcore offers no bypass - emergency pass only`() {
        val content = blockScreenContent(state(breakLevel = BreakLevel.HARDCORE))
        assertEquals(BreakAffordance.EmergencyPassOnly(passAvailable = true), content.breakAffordance)
    }

    @Test
    fun `hardcore ignores a stray cooldown - still emergency pass only`() {
        val content = blockScreenContent(
            state(breakLevel = BreakLevel.HARDCORE, breakWaitRemainingMillis = 5_000L),
        )
        assertEquals(BreakAffordance.EmergencyPassOnly(passAvailable = true), content.breakAffordance)
    }

    @Test
    fun `hardcore with a spent weekly pass surfaces the unavailable state`() {
        // The host says the pass is gone: the CTA must not render as a live button whose
        // tap silently no-ops — the affordance carries the availability for the UI to show.
        val content = blockScreenContent(
            state(breakLevel = BreakLevel.HARDCORE, emergencyPassAvailable = false),
        )
        assertEquals(BreakAffordance.EmergencyPassOnly(passAvailable = false), content.breakAffordance)
    }

    // ---- Display name + headline ----

    @Test
    fun `display name prefers the app label`() {
        assertEquals("Instagram", blockScreenContent(state()).displayName)
    }

    @Test
    fun `display name falls back to the package when the label is null`() {
        val content = blockScreenContent(state(appLabel = null, packageName = "com.android.chrome"))
        assertEquals("com.android.chrome", content.displayName)
    }

    @Test
    fun `display name falls back to the package when the label is blank`() {
        val content = blockScreenContent(state(appLabel = "  ", packageName = "com.android.chrome"))
        assertEquals("com.android.chrome", content.displayName)
    }

    @Test
    fun `headline names the blocked app`() {
        assertEquals("Instagram can wait", blockScreenContent(state()).headline)
    }

    // ---- Reason copy ----

    @Test
    fun `every reason maps to a distinct non-blank line`() {
        val lines = BlockScreenReason.entries.map { reasonLineFor(it) }
        assertTrue(lines.all { it.isNotBlank() })
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test
    fun `deep focus copy names deep focus`() {
        assertTrue(reasonLineFor(BlockScreenReason.DEEP_FOCUS).contains("Deep Focus"))
    }

    // ---- Cause badge ----

    @Test
    fun `every reason maps to a distinct non-blank badge`() {
        val texts = BlockScreenReason.entries.map { badgeFor(it, BreakLevel.EASY).text }
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(texts.size, texts.toSet().size)
    }

    @Test
    fun `badge is emphasized at hardcore whatever the reason`() {
        for (reason in BlockScreenReason.entries) {
            assertTrue(badgeFor(reason, BreakLevel.HARDCORE).emphasized)
        }
    }

    @Test
    fun `badge is emphasized for the uninstall guard at every tier`() {
        for (level in BreakLevel.entries) {
            assertTrue(badgeFor(BlockScreenReason.UNINSTALL_GUARD, level).emphasized)
        }
    }

    @Test
    fun `badge stays quiet for easy and harder app blocks`() {
        // Emphasis is reserved for the unbreakable shapes; everything else keeps the
        // matte badge so the one ember accent doesn't cheapen.
        for (level in listOf(BreakLevel.EASY, BreakLevel.HARDER)) {
            for (reason in BlockScreenReason.entries - BlockScreenReason.UNINSTALL_GUARD) {
                assertFalse(badgeFor(reason, level).emphasized)
            }
        }
    }

    @Test
    fun `content carries the badge for its state`() {
        val content = blockScreenContent(
            state(breakLevel = BreakLevel.HARDCORE, reason = BlockScreenReason.DEEP_FOCUS),
        )
        assertEquals(BlockScreenBadge("Deep Focus", emphasized = true), content.badge)
    }

    // ---- Encouragement ----

    @Test
    fun `encouragement is deterministic per package and non-blank`() {
        val first = encouragementFor("com.instagram.android")
        val second = encouragementFor("com.instagram.android")
        assertEquals(first, second)
        assertTrue(first.isNotBlank())
    }

    @Test
    fun `encouragement never crashes on hostile hash codes`() {
        // A raw abs(hashCode) % size would go negative for Int.MIN_VALUE-style hashes;
        // the floored mod must always land in range.
        val line = encouragementFor("polygenelubricants") // classic Int.MIN_VALUE hashCode string
        assertTrue(line.isNotBlank())
    }

    // ---- Countdown label ----

    @Test
    fun `open-ended block has no countdown label`() {
        assertEquals(null, countdownLabelFor(BlockScreenReason.TIME_LIMIT, null))
        assertEquals(null, blockScreenContent(state(remainingMillis = null)).countdownLabel)
    }

    @Test
    fun `daily limits count down to a reset`() {
        assertEquals("Resets in 3h 20m", countdownLabelFor(BlockScreenReason.TIME_LIMIT, 12_000_000L))
        assertEquals("Resets in 3h 20m", countdownLabelFor(BlockScreenReason.OPEN_LIMIT, 12_000_000L))
    }

    @Test
    fun `timed blocks count down to unblocking`() {
        assertEquals("Unblocks in 24m 0s", countdownLabelFor(BlockScreenReason.MANUAL_SESSION, 1_440_000L))
        assertEquals("Unblocks in 24m 0s", countdownLabelFor(BlockScreenReason.SCHEDULE, 1_440_000L))
        assertEquals("Unblocks in 24m 0s", countdownLabelFor(BlockScreenReason.DEEP_FOCUS, 1_440_000L))
        assertEquals("Unblocks in 24m 0s", countdownLabelFor(BlockScreenReason.UNINSTALL_GUARD, 1_440_000L))
    }

    @Test
    fun `content carries the reason-aware countdown`() {
        val content = blockScreenContent(
            state(reason = BlockScreenReason.TIME_LIMIT, remainingMillis = 12_000_000L),
        )
        assertEquals("Resets in 3h 20m", content.countdownLabel)
    }

    // ---- Countdown formatting ----

    @Test
    fun `formats seconds only`() {
        assertEquals("42s", formatDuration(42_000L))
        assertEquals("59s", formatDuration(59_999L))
    }

    @Test
    fun `formats minutes with seconds`() {
        assertEquals("1m 0s", formatDuration(60_000L))
        assertEquals("4m 32s", formatDuration(272_000L))
    }

    @Test
    fun `formats hours with minutes`() {
        assertEquals("1h 0m", formatDuration(3_600_000L))
        assertEquals("1h 24m", formatDuration(5_040_000L))
        assertEquals("25h 1m", formatDuration(90_061_000L))
    }

    @Test
    fun `clamps zero and negative to zero seconds`() {
        assertEquals("0s", formatDuration(0L))
        assertEquals("0s", formatDuration(-5_000L))
    }
}
