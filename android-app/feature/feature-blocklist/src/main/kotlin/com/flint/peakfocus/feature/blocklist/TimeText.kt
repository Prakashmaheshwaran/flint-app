package com.flint.peakfocus.feature.blocklist

/**
 * Text ↔ minute-of-day conversion for the schedule editor's time fields.
 *
 * Hand-rolled on purpose: Material3's TimePicker is still an experimental API and `java.time`
 * needs API 26+/desugaring, while `Schedule` stores plain minutes-of-day — a five-line parser
 * covers it. 24h format only ("HH:MM"); a window that should run *until midnight* ends at
 * "00:00", which the engine treats as overnight-wrapping (start > end), so a 1440/"24:00"
 * sentinel is never needed.
 */

/**
 * Parses "9:30" / "09:30" into minutes since midnight (570), or null when the text is not a
 * valid 24h time. Hours 0–23, minutes 0–59, each 1–2 digits; no inner whitespace.
 */
internal fun parseMinuteOfDay(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val hourText = parts[0]
    val minuteText = parts[1]
    if (hourText.isEmpty() || hourText.length > 2 || minuteText.isEmpty() || minuteText.length > 2) return null
    if (!hourText.all { it.isDigit() } || !minuteText.all { it.isDigit() }) return null
    val hour = hourText.toIntOrNull() ?: return null
    val minute = minuteText.toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** Formats minutes since midnight as zero-padded 24h text: 570 → "09:30". Clamps to 00:00–23:59. */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(MINUTE_OF_DAY_RANGE.first, MINUTE_OF_DAY_RANGE.last)
    val hour = clamped / 60
    val minute = clamped % 60
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}
