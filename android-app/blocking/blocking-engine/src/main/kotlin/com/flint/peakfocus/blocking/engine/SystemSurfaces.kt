package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.SafetyFloor

/**
 * System surfaces the Open-Limit heuristic ([OpenLimitPolicy.countsAsOpen]) treats as dips, not
 * apps: entering one is not an open, and returning from one is not a re-open (the shade, Settings,
 * permission dialogs, the dialer). Every package here is also on the enforcement [SafetyFloor],
 * which [BlockDecisionEngine] applies; the floor is deliberately *wider*.
 *
 * Kept narrow on purpose: user-launchable floor apps with their own content (Personal Safety,
 * OEM security/battery managers, cell-broadcast history) must not become free "bounce" surfaces
 * that let a limited app be re-entered without counting an open. The detector layer's runtime
 * floor (default dialer/home app) is likewise not listed — launcher→app transitions are exactly
 * the opens that should count.
 */
internal val SYSTEM_SURFACE_PACKAGES: Set<String> = setOf(
    "com.android.systemui",
    "com.android.settings",
    "android",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    // Telephony / emergency (never blockable, never counted):
    "com.android.dialer",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.samsung.android.app.telephonyui",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.emergency",
).also { surfaces ->
    check(surfaces.all { it in SafetyFloor.STATIC_PACKAGES }) { "every dip surface must be on the safety floor" }
}
