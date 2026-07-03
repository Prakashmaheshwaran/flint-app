package com.flint.peakfocus.blocking.engine

/**
 * Core system surfaces we never treat as a blockable/countable foreground app: the shield
 * allowlist in [BlockDecisionEngine] and the "doesn't count as an open" heuristic in
 * [OpenLimitPolicy] share this so the two decisions can't drift apart.
 */
internal val SYSTEM_SURFACE_PACKAGES: Set<String> = setOf(
    "com.android.systemui",
    "com.android.settings",
    "android",
)
