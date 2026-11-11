package com.flint.peakfocus.feature.settings.applock

/**
 * PIN shape rules, matching iOS `FlintPIN.isValidNewPIN`.
 *
 * New PINs are exactly 4-6 ASCII digits. Input is never trimmed, truncated or filtered: silently
 * saving a different PIN from the one the user typed would lock them out of their own app.
 */
object AppPinPolicy {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /**
     * Longest string accepted for a verification attempt (gate or current-PIN challenge). A longer
     * entry is treated as wrong without hashing it, but it still counts as a failed attempt.
     */
    const val MAX_ENTRY_LENGTH = 32

    fun isValidNewPin(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
}
