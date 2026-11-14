package com.flint.peakfocus.core.history

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comparable streak lifecycle fixtures shared conceptually with iOS
 * `FlintHistoryStreakLifecycleTests`. Scenario IDs S01–S20 match across platforms.
 */
class FocusStreakLifecycleTest {
    private val zone = ZoneId.of("America/New_York")
    private val hour = 3_600_000L
    /** Local noon on 2026-10-28 America/New_York. */
    private val todayNoon = LocalDateTime.parse("2026-10-28T12:00:00").atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 28)
    private val historyStart = today.minusDays(30)

    private fun at(dayOffset: Long, hourOfDay: Int = 9): Long =
        LocalDateTime.of(2026, 10, 28, hourOfDay, 0).plusDays(dayOffset).atZone(zone).toInstant().toEpochMilli()

    private fun started(dayOffset: Long, id: String = "s$dayOffset", hourOfDay: Int = 9) = HistoryEvent(
        type = HistoryEventType.STARTED.wireName, session = id, at = at(dayOffset, hourOfDay), tz = zone.id,
        plannedEnd = at(dayOffset, hourOfDay) + hour, tier = "easy", source = "manual", name = "Focus",
    )

    private fun completed(dayOffset: Long, id: String = "s$dayOffset", hourOfDay: Int = 10) = HistoryEvent(
        type = HistoryEventType.COMPLETED.wireName, session = id, at = at(dayOffset, hourOfDay), tz = zone.id,
    )

    private fun endedEarly(dayOffset: Long, id: String = "s$dayOffset") = HistoryEvent(
        type = HistoryEventType.ENDED_EARLY.wireName, session = id, at = at(dayOffset, 9) + hour / 2, tz = zone.id,
        route = "manual_stop",
    )

    private fun passOnly(dayOffset: Long = 0) = HistoryEvent(
        type = HistoryEventType.PASS_USED.wireName, session = "pass-$dayOffset", at = at(dayOffset, 11), tz = zone.id,
        sessions = listOf("other"), recoveryEnd = at(dayOffset, 12),
    )

    private fun summarize(
        events: List<HistoryEvent>,
        now: Long = todayNoon,
        historyStartMs: Long = historyStart.atStartOfDay(zone).toInstant().toEpochMilli(),
        inZone: ZoneId = zone,
        active: Set<String> = emptySet(),
    ): FocusSummary = FocusHistoryAggregator.summarize(
        HistorySnapshot(events, historyStartMs, true), now, inZone, active,
    )

    private fun completedDays(vararg offsets: Long) = offsets.flatMap { offset ->
        listOf(started(offset), completed(offset))
    }

    @Test fun S01_fiveConsecutiveCompletedDays() {
        val result = summarize(completedDays(-4, -3, -2, -1, 0))
        assertEquals(5, result.streak)
        assertEquals(5, result.bestRecordedStreak)
    }

    @Test fun S02_todayIncompleteYesterdayCompleted() {
        val result = summarize(completedDays(-2, -1))
        assertEquals(2, result.streak)
        assertEquals(2, result.bestRecordedStreak)
    }

    @Test fun S03_oneFullMissedDayBreaksCurrent() {
        // Completed through -2, miss -1 and today → streak 0; best remains 3 (-4..-2)
        val result = summarize(completedDays(-4, -3, -2))
        assertEquals(0, result.streak)
        assertEquals(3, result.bestRecordedStreak)
        assertEquals(3, result.sessions.count { it.outcome == SessionOutcome.COMPLETED })
    }

    @Test fun S04_returnAfterOneMissedDayStartsOne() {
        // Had -4..-2, miss -1, complete today → current 1; best 3
        val result = summarize(completedDays(-4, -3, -2, 0))
        assertEquals(1, result.streak)
        assertEquals(3, result.bestRecordedStreak)
    }

    @Test fun S05_threeConsecutiveMissedDays() {
        val result = summarize(completedDays(-5, -4))
        assertEquals(0, result.streak)
        assertEquals(2, result.bestRecordedStreak)
    }

    @Test fun S06_multipleCompletionsSameDayCountOnce() {
        val events = listOf(
            started(0, "a", 8), completed(0, "a", 9),
            started(0, "b", 11), completed(0, "b", 12),
            started(-1, "c"), completed(-1, "c"),
        )
        val result = summarize(events)
        assertEquals(2, result.streak)
        assertEquals(2, result.bestRecordedStreak)
        assertEquals(2, result.days.last().completed)
    }

    @Test fun S07_startedButIncompleteDoesNotCredit() {
        // Planned end must still be in the future or the active ID alone cannot stay RUNNING.
        val startMs = at(0, 11)
        val planned = at(0, 14)
        val events = listOf(
            HistoryEvent(
                type = HistoryEventType.STARTED.wireName, session = "s0", at = startMs, tz = zone.id,
                plannedEnd = planned, tier = "easy", source = "manual", name = "Focus",
            ),
        )
        val result = summarize(events, now = todayNoon, active = setOf("s0"))
        assertEquals(SessionOutcome.RUNNING, result.sessions.single().outcome)
        assertEquals(0, result.streak)
        assertEquals(0, result.bestRecordedStreak)
    }

    @Test fun S08_unconfirmedDoesNotCredit() {
        val result = summarize(listOf(started(-1)), now = todayNoon)
        assertEquals(SessionOutcome.UNCONFIRMED, result.sessions.single().outcome)
        assertEquals(0, result.streak)
        assertEquals(0, result.bestRecordedStreak)
    }

    @Test fun S09_interruptedEndedEarlyDoesNotCreditStreak() {
        val result = summarize(listOf(started(0), endedEarly(0)))
        assertEquals(SessionOutcome.ENDED_EARLY, result.sessions.single().outcome)
        assertEquals(0, result.streak)
        assertEquals(0, result.bestRecordedStreak)
    }

    @Test fun S10_emergencyPassWithoutCompletedFocus() {
        val result = summarize(listOf(passOnly(0)))
        assertTrue(result.sessions.isEmpty())
        assertEquals(0, result.streak)
        assertEquals(0, result.bestRecordedStreak)
    }

    @Test fun S11_historyStartCutsStreak() {
        val startMs = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val result = summarize(completedDays(-3, -2, -1, 0), historyStartMs = startMs)
        assertEquals(2, result.streak)
        assertEquals(2, result.bestRecordedStreak)
    }

    @Test fun S12_emptyHistory() {
        val result = summarize(emptyList())
        assertEquals(HistoryAvailability.EMPTY, result.availability)
        assertEquals(0, result.streak)
        assertEquals(0, result.bestRecordedStreak)
    }

    @Test fun S13_unavailableHistory() {
        val result = FocusHistoryAggregator.summarize(HistorySnapshot(), todayNoon, zone, emptySet())
        assertEquals(HistoryAvailability.UNAVAILABLE, result.availability)
        assertEquals(0, result.streak)
        assertEquals(0, result.bestRecordedStreak)
    }

    @Test fun S14_localMidnightBoundaryUsesEndDay() {
        // Session ends just after midnight → counts on today, not yesterday.
        val end = today.atStartOfDay(zone).toInstant().toEpochMilli() + 60_000
        val startMs = end - hour
        val events = listOf(
            HistoryEvent(
                type = HistoryEventType.STARTED.wireName, session = "midnight", at = startMs, tz = zone.id,
                plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
            ),
            HistoryEvent(type = HistoryEventType.COMPLETED.wireName, session = "midnight", at = end, tz = zone.id),
        )
        val result = summarize(events)
        assertEquals(1, result.streak)
        assertEquals(1, result.days.last().completed)
    }

    @Test fun S15_dstSpringForwardConsecutiveDays() {
        // America/New_York spring forward 2026-03-08. Completions on 03-07, 03-08, 03-09.
        val spring = ZoneId.of("America/New_York")
        fun springAt(date: String, hour: Int): Long =
            LocalDateTime.parse("${date}T${"%02d".format(hour)}:00:00").atZone(spring).toInstant().toEpochMilli()
        val events = listOf("2026-03-07", "2026-03-08", "2026-03-09").flatMapIndexed { index, date ->
            val id = "dst$index"
            listOf(
                HistoryEvent(
                    type = HistoryEventType.STARTED.wireName, session = id, at = springAt(date, 9), tz = spring.id,
                    plannedEnd = springAt(date, 10), tier = "easy", source = "manual", name = "Focus",
                ),
                HistoryEvent(type = HistoryEventType.COMPLETED.wireName, session = id, at = springAt(date, 10), tz = spring.id),
            )
        }
        val now = springAt("2026-03-09", 12)
        val history = LocalDate.parse("2026-03-01").atStartOfDay(spring).toInstant().toEpochMilli()
        val result = FocusHistoryAggregator.summarize(HistorySnapshot(events, history, true), now, spring, emptySet())
        assertEquals(3, result.streak)
        assertEquals(3, result.bestRecordedStreak)
    }

    @Test fun S16_dstFallBackConsecutiveDays() {
        val fall = ZoneId.of("America/New_York")
        fun fallAt(date: String, hour: Int): Long =
            LocalDateTime.parse("${date}T${"%02d".format(hour)}:00:00").atZone(fall).toInstant().toEpochMilli()
        val events = listOf("2026-10-31", "2026-11-01", "2026-11-02").flatMapIndexed { index, date ->
            val id = "fall$index"
            listOf(
                HistoryEvent(
                    type = HistoryEventType.STARTED.wireName, session = id, at = fallAt(date, 9), tz = fall.id,
                    plannedEnd = fallAt(date, 10), tier = "easy", source = "manual", name = "Focus",
                ),
                HistoryEvent(type = HistoryEventType.COMPLETED.wireName, session = id, at = fallAt(date, 10), tz = fall.id),
            )
        }
        val now = fallAt("2026-11-02", 12)
        val history = LocalDate.parse("2026-10-20").atStartOfDay(fall).toInstant().toEpochMilli()
        val result = FocusHistoryAggregator.summarize(HistorySnapshot(events, history, true), now, fall, emptySet())
        assertEquals(3, result.streak)
        assertEquals(3, result.bestRecordedStreak)
    }

    /**
     * S17 — timezone rebucketing contract.
     *
     * Storage: event `at` / session end are epoch milliseconds (absolute instants). The
     * stored `tz` field is provenance only; aggregation never uses it for day buckets.
     * Local days are `Instant.atZone(currentZone).toLocalDate()` of the completion end.
     * Changing the device zone therefore rebuckets historical completions and can raise
     * or lower both current streak and best recorded streak without mutating history.
     */
    @Test fun S17_timezoneChangeRebuckets() {
        val nyZone = ZoneId.of("America/New_York")
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val history = LocalDate.of(2026, 10, 1).atStartOfDay(nyZone).toInstant().toEpochMilli()

        fun session(id: String, localNy: String): List<HistoryEvent> {
            val start = LocalDateTime.parse(localNy).atZone(nyZone).toInstant().toEpochMilli()
            val end = start + hour
            return listOf(
                HistoryEvent(
                    type = HistoryEventType.STARTED.wireName, session = id, at = start, tz = nyZone.id,
                    plannedEnd = end, tier = "easy", source = "manual", name = "Focus",
                ),
                HistoryEvent(type = HistoryEventType.COMPLETED.wireName, session = id, at = end, tz = nyZone.id),
            )
        }

        // Midday completion stays on the same calendar date in both zones.
        // NY 2026-10-28 10:00 EDT = Tokyo 2026-10-28 23:00; now NY noon = Tokyo 01:00 next day.
        // NY: today completed → streak 1. Tokyo: yesterday completed, today empty → streak 1.
        val midday = session("mid", "2026-10-28T10:00:00")
        val middayNow = LocalDateTime.parse("2026-10-28T12:00:00").atZone(nyZone).toInstant().toEpochMilli()
        val middayNy = summarize(midday, now = middayNow, historyStartMs = history, inZone = nyZone)
        val middayTokyo = summarize(midday, now = middayNow, historyStartMs = history, inZone = tokyoZone)
        assertEquals(1, middayNy.streak)
        assertEquals(1, middayNy.bestRecordedStreak)
        assertEquals(1, middayTokyo.streak)
        assertEquals(1, middayTokyo.bestRecordedStreak)

        // Collapse: two consecutive NY days land on one Tokyo day → streak/best decrease.
        // NY 10-27 22:00 EDT → Tokyo 10-28 11:00; NY 10-28 01:00 EDT → Tokyo 10-28 14:00.
        val collapse = session("c1", "2026-10-27T22:00:00") + session("c2", "2026-10-28T01:00:00")
        val collapseNow = LocalDateTime.parse("2026-10-28T12:00:00").atZone(nyZone).toInstant().toEpochMilli()
        val collapseNy = summarize(collapse, now = collapseNow, historyStartMs = history, inZone = nyZone)
        val collapseTokyo = summarize(collapse, now = collapseNow, historyStartMs = history, inZone = tokyoZone)
        assertEquals(2, collapseNy.streak)
        assertEquals(2, collapseNy.bestRecordedStreak)
        // Tokyo "today" is 10-29; only 10-28 completed → yesterday anchor keeps streak 1.
        assertEquals(1, collapseTokyo.streak)
        assertEquals(1, collapseTokyo.bestRecordedStreak)

        // Split: two same-NY-day completions land on consecutive Tokyo days → streak/best increase.
        // NY 10-28 01:00 → Tokyo 10-28 14:00; NY 10-28 20:00 → Tokyo 10-29 09:00.
        val split = session("s1", "2026-10-28T01:00:00") + session("s2", "2026-10-28T20:00:00")
        val splitNow = LocalDateTime.parse("2026-10-28T21:30:00").atZone(nyZone).toInstant().toEpochMilli()
        val splitNy = summarize(split, now = splitNow, historyStartMs = history, inZone = nyZone)
        val splitTokyo = summarize(split, now = splitNow, historyStartMs = history, inZone = tokyoZone)
        assertEquals(1, splitNy.streak)
        assertEquals(1, splitNy.bestRecordedStreak)
        assertEquals(2, splitTokyo.streak)
        assertEquals(2, splitTokyo.bestRecordedStreak)
    }

    @Test fun S18_appRestartAfterMissedDayPreservesHistory() {
        val events = completedDays(-4, -3, -2)
        val first = summarize(events)
        val relaunch = summarize(events) // identical reload of durable events
        assertEquals(0, first.streak)
        assertEquals(3, first.bestRecordedStreak)
        assertEquals(first.streak, relaunch.streak)
        assertEquals(first.bestRecordedStreak, relaunch.bestRecordedStreak)
        assertEquals(3, relaunch.sessions.count { it.outcome == SessionOutcome.COMPLETED })
    }

    @Test fun S19_forceStopRelaunchSameAsRestart() {
        // Force-stop cannot erase local history; re-summarizing identical events is the contract.
        S18_appRestartAfterMissedDayPreservesHistory()
    }

    @Test fun S20_uncertainHistoryDoesNotInventStreak() {
        val started = started(0).copy(timeConfidence = HistoryEventTimeConfidence.Uncertain("clock_discontinuity"))
        val result = summarize(listOf(started, completed(0)))
        // Uncertain start typically yields unconfirmed / zero focus credit — never invent a streak.
        assertTrue(result.streak == 0 || result.sessions.none { it.outcome == SessionOutcome.COMPLETED })
        assertTrue(result.bestRecordedStreak >= result.streak)
    }

    @Test fun bestNeverLessThanCurrent() {
        val result = summarize(completedDays(-2, -1, 0))
        assertTrue(result.bestRecordedStreak >= result.streak)
    }

    @Test fun pureHelpersMatchRequiredRules() {
        val days = setOf(today.minusDays(2), today.minusDays(1), today)
        assertEquals(3, FocusHistoryAggregator.currentStreak(days, today, historyStart))
        assertEquals(2, FocusHistoryAggregator.currentStreak(days - today, today, historyStart))
        assertEquals(0, FocusHistoryAggregator.currentStreak(setOf(today.minusDays(3)), today, historyStart))
        assertEquals(3, FocusHistoryAggregator.bestRecordedStreak(days, historyStart))
        assertEquals(
            3,
            FocusHistoryAggregator.bestRecordedStreak(
                days + today.minusDays(10) + today.minusDays(9) + today.minusDays(8),
                historyStart,
            ),
        )
    }
}
