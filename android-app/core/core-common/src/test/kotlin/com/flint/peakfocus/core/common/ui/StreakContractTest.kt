package com.flint.peakfocus.core.common.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform streak contract.
 *
 * Both platforms must draw the same loop from the same asset geometry and the same three
 * placements. This test pins the contract by value (frame count, frame duration, loop
 * length, placement sizes) instead of asserting source-code strings, so renaming a symbol
 * cannot break it while a real drift still does.
 *
 * It also asserts that each platform keeps exactly one flame renderer: the abandoned
 * vector renderer is gone and must not come back.
 */
class StreakContractTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "android-app").isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "could not locate repository root from ${System.getProperty("user.dir")}" }
    }

    private fun read(relative: String): String {
        val file = File(repoRoot, relative)
        assertTrue("missing expected source: $relative", file.isFile)
        return file.readText()
    }

    @Test
    fun `loop geometry is identical on both platforms`() {
        assertEquals(45, StreakAtlasFrames.FRAME_COUNT)
        assertEquals(9, StreakAtlasFrames.COLUMNS)
        assertEquals(60L, STREAK_FRAME_MS)
        assertEquals(2_700L, StreakAtlasFrames.FRAME_COUNT * STREAK_FRAME_MS)

        val ios = read("ios-app/Flint/Features/Stats/FlintStreakEmber.swift")
        assertTrue("iOS must advance one frame per 0.06 s", ios.contains("0.06"))
        assertTrue("iOS must wrap on the frame count", ios.contains("flameFrameIndex("))
    }

    @Test
    fun `shipped placements are badge 24, sheet 148, insights 160`() {
        assertEquals(24f, StreakFlamePlacement.Badge.value)
        assertEquals(148f, StreakFlamePlacement.Sheet.value)
        assertEquals(160f, StreakFlamePlacement.Insights.value)

        val ios = read("ios-app/Flint/Features/Stats/FlintStreakEmber.swift")
        assertTrue("iOS sheet placement must stay 148", ios.contains("size: 148") || ios.contains("size = 148"))
        val iosToday = read("ios-app/Flint/Features/Today/TodayView.swift")
        assertTrue("iOS badge placement must stay 24", iosToday.contains("size: 24") || iosToday.contains("size = 24"))
        val iosInsights = read("ios-app/Flint/Features/Stats/FocusHistorySection.swift")
        assertTrue("iOS insights placement must stay 160", iosInsights.contains("size: 160") || iosInsights.contains("size = 160"))
    }

    @Test
    fun `android keeps exactly one flame renderer`() {
        val android = read("android-app/core/core-common/src/main/kotlin/com/flint/peakfocus/core/common/ui/StreakFlame.kt")
        assertTrue("the atlas renderer must exist", android.contains("fun FlintStreakEmber("))
        assertFalse("the retired vector painter must not return", android.contains("class StreakFlamePainter"))
        assertFalse("there must be no second flame composable", android.contains("fun StreakFlame("))
        assertFalse("the retired state enum must not return", android.contains("enum class StreakFlameState"))
        assertFalse("the retired path builder must not return", android.contains("appleMainPath"))
    }

    @Test
    fun `ios keeps exactly one flame renderer`() {
        val ios = read("ios-app/Flint/Features/Stats/FlintStreakEmber.swift")
        assertTrue("the frame renderer must exist", ios.contains("struct FlintStreakEmber: View"))
        assertFalse("the retired vector body must not return", ios.contains("func drawEmber("))
        assertFalse("the retired geometry must not return", ios.contains("struct FlameGeometry"))
        assertFalse("the retired palette must not return", ios.contains("struct EmberPalette"))
    }
}
