package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.core.common.Oem

/**
 * Static, on-device auto-start / power-manager guidance per OEM (Android strategy doc §6.1;
 * content follows the dontkillmyapp.com data set). Text instructions only, deliberately:
 * deep links into OEM-internal settings activities break across OS versions, and a broken
 * deep link is worse than honest steps. Menu names drift too, so steps hedge with the usual
 * paths rather than claiming exactness.
 *
 * Selection happens via core-common's [Oem]/OemUtil, which normalizes Build.MANUFACTURER
 * (Redmi/POCO → XIAOMI, Honor → HUAWEI, ...). This catalog is pure Kotlin — JVM-tested in
 * OemGuidanceTest. Local guidance only: nothing here fetches, checks, or phones anywhere.
 */
data class OemGuidance(
    val oem: Oem,
    /** Brand name(s) as the user knows them, e.g. "Xiaomi / Redmi / POCO". */
    val displayName: String,
    /** One honest sentence on what this manufacturer's software does to background apps. */
    val summary: String,
    /** Do-this-then-that steps, rendered as a numbered list. */
    val steps: List<String>,
)

object OemGuidanceCatalog {

    fun forOem(oem: Oem): OemGuidance = when (oem) {
        Oem.XIAOMI -> OemGuidance(
            oem = oem,
            displayName = "Xiaomi / Redmi / POCO (MIUI or HyperOS)",
            summary = "MIUI and HyperOS are among the most aggressive background killers — " +
                "without Autostart, blocking will not survive the first cleanup.",
            steps = listOf(
                "Open the Security app (or Settings > Apps > Manage apps), find Flint, and " +
                    "turn on \"Autostart\".",
                "In Settings > Battery & performance, open the battery saver options for " +
                    "Flint and choose \"No restrictions\".",
                "In the recent-apps screen, long-press Flint's card and tap the lock icon so " +
                    "\"Clean up\" skips it.",
            ),
        )
        Oem.HUAWEI -> OemGuidance(
            oem = oem,
            displayName = "Huawei / Honor (EMUI or MagicOS)",
            summary = "\"App launch\" auto-manages apps by default and routinely stops " +
                "background services it didn't start.",
            steps = listOf(
                "In Settings > Battery > App launch, find Flint, switch it from \"Manage " +
                    "automatically\" to \"Manage manually\", and enable Auto-launch, " +
                    "Secondary launch, and Run in background.",
                "On older versions, open Phone Manager and add Flint to \"Protected apps\".",
                "Avoid swiping Flint away in the recent-apps screen — lock its card if your " +
                    "version offers the padlock.",
            ),
        )
        Oem.SAMSUNG -> OemGuidance(
            oem = oem,
            displayName = "Samsung (One UI)",
            summary = "One UI puts apps it considers unused \"to sleep\", which silently stops " +
                "background work.",
            steps = listOf(
                "In Settings > Apps > Flint > Battery, choose \"Unrestricted\".",
                "In Settings > Battery > Background usage limits, add Flint to \"Never " +
                    "sleeping apps\" and make sure it is not listed under \"Sleeping\" or " +
                    "\"Deep sleeping\" apps.",
                "If blocking still stops, turn off \"Put unused apps to sleep\" on the same " +
                    "screen.",
            ),
        )
        Oem.ONEPLUS -> OemGuidance(
            oem = oem,
            displayName = "OnePlus (OxygenOS)",
            summary = "OxygenOS layers its own \"advanced\"/deep optimization on top of " +
                "standard Android battery limits.",
            steps = listOf(
                "In Settings > Battery > Battery optimization, set Flint to \"Don't optimize\".",
                "In Settings > Apps > Flint, allow background activity and enable " +
                    "\"Allow auto-launch\" if your version shows it.",
                "In the recent-apps screen, tap the menu on Flint's card and choose \"Lock\".",
            ),
        )
        Oem.OPPO -> OemGuidance(
            oem = oem,
            displayName = "Oppo (ColorOS)",
            summary = "ColorOS blocks app auto-start by default and clears background apps " +
                "aggressively.",
            steps = listOf(
                "In Settings > Apps (App management) > Flint, enable \"Allow auto startup\".",
                "In Settings > Battery, open Flint's app battery usage and allow background " +
                    "activity (\"Don't optimize\").",
                "On older versions, also allow Flint in Phone Manager > Privacy permissions > " +
                    "Startup manager.",
            ),
        )
        Oem.VIVO -> OemGuidance(
            oem = oem,
            displayName = "vivo (Funtouch OS or OriginOS)",
            summary = "vivo's iManager and \"high background power consumption\" controls stop " +
                "background apps that aren't whitelisted.",
            steps = listOf(
                "In iManager > App manager > Autostart manager (or Settings > Apps > Flint), " +
                    "enable autostart for Flint.",
                "In Settings > Battery > Background power consumption management, allow Flint " +
                    "high background power consumption.",
                "Lock Flint's card in the recent-apps screen so one-tap cleanup skips it.",
            ),
        )
        Oem.REALME -> OemGuidance(
            oem = oem,
            displayName = "realme (realme UI)",
            summary = "realme UI is built on ColorOS and ships the same auto-start block and " +
                "background cleanup.",
            steps = listOf(
                "In Settings > Apps (App management) > Flint, enable \"Allow auto startup\".",
                "In Settings > Battery > App battery management, set Flint to \"Don't " +
                    "optimize\" / allow background activity.",
                "Lock Flint's card in the recent-apps screen so cleanup skips it.",
            ),
        )
        Oem.MOTOROLA -> OemGuidance(
            oem = oem,
            displayName = "Motorola (near-stock Android)",
            summary = "Motorola runs close to stock Android and usually respects the battery " +
                "exemption, but its per-app \"Background restriction\" can still stop blocking.",
            steps = listOf(
                "Grant the battery optimization exemption above.",
                "In Settings > Apps > Flint > App battery usage, make sure \"Background " +
                    "restriction\" is off and the setting is \"Unrestricted\".",
                "Avoid swiping Flint away in the recent-apps screen — some builds treat that " +
                    "as a stop.",
            ),
        )
        Oem.NOTHING -> OemGuidance(
            oem = oem,
            displayName = "Nothing (Nothing OS)",
            summary = "Nothing OS stays close to stock Android, so the battery exemption is " +
                "usually enough — just confirm background activity isn't restricted.",
            steps = listOf(
                "Grant the battery optimization exemption above.",
                "In Settings > Apps > Flint > App battery usage, choose \"Unrestricted\".",
                "Avoid swiping Flint away in the recent-apps screen so it isn't force-stopped.",
            ),
        )
        Oem.OTHER -> OemGuidance(
            oem = oem,
            displayName = "your phone",
            summary = "Stock and near-stock Android usually respects the battery exemption " +
                "above, but many brands ship their own battery manager on top.",
            steps = listOf(
                "Grant the battery optimization exemption above — on stock Android that is " +
                    "usually enough.",
                "Search your Settings for \"Autostart\", \"Auto-launch\", \"Protected apps\", " +
                    "or \"Battery manager\" and allow Flint in any of them that exist.",
                "Avoid swiping Flint away in the recent-apps screen — some phones treat that " +
                    "as a force stop.",
            ),
        )
    }
}
