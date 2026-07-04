package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BreakLevel

/**
 * What the uninstall guard can see of the window in front: the package that owns it, the
 * window's class name (from `AccessibilityEvent.className`, when the event carried one) and
 * the text visible in it (node text + content descriptions, collected by the service).
 * Pure data; the AccessibilityService measures it, [UninstallGuard] judges it.
 */
data class ForegroundSurface(
    val packageName: String,
    val className: String? = null,
    val visibleText: List<String> = emptyList(),
)

/**
 * The **Android uninstall guard** — the pure half of the "deleting Flint is the one-tap escape"
 * anti-bypass (spec 2.5; the iOS counterpart is `FlintUninstallGuard` + `denyAppRemoval`).
 *
 * Threat model: while a HARDCORE (Deep Focus) block is active, the cheapest ways out that skip
 * Flint's own UI are (a) uninstalling Flint, (b) force-stopping it or clearing its data from
 * its Settings App-info page, and (c) flipping its accessibility service off. All three run
 * through a handful of well-known system surfaces, and Path A sees every window change — so
 * the guard names the surfaces that must be shielded while a Hardcore window is active:
 *
 *  1. **The system uninstaller** ([UNINSTALLER_PACKAGES]) — blocked outright while Hardcore is
 *     active, whatever app it is uninstalling. That deliberately mirrors iOS, where
 *     `denyAppRemoval` denies app removal **device-wide** during a Hardcore session; it is
 *     also the fail-closed choice, because the uninstall confirmation names its target app
 *     only in localized dialog text we cannot robustly parse.
 *  2. **Settings pages about Flint** ([SETTINGS_PACKAGES]) — blocked only when the visible
 *     text mentions Flint (by label or package name) AND the window looks dangerous: its
 *     class name hints at an App-info or Accessibility screen ([PROTECTED_CLASS_HINTS]), or a
 *     danger phrase ([DANGER_PHRASES]) is visible. Plain Settings use (Wi-Fi, the all-apps
 *     list where "Flint" is merely a row) stays reachable during a block.
 *
 * When to arm: any enabled [BreakLevel.HARDCORE] rule whose schedule is active now — manual
 * "Block Now" hardcore rules (null schedule) and scheduled windows alike, sharing
 * [BlockDecisionEngine.scheduleActive] so the guard's clock can never drift from enforcement's.
 * This is deliberately *broader* than iOS, which arms for Hardcore sessions only: Apple's
 * denial is a heavyweight device-wide OS setting, while this guard just shields a few system
 * screens locally, so extending it to scheduled Hardcore windows costs nothing and closes the
 * same hole there.
 *
 * Honest limits (documented, not hidden):
 *  - **Path A only.** The UsageStats fallback path cannot read window content or class names,
 *    so a user who first disables the accessibility service keeps Settings free of shields;
 *    the health checker surfaces that degradation, it is not silently hidden.
 *  - [DANGER_PHRASES] are English. They are the *fallback* trigger — the class-name hints and
 *    the uninstaller package check are locale-independent and carry the common cases.
 *  - Package/class names vary by OEM; the lists are best-effort, not exhaustive.
 *  - Like the iOS guard, this protects against impulse, not determination: a user can still
 *    disable the Hardcore rule inside Flint's own UI (deliberate, on-screen, and where
 *    friction belongs), reboot to safe mode, or use adb.
 *
 * No Android imports, no clock, no I/O — same contract as the other policies here.
 */
class UninstallGuard {

    /** Shared schedule clock — same active-window semantics as rule enforcement. */
    private val schedules = BlockDecisionEngine()

    /** True for surfaces the guard may want to judge — gate the (cheap) node-text collection. */
    fun isCandidateSurface(packageName: String): Boolean =
        packageName in UNINSTALLER_PACKAGES || packageName in SETTINGS_PACKAGES

    /** True when judging [packageName] needs visible window text (Settings mention-gating). */
    fun needsWindowText(packageName: String): Boolean = packageName in SETTINGS_PACKAGES

    /**
     * Judge [surface] against the currently active rules. Returns the enabled, in-window
     * HARDCORE rule that justifies shielding this surface — callers hand it to
     * `ruleBlockCause` so the block screen shows the real session (tier, countdown) — or
     * null when the surface is fine (no Hardcore window active, or the surface is not a
     * protected one).
     *
     * [nowMinutesOfDay]/[weekday] use the same contract as [BlockDecisionEngine.decide]:
     * ISO weekday (1=Mon…7=Sun), negative = "no time supplied" → schedules treated active.
     */
    fun judge(
        surface: ForegroundSurface,
        selfPackage: String,
        selfLabel: String,
        activeRules: List<BlockRule>,
        nowMinutesOfDay: Int = -1,
        weekday: Int = -1,
    ): BlockRule? {
        if (surface.packageName == selfPackage) return null
        val hardcore = activeHardcoreRule(activeRules, nowMinutesOfDay, weekday) ?: return null
        return when {
            surface.packageName in UNINSTALLER_PACKAGES -> hardcore
            surface.packageName in SETTINGS_PACKAGES &&
                isProtectedSettingsSurface(surface, selfPackage, selfLabel) -> hardcore
            else -> null
        }
    }

    /** First enabled HARDCORE rule whose schedule window is active now, else null. */
    internal fun activeHardcoreRule(
        activeRules: List<BlockRule>,
        nowMinutesOfDay: Int,
        weekday: Int,
    ): BlockRule? = activeRules.firstOrNull {
        it.enabled &&
            it.breakLevel == BreakLevel.HARDCORE &&
            schedules.scheduleActive(it.schedule, nowMinutesOfDay, weekday)
    }

    /**
     * A Settings window is protected only when it is *about Flint* (label or package name
     * visible) and *dangerous* (App-info / Accessibility class hint, or a visible danger
     * phrase). Both gates keep ordinary Settings usable during a block.
     */
    internal fun isProtectedSettingsSurface(
        surface: ForegroundSurface,
        selfPackage: String,
        selfLabel: String,
    ): Boolean {
        val mentionsSelf = surface.visibleText.any {
            it.contains(selfLabel, ignoreCase = true) || it.contains(selfPackage, ignoreCase = true)
        }
        if (!mentionsSelf) return false
        val className = surface.className?.lowercase() ?: ""
        if (PROTECTED_CLASS_HINTS.any { className.contains(it) }) return true
        return surface.visibleText.any { text ->
            DANGER_PHRASES.any { text.contains(it, ignoreCase = true) }
        }
    }

    internal companion object {
        /** System (un)installer UIs — the uninstall confirmation dialog lives here. */
        val UNINSTALLER_PACKAGES: Set<String> = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
        )

        /** Settings apps hosting App-info (uninstall/force-stop) and Accessibility toggles. */
        val SETTINGS_PACKAGES: Set<String> = setOf(
            "com.android.settings",
        )

        /**
         * Lowercased fragments of AOSP/OEM activity class names for the two dangerous screen
         * families: App-info ("InstalledAppDetails", "AppInfoDashboardActivity", …) and
         * Accessibility settings ("AccessibilitySettingsActivity", …).
         */
        val PROTECTED_CLASS_HINTS: List<String> = listOf(
            "installedappdetails",
            "appinfo",
            "appdetails",
            "accessibility",
        )

        /** English fallback triggers for OEM screens whose class name reveals nothing. */
        val DANGER_PHRASES: List<String> = listOf(
            "uninstall",
            "force stop",
            "turn off",
            "disable",
            "clear data",
            "clear storage",
        )
    }
}
