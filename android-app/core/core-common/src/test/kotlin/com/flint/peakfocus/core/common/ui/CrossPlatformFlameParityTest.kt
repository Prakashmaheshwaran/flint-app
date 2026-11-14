package com.flint.peakfocus.core.common.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shared flame specification across Android and iOS.
 *
 * Both renderers are Canvas implementations of the same design, but they cannot share code,
 * so this test reads the two source files and fails when a constant that affects the visible
 * flame drifts on either platform. It also locks the single-renderer rule and the six
 * placement sizes so a future refactor cannot silently reintroduce a second flame.
 */
class CrossPlatformFlameParityTest {

    private val root: File = repoRoot()
    private val android: String = read(
        "android-app/core/core-common/src/main/kotlin/com/flint/peakfocus/core/common/ui/StreakFlame.kt",
    )
    private val androidWrapper: String = read(
        "android-app/core/core-common/src/main/kotlin/com/flint/peakfocus/core/common/ui/FlintStreakEmber.kt",
    )
    private val ios: String = read("ios-app/Flint/Features/Stats/FlintStreakEmber.swift")

    @Test
    fun `renderers share geometry and silhouette scale`() {
        assertBoth(
            androidToken = "val w: Float = s * 0.76f",
            iosToken = "let w = s * 0.76",
            what = "contour-relative width (approved rendered H:W 1.30-1.35)",
        )
        assertBoth("val h: Float = s * 0.90f", "let h = s * 0.90", "flame height")
        assertBoth("val baseY: Float = s * 0.95f", "let baseY = s * 0.95", "fuel-base anchor")

        // The old 0.693 width rendered a ~1.45 silhouette; it must not return on either side.
        assertFalse("Android still uses the old narrow 0.693 scale", android.contains("s * 0.693f"))
        assertFalse("iOS still uses the old narrow 0.693 scale", ios.contains("s * 0.693"))
    }

    @Test
    fun `renderers share active and at-risk body palettes`() {
        assertBoth("0xFFFB922F", "0xFB922F", "active body base amber")
        assertBoth("0xFFFB7A22", "0xFB7A22", "active body orange")
        assertBoth("0xFFF34415", "0xF34415", "active body red-orange")
        assertBoth("0xFFC7300B", "0xC7300B", "active body deepest red")

        assertBoth("0xFFF6A23C", "0xF6A23C", "at-risk saturated base amber")
        assertBoth("0xFFEA8A2B", "0xEA8A2B", "at-risk lower orange")
        assertBoth("0xFFCF6E1E", "0xCF6E1E", "at-risk mid orange")
        assertBoth("0xFFA6551C", "0xA6551C", "at-risk upper cooled orange")
        assertBoth("0xFF7E4017", "0x7E4017", "at-risk upper dark orange")
        assertBoth("0xFF5C3014", "0x5C3014", "at-risk cooled tip")
    }

    @Test
    fun `renderers share core and hot-base palettes`() {
        assertBoth("0xFFFFF6C8", "0xFFF6C8", "active core hot centre")
        assertBoth("0xFFFFE08A", "0xFFE08A", "active core warm mid")
        assertBoth("0xFFFDCC3B", "0xFDCC3B", "active core yellow")

        assertBoth("0xE6FFD27E", "0xFFD27E", "at-risk residual core")
        assertBoth("0x99FFB35A", "0xFFB35A", "at-risk core edge")

        assertBoth("0xFFFFFFFF", "baseHotHex: UInt32 = mood == .active ? 0xFFFFFF : 0xFFD9A0", "active white-hot base")
        assertBoth("0xA6FFFDE6", "0xFFFDE6", "active white-hot base falloff")
        assertBoth("0xE6FFD9A0", "0xFFD9A0", "at-risk amber base")
    }

    @Test
    fun `renderers share dormant ash palette and ember scale`() {
        assertBoth("0xFF655E56", "0x655E56", "ash crown grey")
        assertBoth("0xFF3A3530", "0x3A3530", "ash mid charcoal")
        assertBoth("0xFF262220", "0x262220", "ash lower charcoal")
        assertBoth("0xFF151312", "0x151312", "ash base near-black")

        assertBoth("0xFF2C2521", "0x2C2521", "charcoal shard left")
        assertBoth("0xFF352D26", "0x352D26", "charcoal shard right")
        assertBoth("0xFF17120F", "0x17120F", "coal charred rim")
        assertBoth("0xFFD45E1D", "0xD45E1D", "coal warm body")
        assertBoth("0xFFFFB066", "0xFFB066", "coal hot centre")

        assertBoth(
            "if (compact) 0.11f else 0.085f",
            "(compact ? 0.11 : 0.085)",
            "compact ember radius relative to fuel width",
        )
    }

    @Test
    fun `renderers share size tiers and detail suppression`() {
        assertBoth("sizeDp < 48f -> 0.6f to 1.0f", "if sizeDP < 48 { return (0.6, 1.0) }", "compact fringe")
        assertBoth("sizeDp < 120f -> 1.0f to 1.8f", "if sizeDP < 120 { return (1.0, 1.8) }", "mid fringe")
        assertBoth("1.4f to 2.8f", "return (1.4, 2.8)", "hero fringe")

        assertBoth("dpSize < 48f", "s < 48", "compact detail threshold")
        assertBoth("dpSize >= 128f -> 3", "s >= 128 ? 3", "three embers at hero sizes")
        assertBoth("dpSize >= 64f -> 2", "s >= 64 ? 2", "two embers at mid sizes")
        assertBoth("dpSize >= 36f -> 1", "s >= 36 ? 1", "one ember at badge sizes")
    }

    @Test
    fun `renderers share timing motion and reduce-motion policy`() {
        assertBoth(
            "STATIC_FLAME_SECONDS: Double = 0.72",
            "STATIC_FLAME_SECONDS: Double = 0.72",
            "deterministic static pose",
        )
        assertBoth(
            "STATE_CROSSFADE_MS: Int = 320",
            "STATE_CROSSFADE_SECONDS: Double = 0.32",
            "one-shot state dissolve",
        )
        assertBoth("StreakFlameState.AtRisk -> 0.6f", "mood == .warning ? 0.6 : 1.0", "at-risk motion rate")
        assertBoth(
            "if (state == StreakFlameState.Active) 1f else 0.85f",
            "mood == .active ? 1 : 0.85",
            "at-risk motion amplitude",
        )

        assertBoth("(breath - 1f) * 0.45f", "(breath - 1) * 0.45", "breath height scale")
        assertBoth("(breath - 1f) * 0.55f", "(breath - 1) * 0.55", "breath width scale")
        assertBoth("0.96f + 0.08f * breath01", "0.04 * sin(seconds * 2.0 * Double.pi / 1.5)", "breathing envelope")
    }

    @Test
    fun `cooling chrome stays warm on both platforms`() {
        assertBoth("0xFFF2C892", "Color(red: 0.949, green: 0.784, blue: 0.573)", "cooling number top")
        assertBoth("0xFFC1731F", "Color(red: 0.757, green: 0.451, blue: 0.122)", "cooling number deep")
    }

    @Test
    fun `there is exactly one renderer per platform`() {
        val mainMarker = "${File.separator}src${File.separator}main${File.separator}"
        val androidSources = File(root, "android-app").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains(mainMarker) }
            .map { it.readText() }
            .toList()
        assertEquals(
            "Android must have exactly one StreakFlame composable across main sources",
            1,
            androidSources.sumOf { Regex("""fun StreakFlame\(""").findAll(it).count() },
        )
        for (function in listOf("buildOuter", "buildCore", "buildAsh", "buildShard")) {
            assertEquals(
                "$function must be defined exactly once across Android main sources",
                1,
                androidSources.sumOf { Regex("""fun $function\(""").findAll(it).count() },
            )
        }

        val iosSources = File(root, "ios-app/Flint").walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .map { it.readText() }
            .toList()
        assertEquals(
            "iOS must have exactly one FlintStreakEmber view across the app",
            1,
            iosSources.sumOf { Regex("""struct FlintStreakEmber: View""").findAll(it).count() },
        )
        for (function in listOf("drawEmber", "drawAsh")) {
            assertEquals(
                "$function must be defined exactly once across iOS sources",
                1,
                iosSources.sumOf { Regex("""func $function\(""").findAll(it).count() },
            )
        }

        // The mood wrapper must delegate to the one renderer, never draw its own flame.
        assertTrue("FlintStreakEmber must delegate to StreakFlame", androidWrapper.contains("StreakFlame("))
        assertFalse("legacy glass renderer must stay removed", androidWrapper.contains("buildGlassDrop"))
        assertFalse("legacy fire core must stay removed", androidWrapper.contains("buildFireCore"))
        assertEquals(
            "FlintStreakEmber must exist exactly once",
            1,
            Regex("""fun FlintStreakEmber\(""").findAll(androidWrapper).count(),
        )
    }

    @Test
    fun `all six placements use the approved sizes`() {
        val androidToday = read(
            "android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/TimerScreen.kt",
        )
        val androidInsights = read(
            "android-app/feature/feature-stats/src/main/kotlin/com/flint/peakfocus/feature/stats/FocusSessionsSection.kt",
        )
        val androidSheet = read(
            "android-app/core/core-common/src/main/kotlin/com/flint/peakfocus/core/common/ui/FocusStreakCelebrationSheet.kt",
        )
        val iosToday = read("ios-app/Flint/Features/Today/TodayView.swift")
        val iosInsights = read("ios-app/Flint/Features/Stats/FocusHistorySection.swift")
        val iosSheet = read("ios-app/Flint/Features/Stats/FocusStreakCelebrationSheet.swift")

        assertTrue(
            "Android Today badge must be 40 dp",
            Regex("""size\s*=\s*40\.dp\b""").containsMatchIn(androidToday),
        )
        assertTrue(
            "Android Insights hero must be 160 dp",
            Regex("""size\s*=\s*160\.dp\b""").containsMatchIn(androidInsights),
        )
        assertTrue(
            "Android sheet must be 148 dp",
            Regex("""size\s*=\s*148\.dp\b""").containsMatchIn(androidSheet),
        )
        assertTrue(
            "iOS Today badge must be 40 pt",
            Regex("""size:\s*40\b""").containsMatchIn(iosToday),
        )
        assertTrue(
            "iOS Insights hero must be 160 pt",
            Regex("""size:\s*160\b""").containsMatchIn(iosInsights),
        )
        assertTrue(
            "iOS sheet must be 148 pt",
            Regex("""size:\s*148\b""").containsMatchIn(iosSheet),
        )

        // The sheet must reuse the shared renderer, not its own path drawing.
        assertTrue("Android sheet must call StreakFlame", androidSheet.contains("StreakFlame("))
        assertFalse("Android sheet must not define a second flame", androidSheet.contains("buildOuter("))
    }

    @Test
    fun `both platforms resume the flame from the paused phase`() {
        // Android keys its single loop on eligibility and resumes from the frozen elapsed
        // value instead of restarting or snapping to the static pose.
        assertTrue(
            "Android must compute loop time from the paused anchor",
            android.contains("frameElapsed(anchor, start, now)"),
        )
        assertTrue(
            "Android must capture the paused elapsed as the resume anchor",
            android.contains("val anchor = elapsed"),
        )

        // iOS mirrors that anchor with an explicit phase base plus a monotonic clock.
        assertTrue("iOS must keep a resumable phase anchor", ios.contains("phaseAnchor"))
        assertTrue("iOS must freeze the paused phase", ios.contains("pausedPhase"))
        assertTrue(
            "iOS must anchor the phase on a monotonic clock",
            ios.contains("anchorUptime") && ios.contains("systemUptime"),
        )
        assertTrue(
            "iOS live phase must start from the anchored base",
            ios.contains("phaseAnchor +"),
        )
        assertTrue(
            "iOS must re-anchor from the paused phase, not the static pose",
            ios.contains("phaseAnchor = reduceMotion ? STATIC_FLAME_SECONDS : pausedPhase"),
        )
        assertTrue(
            "iOS must freeze the paused phase under reduce motion",
            ios.contains("pausedPhase = reduceMotion"),
        )
        assertTrue(
            "iOS must capture the frozen phase from the live anchor",
            ios.contains(": phaseAnchor + (uptime - anchorUptime)"),
        )
        // The old absolute wall-clock form snapped to the time of day on every resume.
        assertFalse(
            "iOS must not derive the live phase from absolute wall-clock time",
            ios.contains("let seconds = live ? now"),
        )
        assertFalse(
            "iOS must not read the wall clock for the phase delta",
            ios.contains("timeIntervalSinceReferenceDate"),
        )
    }

    @Test
    fun `both platforms pin the static pose under reduce motion`() {
        assertTrue(
            "Android must reset elapsed to the static pose under reduce motion",
            android.contains("if (reduced) elapsed = STATIC_FLAME_SECONDS"),
        )
        assertTrue(
            "iOS must draw the static pose under reduce motion",
            ios.contains("reduceMotion ? STATIC_FLAME_SECONDS : pausedPhase"),
        )
    }

    private fun assertBoth(androidToken: String, iosToken: String, what: String) {
        assertTrue("Android flame is missing $what token: $androidToken", android.contains(androidToken))
        assertTrue("iOS flame is missing $what token: $iosToken", ios.contains(iosToken))
    }

    private fun read(relative: String): String {
        val file = File(root, relative)
        assertTrue("Missing source file: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "android-app").isDirectory && File(dir, "ios-app").isDirectory) return dir
            dir = dir.parentFile
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }
}
