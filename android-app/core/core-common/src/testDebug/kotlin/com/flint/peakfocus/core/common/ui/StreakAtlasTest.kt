package com.flint.peakfocus.core.common.ui

import androidx.compose.ui.graphics.toPixelMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Guards the one asset the streak flame actually draws: the pre-cropped fire atlas.
 *
 * Before this test, a missing, renamed or re-gridded atlas produced an empty canvas with
 * no failure anywhere in the build. These assertions fail loudly instead, and they cover
 * the shipped loader rather than a retired renderer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreakAtlasTest {

    private fun frames() = StreakAtlasFrames.get(RuntimeEnvironment.getApplication())

    @Test
    fun `atlas decodes into the shipped frame count`() {
        assertEquals(StreakAtlasFrames.FRAME_COUNT, frames().size)
        assertEquals(45, StreakAtlasFrames.FRAME_COUNT)
        assertEquals(9, StreakAtlasFrames.COLUMNS)
    }

    @Test
    fun `every frame is a full tile of the atlas`() {
        val decoded = frames()
        for (frame in decoded) {
            assertEquals(156, frame.width)
            assertEquals(187, frame.height)
        }
    }

    @Test
    fun `atlas frames are distinct so the loop really animates`() {
        val decoded = frames()
        val first = decoded.first().toPixelMap()
        val later = decoded[StreakAtlasFrames.FRAME_COUNT / 2].toPixelMap()
        var different = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (first[x, y] != later[x, y]) different++
            }
        }
        assertTrue("frames must differ, found $different changed pixels", different > 100)
    }

    @Test
    fun `first frame is not blank`() {
        val pixels = frames().first().toPixelMap()
        var opaque = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].alpha > 0.1f) opaque++
            }
        }
        assertTrue("first frame must draw the flame, opaque pixels = $opaque", opaque > 500)
    }
}
