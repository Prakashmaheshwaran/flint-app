package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the Android uninstall guard's decision half. The service side that
 * measures [ForegroundSurface]s (event package/class, node-text collection) is framework
 * code, not tested here — same split as ClockChangeGuard / TimeChangeReceiver.
 */
class UninstallGuardTest {

    private val guard = UninstallGuard()
    private val self = "com.flint.peakfocus"
    private val label = "Flint"

    private fun hardcoreRule(
        id: String = "hc",
        schedule: Schedule? = null,
        enabled: Boolean = true,
    ) = BlockRule(
        id = id,
        name = "Deep Focus",
        targets = BlockTargets(apps = setOf(AppRef("com.instagram.android"))),
        schedule = schedule,
        breakLevel = BreakLevel.HARDCORE,
        enabled = enabled,
    )

    private fun easyRule(id: String = "easy") = hardcoreRule(id).copy(breakLevel = BreakLevel.EASY)

    private fun uninstallDialog(text: List<String> = emptyList()) = ForegroundSurface(
        packageName = "com.google.android.packageinstaller",
        className = "com.android.packageinstaller.UninstallerActivity",
        visibleText = text,
    )

    private fun appInfoFor(appLabel: String) = ForegroundSurface(
        packageName = "com.android.settings",
        className = "com.android.settings.applications.InstalledAppDetailsTop",
        visibleText = listOf("App info", appLabel, "Uninstall", "Force stop"),
    )

    private fun judge(surface: ForegroundSurface, rules: List<BlockRule>, nowMin: Int = -1, weekday: Int = -1) =
        guard.judge(surface, self, label, rules, nowMin, weekday)

    // MARK: arming — when a Hardcore window is (not) active

    @Test
    fun noRulesAllowsEverySurface() {
        assertNull(judge(uninstallDialog(), emptyList()))
        assertNull(judge(appInfoFor(label), emptyList()))
    }

    @Test
    fun easyAndHarderRulesNeverArmTheGuard() {
        val rules = listOf(easyRule(), hardcoreRule("h2").copy(breakLevel = BreakLevel.HARDER))
        assertNull(judge(uninstallDialog(), rules))
        assertNull(judge(appInfoFor(label), rules))
    }

    @Test
    fun disabledHardcoreRuleDoesNotArm() {
        assertNull(judge(uninstallDialog(), listOf(hardcoreRule(enabled = false))))
    }

    @Test
    fun manualHardcoreRuleArms() {
        assertNotNull(judge(uninstallDialog(), listOf(hardcoreRule(schedule = null))))
    }

    @Test
    fun scheduledHardcoreArmsOnlyInsideItsWindow() {
        val nineToFive = Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)
        val rules = listOf(hardcoreRule(schedule = nineToFive))
        assertNotNull(judge(uninstallDialog(), rules, nowMin = 12 * 60, weekday = 3))
        assertNull(judge(uninstallDialog(), rules, nowMin = 18 * 60, weekday = 3))
    }

    @Test
    fun weekdayGateIsRespected() {
        val mondaysOnly = Schedule(daysOfWeek = setOf(1), startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)
        val rules = listOf(hardcoreRule(schedule = mondaysOnly))
        assertNotNull(judge(uninstallDialog(), rules, nowMin = 12 * 60, weekday = 1))
        assertNull(judge(uninstallDialog(), rules, nowMin = 12 * 60, weekday = 2))
    }

    @Test
    fun overnightTailArmsOnTheSpilloverDay() {
        // Mon 22:00–06:00: the post-midnight tail belongs to Monday's window, i.e. it is
        // active at *Tuesday* 00:30 and NOT at Monday 00:30 — same contract as the engine.
        val monNight = Schedule(daysOfWeek = setOf(1), startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60)
        val rules = listOf(hardcoreRule(schedule = monNight))
        assertNotNull(judge(uninstallDialog(), rules, nowMin = 30, weekday = 2))
        assertNull(judge(uninstallDialog(), rules, nowMin = 30, weekday = 1))
    }

    @Test
    fun returnsTheArmingHardcoreRuleForTheBlockCause() {
        val hardcore = hardcoreRule("the-hardcore-one")
        val judged = judge(uninstallDialog(), listOf(easyRule(), hardcore))
        assertEquals(hardcore, judged)
    }

    // MARK: the system uninstaller — blocked outright while armed (device-wide, like iOS)

    @Test
    fun uninstallerIsBlockedEvenWithoutReadableText() {
        assertNotNull(judge(uninstallDialog(text = emptyList()), listOf(hardcoreRule())))
    }

    @Test
    fun everyKnownUninstallerPackageIsCovered() {
        for (pkg in UninstallGuard.UNINSTALLER_PACKAGES) {
            val surface = ForegroundSurface(packageName = pkg)
            assertNotNull("expected $pkg to be blocked", judge(surface, listOf(hardcoreRule())))
        }
    }

    // MARK: Settings — mention-gated, danger-gated

    @Test
    fun flintAppInfoPageIsBlocked() {
        assertNotNull(judge(appInfoFor(label), listOf(hardcoreRule())))
    }

    @Test
    fun otherAppsAppInfoPageStaysReachable() {
        assertNull(judge(appInfoFor("Instagram"), listOf(hardcoreRule())))
    }

    @Test
    fun accessibilitySettingsMentioningFlintAreBlocked() {
        val surface = ForegroundSurface(
            packageName = "com.android.settings",
            className = "com.android.settings.Settings\$AccessibilitySettingsActivity",
            visibleText = listOf("Accessibility", "Downloaded apps", label),
        )
        assertNotNull(judge(surface, listOf(hardcoreRule())))
    }

    @Test
    fun allAppsListShowingFlintRowStaysReachable() {
        // The all-apps list mentions Flint but is neither a protected class nor shows a
        // danger phrase — blocking it would make Settings unusable during every block.
        val surface = ForegroundSurface(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.ManageApplications",
            visibleText = listOf("All apps", "Chrome", label, "Instagram"),
        )
        assertNull(judge(surface, listOf(hardcoreRule())))
    }

    @Test
    fun dangerPhraseBlocksOemScreensWithOpaqueClassNames() {
        val surface = ForegroundSurface(
            packageName = "com.android.settings",
            className = "com.oem.settings.SomeOpaqueScreen",
            visibleText = listOf(label, "Uninstall"),
        )
        assertNotNull(judge(surface, listOf(hardcoreRule())))
    }

    @Test
    fun packageNameMentionCountsAsSelf() {
        val surface = ForegroundSurface(
            packageName = "com.android.settings",
            className = null,
            visibleText = listOf(self, "Force stop"),
        )
        assertNotNull(judge(surface, listOf(hardcoreRule())))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val surface = ForegroundSurface(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.APPINFODashboardActivity",
            visibleText = listOf("FLINT — Peak Focus"),
        )
        assertNotNull(judge(surface, listOf(hardcoreRule())))
    }

    @Test
    fun settingsWithoutAnyFlintMentionStayReachable() {
        val surface = ForegroundSurface(
            packageName = "com.android.settings",
            className = "com.android.settings.applications.InstalledAppDetailsTop",
            visibleText = listOf("App info", "Instagram", "Uninstall", "Force stop"),
        )
        assertNull(judge(surface, listOf(hardcoreRule())))
    }

    // MARK: scope

    @Test
    fun flintItselfIsNeverJudged() {
        val surface = ForegroundSurface(packageName = self, visibleText = listOf(label, "Uninstall"))
        assertNull(judge(surface, listOf(hardcoreRule())))
    }

    @Test
    fun ordinaryAppsAreNotCandidates() {
        assertFalse(guard.isCandidateSurface("com.instagram.android"))
        assertNull(judge(ForegroundSurface("com.instagram.android"), listOf(hardcoreRule())))
    }

    @Test
    fun candidateAndTextNeedsAgreeWithThePackageLists() {
        assertTrue(guard.isCandidateSurface("com.android.settings"))
        assertTrue(guard.isCandidateSurface("com.android.packageinstaller"))
        assertTrue(guard.needsWindowText("com.android.settings"))
        assertFalse(guard.needsWindowText("com.android.packageinstaller"))
    }
}
