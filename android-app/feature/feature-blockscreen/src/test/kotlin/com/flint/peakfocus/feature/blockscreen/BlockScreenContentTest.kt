package com.flint.peakfocus.feature.blockscreen

import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
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
        breakWaitTotalMillis: Long? = null,
    ) = BlockScreenState(
        packageName = packageName,
        appLabel = appLabel,
        reason = reason,
        breakLevel = breakLevel,
        remainingMillis = remainingMillis,
        breakWaitRemainingMillis = breakWaitRemainingMillis,
        breakWaitTotalMillis = breakWaitTotalMillis,
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
    fun `harder wait carries determinate progress when the host supplies the total`() {
        val content = blockScreenContent(
            state(
                breakLevel = BreakLevel.HARDER,
                breakWaitRemainingMillis = 150_000L,
                breakWaitTotalMillis = 600_000L,
            ),
        )
        assertEquals(
            BreakAffordance.WaitBeforeBreak(remainingMillis = 150_000L, progress = 0.75f),
            content.breakAffordance,
        )
    }

    // ---- Wait-ring progress math ----

    @Test
    fun `wait progress is the elapsed fraction`() {
        assertEquals(0.5f, waitProgress(totalMillis = 600_000L, remainingMillis = 300_000L))
        assertEquals(0f, waitProgress(totalMillis = 600_000L, remainingMillis = 600_000L))
    }

    @Test
    fun `wait progress is null without a usable total - text-only notice`() {
        assertEquals(null, waitProgress(totalMillis = null, remainingMillis = 300_000L))
        assertEquals(null, waitProgress(totalMillis = 0L, remainingMillis = 300_000L))
        assertEquals(null, waitProgress(totalMillis = -600_000L, remainingMillis = 300_000L))
    }

    @Test
    fun `wait progress clamps host clock skew into range`() {
        // remaining > total (clock set back): never below 0.
        assertEquals(0f, waitProgress(totalMillis = 600_000L, remainingMillis = 900_000L))
        // negative remaining (host rendered late): never above 1.
        assertEquals(1f, waitProgress(totalMillis = 600_000L, remainingMillis = -5_000L))
    }

    @Test
    fun `hardcore offers no bypass - emergency pass only`() {
        val content = blockScreenContent(state(breakLevel = BreakLevel.HARDCORE))
        assertEquals(BreakAffordance.EmergencyPassOnly, content.breakAffordance)
    }

    @Test
    fun `hardcore ignores a stray cooldown - still emergency pass only`() {
        val content = blockScreenContent(
            state(breakLevel = BreakLevel.HARDCORE, breakWaitRemainingMillis = 5_000L),
        )
        assertEquals(BreakAffordance.EmergencyPassOnly, content.breakAffordance)
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

    // ---- Spoken countdowns + accessibility copy ----

    @Test
    fun `spoken form mirrors the visual unit choices in words`() {
        assertEquals("42 seconds", formatDurationSpoken(42_000L))
        assertEquals("4 minutes 32 seconds", formatDurationSpoken(272_000L))
        assertEquals("1 hour 24 minutes", formatDurationSpoken(5_040_000L))
        assertEquals("0 seconds", formatDurationSpoken(-5_000L))
    }

    @Test
    fun `spoken form uses singular units where the value is one`() {
        assertEquals("1 minute 1 second", formatDurationSpoken(61_000L))
        assertEquals("1 hour 0 minutes", formatDurationSpoken(3_600_000L))
    }

    @Test
    fun `wait and unblock announcements carry the spoken countdown`() {
        assertEquals("Break available in 4 minutes 32 seconds", waitNoticeA11y(272_000L))
        assertEquals("Unblocks in 42 seconds", unblockCountdownA11y(42_000L))
    }

    @Test
    fun `hold control accessibility copy is honest about both ways to operate it`() {
        // The action label is what TalkBack appends after "double-tap to…".
        assertTrue(HOLD_BREAK_A11Y_LABEL.isNotBlank())
        assertTrue(HOLD_BREAK_A11Y_DESCRIPTION.contains("hold", ignoreCase = true))
        assertTrue(HOLD_BREAK_A11Y_DESCRIPTION.contains("double-tap", ignoreCase = true))
    }
}
