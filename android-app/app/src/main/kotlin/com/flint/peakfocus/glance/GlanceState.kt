package com.flint.peakfocus.glance

import java.time.Instant
import java.time.LocalDate
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
enum class GlanceCauseKind { FOCUS_SESSION, SCHEDULE, SLEEP, TIME_LIMIT, OPEN_LIMIT, UNKNOWN }
/** PATH_A requires positively live A AND positively absent Path B FGS, not permission bits. */
enum class GlanceDetector { UNKNOWN, PATH_A_CONFIRMED, PATH_B_FOREGROUND }

/** Deadlines are same-boot elapsedRealtime, supplied by the authority, never inferred from rules. */
data class ProtectionReading(
    val status: ProtectionStatus = ProtectionStatus.UNAVAILABLE,
    val checkedElapsedMs: Long? = null,
    val validUntilElapsedMs: Long? = null,
    val cause: ActiveGlanceCause? = null,
    val recoveryEndElapsedMs: Long? = null,
    val next: NextGlanceSession? = null,
    val sameBootVerified: Boolean = false,
    val timeUncertain: Boolean = false,
    val detector: GlanceDetector = GlanceDetector.UNKNOWN,
    val checkedEpochMs: Long? = null,
)

data class ActiveGlanceCause(
    val occurrenceId: String?,
    val name: String,
    val tier: GlanceTier,
    val logicalEndElapsedMs: Long?,
    val kind: GlanceCauseKind = GlanceCauseKind.UNKNOWN,
)

data class NextGlanceSession(val name: String, val startsAtEpochMs: Long)

/** F owns formatting and aggregation. These lines come from its public read API, never log files. */
data class FocusTodayReading(
    val text: String = "Focus history unavailable",
    val note: String? = null,
    val day: LocalDate? = null,
    val inProgress: Boolean = false,
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
    val focusedTodayLabel: String = "Focused today",
    val detector: GlanceDetector = GlanceDetector.UNKNOWN,
    val session: GlanceOngoingSession? = null,
)

data class GlanceOngoingSession(val occurrenceId: String, val logicalEndElapsedMs: Long?)

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
        fun atElapsed(elapsedMs: Long) = at(nowEpochMs + (elapsedMs - nowElapsedMs))
        val checked = reading.checkedElapsedMs
        val validUntil = reading.validUntilElapsedMs
        val fresh = reading.sameBootVerified && !reading.timeUncertain &&
            checked != null && checked >= 0 && checked <= nowElapsedMs &&
            nowElapsedMs - checked < MAX_AGE_MS && validUntil != null &&
            validUntil > nowElapsedMs && validUntil >= checked
        val stamp = if (reading.sameBootVerified && !reading.timeUncertain && checked != null && checked in 0..nowElapsedMs)
            "Checked ${reading.checkedEpochMs?.let(::at) ?: atElapsed(checked)} · Open Flint for current state"
            else "Open Flint to check protection"
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val historyCurrent = focus.day == today
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val historyBoundary = if (historyCurrent) nowElapsedMs + (midnight - nowEpochMs) else null
        val base = GlanceDisplay(
            status = "Protection state unavailable",
            detail = "Open Flint to check",
            next = "Next session unavailable",
            focusedToday = if (historyCurrent) focus.text else "Focus history unavailable",
            historyNote = if (focus.day != null && !historyCurrent) "Open Flint to refresh" else focus.note,
            checked = stamp,
            refreshAtElapsedMs = historyBoundary,
            focusedTodayLabel = "Focused today · ${today.format(DateTimeFormatter.ofPattern("MMM d", locale))}",
        )
        if (reading.status == ProtectionStatus.UNAVAILABLE) return base
        if (!fresh) return base.copy(status = "Protection check needed", detail = "Last check is out of date")
        val next = reading.next?.takeIf { it.startsAtEpochMs > nowEpochMs }?.let {
            "Next: ${it.name.take(64)} · ${at(it.startsAtEpochMs)}"
        } ?: if (reading.next == null) "No upcoming session" else "Next session needs a new check"
        val nextBoundary = reading.next?.takeIf { it.startsAtEpochMs > nowEpochMs }?.let {
            nowElapsedMs + (it.startsAtEpochMs - nowEpochMs)
        }
        val refresh = listOfNotNull(checked!! + MAX_AGE_MS, validUntil, historyBoundary, nextBoundary).min()
        val available = base.copy(next = next, refreshAtElapsedMs = refresh, detector = reading.detector)
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
                    status = "Emergency Pass recovery", detail = "Recovery until ${atElapsed(end)}",
                    refreshAtElapsedMs = minOf(refresh, end),
                )
            }
            ProtectionStatus.ACTIVE -> {
                val cause = reading.cause ?: return base
                val id = cause.occurrenceId
                if (cause.kind in setOf(GlanceCauseKind.FOCUS_SESSION, GlanceCauseKind.UNKNOWN) && id.isNullOrBlank())
                    return base
                val end = cause.logicalEndElapsedMs
                if (end != null && end <= nowElapsedMs) return available.copy(
                    status = "Planned end reached", detail = "Open Flint to check protection",
                )
                if (end == null && cause.tier != GlanceTier.EASY) return base
                available.copy(
                    status = "Protection active at last check",
                    detail = "${cause.name.take(64)} · ${cause.tier.label}" +
                        (end?.let { " · Until ${atElapsed(it)}" } ?: " · Until ended in Flint"),
                    countdownEndElapsedMs = end.takeIf { cause.kind == GlanceCauseKind.FOCUS_SESSION },
                    refreshAtElapsedMs = end?.let { minOf(refresh, it) } ?: refresh,
                    session = if (cause.kind == GlanceCauseKind.FOCUS_SESSION && id != null)
                        GlanceOngoingSession(id, end) else null,
                    // RemoteViews may outlive this process. Progress always describes an update,
                    // and is withdrawn on the stale/expired/unsupported branches above.
                    historyNote = listOfNotNull(
                        "In progress at update".takeIf {
                            historyCurrent && focus.inProgress && cause.kind == GlanceCauseKind.FOCUS_SESSION
                        },
                        available.historyNote,
                    ).takeIf { it.isNotEmpty() }?.joinToString(" · "),
                )
            }
        }
    }
}
