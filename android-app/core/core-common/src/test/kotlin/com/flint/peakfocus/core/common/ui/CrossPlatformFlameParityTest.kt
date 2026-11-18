package com.flint.peakfocus.core.common.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shared flame specification across Android and iOS after flame-correction redesign.
 */
class CrossPlatformFlameParityTest {

    private val root: File = repoRoot()
    private val android: String = read("android-app/core/core-common/src/main/kotlin/com/flint/peakfocus/core/common/ui/StreakFlame.kt")
    private val androidWrapper: String = read("android-app/core/core-common/src/main/kotlin/com/flint/peakfocus/core/common/ui/StreakFlame.kt")
    private val ios: String = read("ios-app/Flint/Features/Stats/FlintStreakEmber.swift")

    @Test
    fun `renderers share core geometry and timing`() {
        assertBoth("val w: Float = s * 0.76f", "w = s * 0.76", "width scale")
        assertBoth("val h: Float = s * 0.90f", "h = s * 0.90", "height scale")
        assertBoth("val baseY: Float = s * 0.95f", "baseY = s * 0.95", "base anchor")
        assertBoth("STATIC_FLAME_SECONDS: Double = 0.72", "STATIC_FLAME_SECONDS: Double = 0.72", "static pose")
        assertBoth("STATE_CROSSFADE_MS: Int = 320", "STATE_CROSSFADE_SECONDS: Double = 0.32", "state cross-fade")
        assertBoth("StreakFlameState.AtRisk -> 0.6f", "mood == .warning ? 0.6 : 1.0", "at-risk rate")
    }

    @Test
    fun `dormant preserves flame identity not ash mound`() {
        assertTrue(android.contains("Recognizable low-intensity flame"))
        assertTrue(android.contains("DormantBodyStops"))
        assertTrue(android.contains("private fun drawAsh"))
        assertTrue(android.contains("appleMainPath"))
        assertTrue(android.contains("appleLickPath"))
        assertTrue(ios.contains("Recognizable low-intensity flame"))
        assertTrue(ios.contains("dormant"))
        assertTrue(ios.contains("private func drawAsh"))
        assertTrue(ios.contains("func buildAsh"))
        assertFalse(android.contains("grey mountain"))
        assertFalse(ios.contains("grey mountain"))
    }

    @Test
    fun `there is exactly one renderer per platform`() {
        val mainMarker = File.separator + "src" + File.separator + "main" + File.separator
        val androidSources = File(root, "android-app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains(mainMarker) }
            .map { it.readText() }
            .toList()
        assertEquals(1, androidSources.sumOf { Regex("""fun StreakFlame\(""").findAll(it).count() })
        for (function in listOf("appleMainPath", "appleLickPath")) {
            assertEquals(1, androidSources.sumOf { Regex("""fun $function\(""").findAll(it).count() })
        }
        val iosSources = File(root, "ios-app/Flint").walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .map { it.readText() }
            .toList()
        assertEquals(1, iosSources.sumOf { Regex("""struct FlintStreakEmber: View""").findAll(it).count() })
        assertEquals(1, iosSources.sumOf { Regex("""func drawEmber\(""").findAll(it).count() })
        assertEquals(1, iosSources.sumOf { Regex("""func drawAsh\(""").findAll(it).count() })
        assertEquals(1, iosSources.sumOf { Regex("""func buildOuter\(""").findAll(it).count() })
        assertEquals(1, iosSources.sumOf { Regex("""func buildCore\(""").findAll(it).count() })
        assertTrue(androidWrapper.contains("StreakFlame("))
    }

    @Test
    fun `all six placements use the approved sizes`() {
        val androidToday = read("android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/TimerScreen.kt")
        val androidInsights = read("android-app/feature/feature-stats/src/main/kotlin/com/flint/peakfocus/feature/stats/FocusSessionsSection.kt")
        val androidSheet = read("android-app/core/core-common/src/main/kotlin/com/flint/peakfocus/core/common/ui/StreakFlame.kt")
        val iosToday = read("ios-app/Flint/Features/Today/TodayView.swift")
        val iosInsights = read("ios-app/Flint/Features/Stats/FocusHistorySection.swift")
        val iosSheet = read("ios-app/Flint/Features/Stats/FlintStreakEmber.swift")
        assertTrue(Regex("""size\s*=\s*24\.dp\b""").containsMatchIn(androidToday))
        assertTrue(Regex("""size\s*=\s*160\.dp\b""").containsMatchIn(androidInsights) || Regex("""160\.dp\b""").containsMatchIn(androidInsights))
        assertTrue(Regex("""size\s*=\s*148\.dp\b""").containsMatchIn(androidSheet))
        assertTrue(Regex("""size:\s*24\b""").containsMatchIn(iosToday))
        assertTrue(Regex("""size:\s*160\b""").containsMatchIn(iosInsights))
        assertTrue(Regex("""size:\s*148\b""").containsMatchIn(iosSheet))
        assertTrue(androidSheet.contains("StreakFlame("))
    }

    private fun assertBoth(androidToken: String, iosToken: String, what: String) {
        assertTrue("$what missing on Android: $androidToken", android.contains(androidToken))
        assertTrue("$what missing on iOS: $iosToken", ios.contains(iosToken))
    }

    private fun read(relative: String): String = File(root, relative).readText()

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(12) {
            if (File(dir, "android-app").exists() && File(dir, "ios-app").exists()) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir")).absoluteFile
    }
}
