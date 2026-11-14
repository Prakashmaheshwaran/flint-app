package com.flint.peakfocus.core.common.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin

class StreakFlameTest {

    @Test
    fun `flameTick advances one tick per frame and never rounds up`() {
        // 6 seconds at 30fps, sampled 10x per frame: every sample maps to floor(t * 30).
        var t = 0.0
        while (t < 6.0) {
            assertEquals((t * 30.0).toLong(), flameTick(t, 30.0))
            t += 1.0 / 300.0
        }
        // A mid-frame timestamp stays on the earlier tick.
        assertEquals(7L, flameTick(7.5 / 30.0, 30.0))
        assertEquals(14L, flameTick(14.5 / 30.0, 30.0))
        // Other frame rates quantize the same way.
        assertEquals(6L, flameTick(0.5, 12.0))
        assertEquals(90L, flameTick(1.5, 60.0))
    }

    @Test
    fun `out of domain time maps to tick zero instead of going negative`() {
        assertEquals(0L, flameTick(-1.0, 30.0))
        assertEquals(0L, flameTick(-0.001, 30.0))
    }

    @Test
    fun `breathing stays within four percent and reaches both extremes`() {
        // Four full 1.5s cycles, 1200 samples: scale must live in [0.96, 1.04].
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var t = 0.0
        while (t < 6.0) {
            val s = breathScale(breathPhase01(t))
            assertTrue("scale $s out of range at t=$t", s >= 0.96f && s <= 1.04f)
            if (s < min) min = s
            if (s > max) max = s
            t += 6.0 / 1200.0
        }
        // Extremes are actually reached, not just theoretically inside.
        assertTrue("min $min never nears 0.96", min < 0.965f)
        assertTrue("max $max never nears 1.04", max > 1.035f)
    }

    @Test
    fun `glow alpha stays within quarter to point four five and reaches both ends`() {
        // 1001 samples across the full breath range, no milestone flash.
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0..1000) {
            val a = haloAlpha(i / 1000f, 0f)
            assertTrue("glow $a out of range", a >= 0.25f && a <= 0.45f)
            if (a < min) min = a
            if (a > max) max = a
        }
        assertTrue("glow min $min never reaches 0.25", min < 0.251f)
        assertTrue("glow max $max never reaches 0.45", max > 0.449f)
        // A full milestone flash lifts the glow without clipping.
        assertEquals(0.60f, haloAlpha(1f, 1f), 1e-6f)
    }

    @Test
    fun `sparks stagger spawn times and fade toward dark at both ends of life`() {
        // Every seed across a full 6s cycle lives in [0, 1).
        var t = 0.0
        while (t < 6.0) {
            for (i in 0..2) {
                val seed = sparkSeed(t, 1f, i)
                assertTrue("seed $seed out of range", seed >= 0f && seed < 1f)
            }
            t += 6.0 / 1200.0
        }
        // At any single tick the three sparks never share a phase.
        val tickSeeds = (0..2).map { sparkSeed(elapsedSeconds = 2.22, rate = 1f, index = it) }
        for (a in tickSeeds.indices) {
            for (b in a + 1 until tickSeeds.size) {
                val gap = kotlin.math.abs(tickSeeds[a] - tickSeeds[b])
                val circular = minOf(gap, 1f - gap)
                assertTrue("sparks $a and $b share a phase", circular > 0.1f)
            }
        }
        // The envelope is dark at spawn and at end-of-life, bright mid-flight.
        // It is never exactly zero except at seed 0; darkness at end of life
        // holds only in the limit, so the probe sits just short of the wrap.
        assertEquals(0f, sparkAlpha(0f, 1f), 1e-6f)
        // sin(0.9999 * pi) ~= 3.1e-4, so the envelope is ~2.4e-4 here: dark at end-of-life.
        assertTrue(sparkAlpha(0.9999f, 1f) < 1e-3f)
        assertTrue(sparkAlpha(0.5f, 1f) > 0.7f)
    }

    @Test
    fun `inactive flame is dim, static, and emits no animated sparks`() {
        assertTrue(flameIntensity(StreakFlameState.Inactive) in 0.2f..0.6f)
        assertEquals(0f, flickerRate(StreakFlameState.Inactive), 0f)
        assertEquals(
            sparkSeed(0.1, flickerRate(StreakFlameState.Inactive), 1),
            sparkSeed(0.9, flickerRate(StreakFlameState.Inactive), 1),
            0f,
        )
        assertEquals(tipFlickerDx(0.2, 0f, 10f), tipFlickerDx(3.4, 0f, 10f), 0f)
    }

    @Test
    fun `at risk flicker runs slower than active over a full cycle`() {
        // Count sign changes of the tip-flicker sine over one 6s phase cycle,
        // sampling 6000 points, with each state's production flicker rate.
        fun crossings(rate: Float): Int {
            var count = 0
            var previous = sin(0.0 * rate * 2.0 * PI * 2.1)
            for (k in 1..6000) {
                val phase = 6.0 * k / 6000.0
                val current = sin(phase * rate * 2.0 * PI * 2.1)
                if (previous == 0.0 || current == 0.0 || previous.sign != current.sign) {
                    count++
                }
                previous = current
            }
            return count
        }
        val activeRate = flickerRate(StreakFlameState.Active)
        val atRiskRate = flickerRate(StreakFlameState.AtRisk)
        val activeCrossings = crossings(activeRate)
        val atRiskCrossings = crossings(atRiskRate)
        assertTrue("active must oscillate (got $activeCrossings)", activeCrossings > 10)
        assertTrue("at-risk must oscillate less (got $atRiskCrossings)", atRiskCrossings < activeCrossings)
        val ratio = atRiskCrossings.toDouble() / activeCrossings.toDouble()
        assertTrue("flicker ratio $ratio not near 0.6x", ratio > 0.5 && ratio < 0.7)
    }

    @Test
    fun `motion is continuous across the old six second boundary`() {
        val dt = 1.0 / 120.0
        for (boundary in listOf(6.0, 12.0, 18.0)) {
            for (state in listOf(StreakFlameState.Active, StreakFlameState.AtRisk)) {
                val rate = flickerRate(state)
                var t = boundary - 0.1
                var prevTip = tipFlickerDx(t, rate, 1f).toDouble()
                var prevTilt = tiltDegrees(t, rate).toDouble()
                var prevBob = bobOffset(t, rate, 1f).toDouble()
                val prevSeed = DoubleArray(3) { sparkSeed(t, rate, it).toDouble() }
                t += dt
                while (t <= boundary + 0.1 + dt / 2) {
                    val tip = tipFlickerDx(t, rate, 1f).toDouble()
                    val tilt = tiltDegrees(t, rate).toDouble()
                    val bob = bobOffset(t, rate, 1f).toDouble()
                    assertTrue(kotlin.math.abs(tip - prevTip) < 0.02)
                    assertTrue(kotlin.math.abs(tilt - prevTilt) < 0.05)
                    assertTrue(kotlin.math.abs(bob - prevBob) < 0.002)
                    for (k in 0..2) {
                        val seed = sparkSeed(t, rate, k).toDouble()
                        val step = seed - prevSeed[k]
                        assertTrue(kotlin.math.abs(step) < 0.02 || step < -0.9)
                        prevSeed[k] = seed
                    }
                    if (kotlin.math.abs(t - boundary) < dt / 2) {
                        for (k in 0..2) {
                            val a = sparkSeed(t, rate, k)
                            val b = sparkSeed(t - dt, rate, k)
                            assertTrue(kotlin.math.abs(a - b) < 0.02f || b - a > 0.9f)
                        }
                    }
                    prevTip = tip; prevTilt = tilt; prevBob = bob
                    t += dt
                }
            }
        }
    }

    @Test
    fun `motion terms stay bounded and deterministic`() {
        for (t in listOf(0.0, 0.3, 1.2, 2.7, 5.9, 8.4)) {
            val tip = tipFlickerDx(t, 1f, 10f)
            val tilt = tiltDegrees(t, 1f)
            val bob = bobOffset(t, 1f, 10f)
            assertTrue(kotlin.math.abs(tip) <= 1.5f)
            assertTrue(kotlin.math.abs(tilt) <= 2.5f)
            assertTrue(kotlin.math.abs(bob) <= 0.2f)
            assertEquals(tip, tipFlickerDx(t, 1f, 10f), 0f)
        }
    }

    @Test
    fun `spark position jumps only where drawn alpha is below threshold`() {
        // A rising spark's seed wraps 1.0 -> 0.0 once per 1.7s/rate cycle,
        // teleporting the dot from tip back to base. That relocation is only
        // invisible when the drawn alpha is under the 0.03 draw gate on both
        // sides of the wrap; this test fails if any wrap is ever visible.
        for (rate in listOf(1f, 0.6f)) {
            var wraps = 0
            for (i in 0..2) {
                var t = 0.0
                var prev = sparkSeed(t, rate, i)
                while (t < 2 * 1.7 / rate) {
                    t += 1.0 / 240.0
                    val cur = sparkSeed(t, rate, i)
                    if (prev > 0.9f && cur < 0.1f) {
                        wraps++
                        assertTrue("alpha before wrap at t=$t", sparkAlpha(prev, 1f) < 0.03f)
                        assertTrue("alpha after wrap at t=$t", sparkAlpha(cur, 1f) < 0.03f)
                    }
                    prev = cur
                }
            }
            assertTrue("no wraps observed for rate $rate", wraps > 0)
        }
    }

    @Test
    fun `a resumed loop continues the paused phase instead of the static pose`() {
        // A pause (off-screen, unfocused) freezes `elapsed`; the loop must re-anchor on
        // that value. Snapping back to STATIC_FLAME_SECONDS would jump the pose on every
        // return from the stats list.
        val paused = 12.5
        assertEquals(paused, frameElapsed(paused, 1_000L, 1_000L), 0.0)
        assertTrue("resume snaps to the static pose", frameElapsed(paused, 1_000L, 1_000L) > STATIC_FLAME_SECONDS)
        // Frames after a resume advance by the real elapsed time, not from zero.
        assertEquals(paused + 0.5, frameElapsed(paused, 1_000L, 500_001_000L), 1e-9)
        val dt = 1.0 / 30.0
        val frameNanos = 33_333_333L
        assertEquals(
            dt,
            frameElapsed(paused, 1_000L, 500_001_000L) -
                frameElapsed(paused, 1_000L, 500_001_000L - frameNanos),
            1e-6,
        )
        // The very first launch anchors on the static pose, so entry does not jump either.
        assertEquals(
            STATIC_FLAME_SECONDS,
            frameElapsed(STATIC_FLAME_SECONDS, 0L, 0L),
            0.0,
        )
    }

    @Test
    fun `mood mappers round trip across the full closed domain`() {
        // Both enums are closed: exhaustive `when` is compiler-checked, so every
        // entry must survive a round trip with no documented default needed.
        assertEquals(3, StreakEmberMood.values().size)
        assertEquals(3, StreakFlameState.values().size)
        StreakEmberMood.values().forEach { mood ->
            assertEquals(mood, mood.toFlameState().toEmberMood())
        }
        StreakFlameState.values().forEach { state ->
            assertEquals(state, state.toEmberMood().toFlameState())
        }
    }

    @Test
    fun `fringe narrows at badge sizes and widens at hero sizes`() {
        val (inner24, outer24) = fringeWidthsDp(24f)
        val (inner40, outer40) = fringeWidthsDp(40f)
        val (inner96, outer96) = fringeWidthsDp(96f)
        val (inner148, outer148) = fringeWidthsDp(148f)
        val (inner160, outer160) = fringeWidthsDp(160f)
        assertEquals(0.7f, inner24, 0f)
        assertEquals(1.1f, outer24, 0f)
        assertEquals(inner24, inner40, 0f)
        assertEquals(outer24, outer40, 0f)
        assertTrue(inner96 > inner40)
        assertTrue(inner148 > inner96)
        assertEquals(1.0f, inner96, 0f)
        assertEquals(1.4f, inner148, 0f)
        assertEquals(1.4f, inner160, 0f)
        assertEquals(1.8f, outer96, 0f)
        assertEquals(2.6f, outer148, 0f)
        assertEquals(2.6f, outer160, 0f)
    }

    @Test
    fun `at risk ramp keeps warm amber tones and cools upward`() {
        val stops = AtRiskBodyStops
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
        for (i in 1 until stops.size) assertTrue("stop offsets must ascend", stops[i].first > stops[i - 1].first)
        val base = stops.first().second
        assertTrue(base.red >= 0.85f); assertTrue(base.green >= 0.55f)
        fun luma(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        for (i in 1 until stops.size) assertTrue(luma(stops[i].second) < luma(stops[i - 1].second))
        for ((_, color) in stops) assertTrue(!(color.red > 0.85f && color.green < 0.20f))
    }

    @Test
    fun `state cross fade is a short one shot that reduce motion can skip`() {
        // Long enough to hide the palette swap, short enough to read as a transition.
        assertTrue(STATE_CROSSFADE_MS in 180..500)
    }
}
