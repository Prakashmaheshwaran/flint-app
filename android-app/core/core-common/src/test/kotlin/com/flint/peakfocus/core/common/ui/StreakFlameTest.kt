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
    fun `inactive flame emits no glow and no sparks at any sampled time`() {
        // 101 breath samples x flash on/off: total emitted light is always zero.
        for (i in 0..100) {
            val breath = i / 100f
            for (flash in listOf(0f, 1f)) {
                assertEquals(
                    0f,
                    haloAlpha(breath, flash) * flameIntensity(StreakFlameState.Inactive),
                    0f,
                )
            }
        }
        var seed = 0f
        while (seed < 1f) {
            assertEquals(0f, sparkAlpha(seed, flameIntensity(StreakFlameState.Inactive)), 0f)
            seed += 1f / 200f
        }
        // A frozen flicker rate means sparks never advance with time.
        assertEquals(
            sparkSeed(0.1, flickerRate(StreakFlameState.Inactive), 1),
            sparkSeed(0.9, flickerRate(StreakFlameState.Inactive), 1),
            0f,
        )
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
        // The old phase wrapped every 6s, jumping seeds by ~0.53 and flicker
        // sines mid-swing. Time is unwrapped now: sample tip, tilt, bob and
        // spark positions at 120fps across the 6s, 12s and 18s marks and require
        // every step to stay under the analytic max rate of change of its term
        // (halfW = unit = 1, flameH = 0.72, 1.5x headroom for float rounding).
        // Sparks respawn: a step is only checked while the spark is visible
        // (alpha gate mirrors the draw gate); a respawn wrap is new < old - 0.9.
        val dt = 1.0 / 120.0
        val margin = 1.5
        for (boundary in listOf(6.0, 12.0, 18.0)) {
            for (state in listOf(StreakFlameState.Active, StreakFlameState.AtRisk)) {
                val rate = flickerRate(state)
                // Analytic max rates: d/dt of each sine is amplitude * 2π * freq,
                // with frequencies in Hz (cycles per 6s phase divided by 6).
                val twoPi = 2.0 * PI
                val tipRate = rate * (0.05 * twoPi * 2.1 / 6.0 + 0.02 * twoPi * 3.7 / 6.0)
                val tiltRate = rate * (1.2 * twoPi * 0.9 / 6.0)
                val bobRate = rate * (0.004 * twoPi * 2.2 / 6.0)
                val seedRate = rate / 1.7
                val sxRate = tipRate * 0.4 + 5.0 * seedRate * 0.10
                val syRate = 0.72 * 0.75 * seedRate
                fun sxAt(t: Double, i: Int): Double {
                    val seed = sparkSeed(t, rate, i).toDouble()
                    return tipFlickerDx(t, rate, 1f).toDouble() * 0.4 +
                        sin(seed * 5.0 + i * 2.1) * 0.10
                }
                fun syAt(t: Double, i: Int): Double {
                    val seed = sparkSeed(t, rate, i).toDouble()
                    return -seed * 0.72 * 0.75
                }
                fun visible(t: Double, i: Int): Boolean =
                    sparkAlpha(sparkSeed(t, rate, i), 1f) > 0.05f

                var t = boundary - 0.1
                var prevTip = tipFlickerDx(t, rate, 1f).toDouble()
                var prevTilt = tiltDegrees(t, rate).toDouble()
                var prevBob = bobOffset(t, rate, 1f).toDouble()
                val prevSeed = DoubleArray(3) { sparkSeed(t, rate, it).toDouble() }
                val prevSx = DoubleArray(3) { sxAt(t, it) }
                val prevSy = DoubleArray(3) { syAt(t, it) }
                t += dt
                while (t <= boundary + 0.1 + dt / 2) {
                    val tip = tipFlickerDx(t, rate, 1f).toDouble()
                    val tilt = tiltDegrees(t, rate).toDouble()
                    val bob = bobOffset(t, rate, 1f).toDouble()
                    assertTrue("tip jump $tip at t=$t", abs(tip - prevTip) <= tipRate * dt * margin)
                    assertTrue("tilt jump $tilt at t=$t", abs(tilt - prevTilt) <= tiltRate * dt * margin)
                    assertTrue("bob jump $bob at t=$t", abs(bob - prevBob) <= bobRate * dt * margin)
                    for (i in 0..2) {
                        val seed = sparkSeed(t, rate, i).toDouble()
                        val step = seed - prevSeed[i]
                        // Either continuous, or a downward respawn wrap (never a mid jump).
                        assertTrue(
                            "seed jump $step at t=$t",
                            abs(step) <= seedRate * dt * margin || step < -0.9,
                        )
                        if (visible(t, i) && visible(t - dt, i)) {
                            val sx = sxAt(t, i)
                            val sy = syAt(t, i)
                            assertTrue("spark x jump at t=$t", abs(sx - prevSx[i]) <= sxRate * dt * margin)
                            assertTrue("spark y jump at t=$t", abs(sy - prevSy[i]) <= syRate * dt * margin)
                        }
                        prevSeed[i] = seed
                        prevSx[i] = sxAt(t, i)
                        prevSy[i] = syAt(t, i)
                    }
                    // The exact old failure: the pair straddling each boundary
                    // used to jump ~0.53 in seed. It must now be continuous
                    // (a downward respawn wrap is the only other legal move).
                    if (abs(t - boundary) < dt / 2) {
                        for (i in 0..2) {
                            val a = sparkSeed(t, rate, i)
                            val b = sparkSeed(t - dt, rate, i)
                            assertTrue(
                                "boundary seed jump at t=$t",
                                abs(a - b) < 0.02f || b - a > 0.9f,
                            )
                        }
                    }
                    prevTip = tip
                    prevTilt = tilt
                    prevBob = bob
                    t += dt
                }
            }
        }
    }

    @Test
    fun `first cycle matches the old wrapped phase formulas within tolerance`() {
        // Reference: the pre-fix float expressions, fed with the old wrapped phase.
        // Helpers reduce the argument in double precision first, so results agree
        // to float-rounding precision: asserted below 1e-4, far under visible motion.
        // The 1.0f rounding edge normalizes to 0; everything else must match.
        val twoPi = 2f * PI.toFloat()
        fun oldPhase(t: Double): Float = ((t / 6.0) % 1.0).toFloat()
        fun oldTip(t: Double, rate: Float, halfW: Float): Float {
            val fp = oldPhase(t) * rate
            return sin(fp * twoPi * 2.1f) * halfW * 0.05f +
                sin(fp * twoPi * 3.7f + 0.8f) * halfW * 0.02f
        }
        fun oldTilt(t: Double, rate: Float): Float {
            val fp = oldPhase(t) * rate
            return sin(fp * twoPi * 0.9f + 0.5f) * 1.2f
        }
        fun oldBob(t: Double, rate: Float, unit: Float): Float {
            val fp = oldPhase(t) * rate
            return sin(fp * twoPi * 2.2f) * unit * 0.004f
        }
        fun oldSeed(t: Double, rate: Float, index: Int): Float {
            val phase = oldPhase(t)
            return ((phase * 6f * rate / 1.7f + index * 0.37f) % 1f + 1f) % 1f
        }
        var worst = 0.0
        var t = 0.0
        while (t < 6.0) {
            for (rate in listOf(1f, 0.6f)) {
                worst = worst.coerceAtLeast(abs(oldTip(t, rate, 1f) - tipFlickerDx(t, rate, 1f)).toDouble())
                worst = worst.coerceAtLeast(abs(oldTilt(t, rate) - tiltDegrees(t, rate)).toDouble())
                worst = worst.coerceAtLeast(abs(oldBob(t, rate, 1f) - bobOffset(t, rate, 1f)).toDouble())
                for (i in 0..2) {
                    val expected = oldSeed(t, rate, i)
                    val reference = if (expected == 1.0f) 0f else expected
                    worst = worst.coerceAtLeast(abs(reference - sparkSeed(t, rate, i)).toDouble())
                }
            }
            t += 1.0 / 120.0
        }
        assertTrue("worst first-cycle diff $worst", worst < 1e-4)
    }

    @Test
    fun `long sessions track the double precision reference`() {
        // At 1h and 2h the unwrapped double reduction must still match a pure
        // double-precision reference: no drift accumulates because every frame
        // is computed fresh from t rather than integrated.
        fun refTip(t: Double, rate: Float): Double {
            val r = rate.toDouble()
            return 0.05 * sin(2.0 * PI * 2.1 * r * t / 6.0) +
                0.02 * sin(2.0 * PI * 3.7 * r * t / 6.0 + 0.8)
        }
        fun refTilt(t: Double, rate: Float): Double {
            val r = rate.toDouble()
            return 1.2 * sin(2.0 * PI * 0.9 * r * t / 6.0 + 0.5)
        }
        fun refBob(t: Double, rate: Float, unit: Double): Double {
            val r = rate.toDouble()
            return 0.004 * sin(2.0 * PI * 2.2 * r * t / 6.0) * unit
        }
        fun refSeed(t: Double, rate: Float, index: Int): Double {
            val x = t * rate.toDouble() / 1.7 + index * 0.37
            return ((x % 1.0) + 1.0) % 1.0
        }
        var worst = 0.0
        for (t in listOf(3600.0, 7200.0)) {
            for (rate in listOf(1f, 0.6f)) {
                worst = worst.coerceAtLeast(abs(refTip(t, rate) - tipFlickerDx(t, rate, 1f).toDouble()))
                worst = worst.coerceAtLeast(abs(refTilt(t, rate) - tiltDegrees(t, rate).toDouble()))
                worst = worst.coerceAtLeast(abs(refBob(t, rate, 1.0) - bobOffset(t, rate, 1f).toDouble()))
                for (i in 0..2) {
                    val expected = refSeed(t, rate, i)
                    val actual = sparkSeed(t, rate, i).toDouble()
                    // A reference value within one float-ulp below 1.0 legitimately
                    // becomes 0f through the float-1.0 normalization.
                    val reference = if (expected > 1.0 - 1e-6 && actual == 0.0) 0.0 else expected
                    worst = worst.coerceAtLeast(abs(reference - actual))
                }
            }
        }
        assertTrue("worst long-session diff $worst", worst < 1e-4)
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
        val (inner32, outer32) = fringeWidthsDp(32f)
        val (inner40, outer40) = fringeWidthsDp(40f)
        val (inner96, outer96) = fringeWidthsDp(96f)
        val (inner148, outer148) = fringeWidthsDp(148f)
        val (inner160, outer160) = fringeWidthsDp(160f)

        // The 24 dp badge gets roughly a 1 dp halo in total, not a 2.4 dp wash.
        assertEquals(0.6f, inner24, 0f)
        assertEquals(1.0f, outer24, 0f)
        assertTrue("24 dp halo ${inner24 + outer24} dp", inner24 + outer24 <= 1.6f)

        // 32 and 40 share the crisp compact tier and never soften past 1 dp.
        assertEquals(inner24, inner32, 0f)
        assertEquals(inner32, inner40, 0f)
        assertEquals(outer24, outer32, 0f)
        assertEquals(outer32, outer40, 0f)
        assertTrue("40 dp outer fringe $outer40", outer40 <= 1.0f)

        // Mid sizes step up, hero sizes keep the fuller two-pass treatment.
        assertEquals(1.0f, inner96, 0f)
        assertEquals(1.8f, outer96, 0f)
        assertEquals(1.4f, inner148, 0f)
        assertEquals(2.8f, outer148, 0f)
        assertEquals(inner148, inner160, 0f)
        assertEquals(outer148, outer160, 0f)
        assertTrue("hero must be fuller than compact", inner160 > inner40 && outer160 > outer40)
    }

    @Test
    fun `at risk ramp keeps amber at the base and cools upward`() {
        val stops = AtRiskBodyStops
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
        for (i in 1 until stops.size) {
            assertTrue("stop offsets must ascend", stops[i].first > stops[i - 1].first)
        }

        // Saturated amber at the fuel base: not tan (blue stays low, green stays high)
        // and not the legacy red warning colour.
        val base = stops.first().second
        assertTrue("base must stay bright red-orange", base.red >= 0.85f)
        assertTrue("base must stay saturated amber", base.green >= 0.55f)
        assertTrue("base must not wash out to tan", base.blue <= 0.35f)

        // Heat leaves the top first: luminance falls stop by stop toward the tip.
        fun luma(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        for (i in 1 until stops.size) {
            assertTrue(
                "luminance must cool upward at stop $i",
                luma(stops[i].second) < luma(stops[i - 1].second),
            )
        }

        // No red-dominant warning stop anywhere in the ramp.
        for ((_, color) in stops) {
            assertTrue(
                "red-dominant stop ${color.red}/${color.green}",
                !(color.red > 0.75f && color.green < 0.30f),
            )
        }
    }

    @Test
    fun `state cross fade is a short one shot that reduce motion can skip`() {
        // Long enough to hide the palette swap, short enough to read as a transition.
        assertTrue(STATE_CROSSFADE_MS in 180..500)
    }
}
