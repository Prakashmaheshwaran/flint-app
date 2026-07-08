package com.flint.peakfocus.blocking.engine

/**
 * Core system surfaces we never treat as a blockable/countable foreground app: the shield
 * allowlist in [BlockDecisionEngine] and the "doesn't count as an open" heuristic in
 * [OpenLimitPolicy] share this so the two decisions can't drift apart.
 *
 * The telephony rows are a **safety floor**, not a convenience: an allow-list rule (brick-phone
 * mode, Sleep Mode's Full Assist) blocks everything not explicitly allowed, and shielding the
 * dialer would block emergency calling. Best-effort OEM coverage — the detector layer
 * additionally exempts the device's resolved default dialer and launcher at runtime.
 */
internal val SYSTEM_SURFACE_PACKAGES: Set<String> = setOf(
    "com.android.systemui",
    "com.android.settings",
    "android",
    // Telephony / emergency (never blockable, never counted):
    "com.android.dialer",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.samsung.android.app.telephonyui",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.emergency",
)
