package com.flint.peakfocus.quickstart

import com.flint.peakfocus.glance.GlanceCauseKind
import com.flint.peakfocus.glance.GlanceDisplayMapper
import com.flint.peakfocus.glance.GlanceTier
import com.flint.peakfocus.glance.ProtectionReading
import com.flint.peakfocus.glance.ProtectionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class QuickTileMode { ACTIVE, INACTIVE }

/** Everything the Quick Settings tile shows and announces; refreshed at [refreshAtElapsedMs]. */
data class QuickTileDisplay(
    val mode: QuickTileMode,
    val label: String,
    val subtitle: String,
    val stateDescription: String,
    val contentDescription: String,
    val refreshAtElapsedMs: Long? = null,
)

/**
 * Pure tile projection of the coordinator's glance reading (the widget's and ongoing
 * notification's source). The tile is ACTIVE only for a fresh, same-boot check naming an
 * active cause with a supported end; everything else is INACTIVE with honest wording. It
 * never infers protection from permissions, saved rules or a previous reading.
 */
object QuickTileMapper {
    const val START_LABEL = "Start focus"
    private const val MAX_NAME = 24

    fun map(
        reading: ProtectionReading,
        nowElapsedMs: Long,
        nowEpochMs: Long,
        formatEnd: (epochMs: Long) -> String,
    ): QuickTileDisplay {
        fun idle(subtitle: String, state: String, refresh: Long? = null) = QuickTileDisplay(
            mode = QuickTileMode.INACTIVE,
            label = START_LABEL,
            subtitle = subtitle,
            stateDescription = state,
            contentDescription = "$START_LABEL. $state. Opens Flint.",
            refreshAtElapsedMs = refresh,
        )
        val unconfirmed = idle("Status unconfirmed", "Protection not confirmed")
        if (reading.status == ProtectionStatus.UNAVAILABLE || !GlanceDisplayMapper.isFresh(reading, nowElapsedMs)) {
            return unconfirmed
        }
        // isFresh guarantees both values; the tile must be re-read when the check ages out.
        val freshUntil = minOf(reading.checkedElapsedMs!! + GlanceDisplayMapper.MAX_AGE_MS, reading.validUntilElapsedMs!!)
        fun at(elapsedMs: Long) = formatEnd(nowEpochMs + (elapsedMs - nowElapsedMs))
        return when (reading.status) {
            ProtectionStatus.UNAVAILABLE -> unconfirmed
            ProtectionStatus.DEGRADED ->
                idle("Check protection", "Protection degraded at last check", freshUntil)
            ProtectionStatus.INACTIVE ->
                idle("Not active", "No active protection at last check", freshUntil)
            ProtectionStatus.PASS_RECOVERY -> {
                val end = reading.recoveryEndElapsedMs?.takeIf { it > nowElapsedMs } ?: return unconfirmed
                idle("Recovery until ${at(end)}", "Emergency Pass recovery until ${at(end)}", minOf(freshUntil, end))
            }
            ProtectionStatus.ACTIVE -> {
                val cause = reading.cause ?: return unconfirmed
                // Same evidence bar as the widget: an unidentified focus session, a passed
                // planned end, or an open-ended Harder/Hardcore cause is not shown as active.
                if (cause.kind in setOf(GlanceCauseKind.FOCUS_SESSION, GlanceCauseKind.UNKNOWN) &&
                    cause.occurrenceId.isNullOrBlank()) return unconfirmed
                val end = cause.logicalEndElapsedMs
                if (end != null && end <= nowElapsedMs) return idle("Planned end reached", "Planned end reached. Check in Flint")
                if (end == null && cause.tier != GlanceTier.EASY) return unconfirmed
                val name = cause.name.trim().let { if (it.length > MAX_NAME) it.take(MAX_NAME - 1).trimEnd() + "…" else it }
                    .ifBlank { "Focus" }
                val until = end?.let { "Until ${at(it)}" } ?: "Until ended in Flint"
                val state = "Protection active at last check"
                QuickTileDisplay(
                    mode = QuickTileMode.ACTIVE,
                    label = name,
                    subtitle = until,
                    stateDescription = state,
                    contentDescription = "$name, ${cause.tier.label}. $state, ${until.replaceFirstChar { it.lowercase() }}. Opens Flint.",
                    refreshAtElapsedMs = end?.let { minOf(freshUntil, it) } ?: freshUntil,
                )
            }
        }
    }

    /** Short end time: "14:30" today, "Tue 14:30" otherwise, honoring the 12/24-hour setting. */
    fun formatTime(epochMs: Long, nowEpochMs: Long, zone: ZoneId, locale: Locale, is24Hour: Boolean): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zone)
        val sameDay = at.toLocalDate() == Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val time = if (is24Hour) "HH:mm" else "h:mm a"
        return DateTimeFormatter.ofPattern(if (sameDay) time else "EEE $time", locale).format(at)
    }
}
