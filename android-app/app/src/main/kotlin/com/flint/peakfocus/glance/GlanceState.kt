package com.flint.peakfocus.glance

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Read-only boundary. Only C's coordinator may supply protection evidence; no saved toggles. */
interface GlanceProtectionReader {
    val changes: Flow<Unit>
    suspend fun read(): ProtectionReading
}

object UnavailableProtectionReader : GlanceProtectionReader {
    override val changes: Flow<Unit> = flowOf(Unit)
    override suspend fun read() = ProtectionReading()
}

enum class ProtectionStatus { UNAVAILABLE, DEGRADED, INACTIVE, ACTIVE, PASS_RECOVERY }
enum class GlanceTier(val label: String) { EASY("Easy"), HARDER("Harder"), HARDCORE("Hardcore") }

/** Deadlines are same-boot elapsedRealtime, supplied by the authority, never inferred from rules. */
data class ProtectionReading(
    val status: ProtectionStatus = ProtectionStatus.UNAVAILABLE,
    val checkedElapsedMs: Long? = null,
    val validUntilElapsedMs: Long? = null,
    val cause: ActiveGlanceCause? = null,
    val recoveryEndElapsedMs: Long? = null,
    val next: NextGlanceSession? = null,
)

data class ActiveGlanceCause(
    val occurrenceId: String,
    val name: String,
    val tier: GlanceTier,
    val logicalEndElapsedMs: Long?,
)

data class NextGlanceSession(val name: String, val startsAtEpochMs: Long)

/** F owns formatting and aggregation. These lines come from its public read API, never log files. */
data class FocusTodayReading(
    val text: String = "Focus history unavailable",
    val note: String? = null,
)

data class GlanceDisplay(
    val status: String,
    val detail: String,
    val next: String,
    val focusedToday: String,
    val historyNote: String?,
    val checked: String,
    val countdownEndElapsedMs: Long? = null,
    val refreshAtElapsedMs: Long? = null,
)

/** Pure display projection: no mutation, release, lifecycle event or completion inference. */
object GlanceDisplayMapper {
    const val MAX_AGE_MS = 60_000L

    fun map(
        reading: ProtectionReading,
        focus: FocusTodayReading,
        nowElapsedMs: Long,
        nowEpochMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): GlanceDisplay {
        fun at(epochMs: Long): String = DateTimeFormatter.ofPattern("EEE HH:mm", locale)
            .withZone(zone).format(Instant.ofEpochMilli(epochMs))
        fun end(elapsedMs: Long) = at(nowEpochMs + (elapsedMs - nowElapsedMs))
        val checked = reading.checkedElapsedMs
        val validUntil = reading.validUntilElapsedMs
        val fresh = checked != null && checked >= 0 && checked <= nowElapsedMs &&
            nowElapsedMs - checked < MAX_AGE_MS && validUntil != null &&
            validUntil > nowElapsedMs && validUntil >= checked
        val stamp = if (checked != null && checked in 0..nowElapsedMs)
            "Checked ${end(checked)} · Open Flint for current state" else "Open Flint to check protection"
        val base = GlanceDisplay(
            status = "Protection state unavailable",
            detail = "Open Flint to check",
            next = "Next session unavailable",
            focusedToday = focus.text,
            historyNote = focus.note,
            checked = stamp,
        )
        if (reading.status == ProtectionStatus.UNAVAILABLE) return base
        if (!fresh) return base.copy(status = "Protection check needed", detail = "Last check is out of date")
        val next = reading.next?.takeIf { it.startsAtEpochMs > nowEpochMs }?.let {
            "Next: ${it.name.take(64)} · ${at(it.startsAtEpochMs)}"
        } ?: if (reading.next == null) "No upcoming session" else "Next session needs a new check"
        val refresh = minOf(checked!! + MAX_AGE_MS, validUntil!!)
        val available = base.copy(next = next, refreshAtElapsedMs = refresh)
        return when (reading.status) {
            ProtectionStatus.UNAVAILABLE -> base
            ProtectionStatus.DEGRADED -> available.copy(
                status = "Protection degraded", detail = "Open Flint to review coverage",
            )
            ProtectionStatus.INACTIVE -> available.copy(
                status = "No active protection at last check", detail = "Tap to open Flint",
            )
            ProtectionStatus.PASS_RECOVERY -> {
                val end = reading.recoveryEndElapsedMs ?: return base
                if (end <= nowElapsedMs) available.copy(
                    status = "Recovery time ended", detail = "Open Flint to check protection",
                ) else available.copy(
                    status = "Emergency Pass recovery", detail = "Recovery until ${end(end)}",
                    refreshAtElapsedMs = minOf(refresh, end),
                )
            }
            ProtectionStatus.ACTIVE -> {
                val cause = reading.cause?.takeIf { it.occurrenceId.isNotBlank() } ?: return base
                val end = cause.logicalEndElapsedMs
                if (end != null && end <= nowElapsedMs) return available.copy(
                    status = "Planned end reached", detail = "Open Flint to check protection",
                )
                if (end == null && cause.tier != GlanceTier.EASY) return base
                available.copy(
                    status = "Protection active at last check",
                    detail = "${cause.name.take(64)} · ${cause.tier.label}" +
                        (end?.let { " · Until ${end(it)}" } ?: " · Until ended in Flint"),
                    countdownEndElapsedMs = end,
                    refreshAtElapsedMs = end?.let { minOf(refresh, it) } ?: refresh,
                )
            }
        }
    }
}
