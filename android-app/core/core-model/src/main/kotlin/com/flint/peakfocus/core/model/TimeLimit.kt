package com.flint.peakfocus.core.model

/**
 * A daily **Time Limit**: a per-app foreground-time budget. When today's usage of [packageName]
 * reaches [dailyMinutes], the app blocks until the next day. Free in Flint (Opal paywalls the
 * hard-reset tier).
 *
 * The typed model the limits UI (A-BLOCKLIST-UI) and the engine share; mirrors the existing
 * SharedPreferences-backed `LimitStore` (core-data) `package → minutes` map. Provided by the
 * scaffold because feature modules can't add to core-model.
 */
data class TimeLimit(
    val packageName: String,
    val dailyMinutes: Int,
    /** Owned by this limit. Legacy tierless rows snapshot the old default during migration. */
    val breakLevel: BreakLevel = BreakLevel.EASY,
    /** Saved IANA zone. Null is a legacy/uncommitted draft; the migration/commit boundary stamps it once. */
    val zoneId: String? = null,
)
