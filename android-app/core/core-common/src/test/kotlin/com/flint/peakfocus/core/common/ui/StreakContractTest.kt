package com.flint.peakfocus.core.common.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streak contract, asserted by value on this platform.
 *
 * Loop geometry and placements are pinned here from Android production declarations. The
 * iOS suite pins the same numbers natively (`FocusStreakCopyTests`: frame interval, frame
 * index, placements and the shipped 45-frame asset), so neither side reads the other's
 * source for values.
 *
 * The renderer checks scan the whole Android main source tree, not one file, so a
 * reintroduced second renderer in a new file is still caught. The equivalent iOS scan lives
 * in `StreakRendererContractTests` and runs in iOS CI.
 */
class StreakContractTest {

    private val repoRoot: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "android-app").isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "could not locate repository root from ${System.getProperty("user.dir")}" }
    }

    private fun androidMainSources(): List<File> =
        File(repoRoot, "android-app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()

    private fun List<File>.declaring(fragment: String): List<String> =
        filter { it.readText().contains(fragment) }.map { it.name }.sorted()

    @Test
    fun `loop constants match the shared contract`() {
        assertEquals(45, StreakAtlasFrames.FRAME_COUNT)
        assertEquals(9, StreakAtlasFrames.COLUMNS)
        assertEquals(60L, STREAK_FRAME_MS)
        assertEquals(2_700L, StreakAtlasFrames.FRAME_COUNT * STREAK_FRAME_MS)
    }

    @Test
    fun `shipped placements are badge 24, sheet 148, insights 160`() {
        assertEquals(24f, StreakFlamePlacement.Badge.value)
        assertEquals(148f, StreakFlamePlacement.Sheet.value)
        assertEquals(160f, StreakFlamePlacement.Insights.value)
    }

    @Test
    fun `android keeps exactly one flame renderer`() {
        val main = androidMainSources()
        assertEquals(listOf("StreakFlame.kt"), main.declaring("fun FlintStreakEmber("))
        assertTrue("the retired painter must not return",
            main.declaring("class StreakFlamePainter").isEmpty())
        assertTrue("there must be no second flame composable",
            main.declaring("fun StreakFlame(").isEmpty())
        assertTrue("the retired state enum must not return",
            main.declaring("enum class StreakFlameState").isEmpty())
        assertTrue("the retired path builder must not return",
            main.declaring("appleMainPath").isEmpty())
    }
}
