package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dated [Schedule] (`date != null`) is a one-time window: exactly one occurrence, any distance
 * ahead, in the saved zone, with never a weekly repeat. These tests pin the engine primitives
 * that the editor timeline, the arming boundary and the detectors all share.
 */
class ScheduleOccurrencesTest {

    private val utc = ZoneId.of("UTC")
    private val ny = ZoneId.of("America/New_York")

    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    private fun dated(date: String, start: Int = 9 * 60, end: Int = 17 * 60) =
        Schedule(startMinuteOfDay = start, endMinuteOfDay = end, date = LocalDate.parse(date))

    @Test
    fun `dated schedule yields exactly one occurrence on its own date`() {
        val schedule = dated("2026-11-20")
        val onDate = ScheduleOccurrences.onDate(schedule, utc, LocalDate.parse("2026-11-20"))
        assertNotNull(onDate)
        assertEquals(at("2026-11-20T09:00:00Z"), onDate!!.startsAtEpochMs)
        assertEquals(at("2026-11-20T17:00:00Z"), onDate.endsAtEpochMs)
        assertNull(ScheduleOccurrences.onDate(schedule, utc, LocalDate.parse("2026-11-19")))
        assertNull(ScheduleOccurrences.onDate(schedule, utc, LocalDate.parse("2026-11-21")))
    }

    @Test
    fun `dated schedule never repeats on the same weekday`() {
        // 2026-11-20 is a Friday; the following Friday must stay empty.
        val schedule = dated("2026-11-20")
        assertEquals(5, LocalDate.parse("2026-11-20").dayOfWeek.value)
        assertNull(ScheduleOccurrences.onDate(schedule, utc, LocalDate.parse("2026-11-27")))
        assertNull(ScheduleOccurrences.onDate(schedule, utc, LocalDate.parse("2026-12-04")))
        // A weekday gate on a dated window is ignored, never interpreted as a repeat.
        val damaged = schedule.copy(daysOfWeek = setOf(5))
        assertNull(ScheduleOccurrences.onDate(damaged, utc, LocalDate.parse("2026-11-27")))
        assertNotNull(ScheduleOccurrences.onDate(damaged, utc, LocalDate.parse("2026-11-20")))
    }

    @Test
    fun `dated start 30 days ahead is the next start - no eight day horizon`() {
        val schedule = dated("2026-12-20")
        val now = at("2026-11-20T12:00:00Z")
        assertEquals(at("2026-12-20T09:00:00Z"), ScheduleOccurrences.nextStart(schedule, utc, now))
        assertNull(ScheduleOccurrences.containing(schedule, utc, now))
    }

    @Test
    fun `dated next start disappears once its own start has passed`() {
        val schedule = dated("2026-11-20")
        val start = at("2026-11-20T09:00:00Z")
        assertEquals(start, ScheduleOccurrences.nextStart(schedule, utc, start - 1))
        assertNull(ScheduleOccurrences.nextStart(schedule, utc, start))
        assertNull(ScheduleOccurrences.nextStart(schedule, utc, start + 1))
        // A week later there is still no next start (PC-81: no replay, no repeat).
        assertNull(ScheduleOccurrences.nextStart(schedule, utc, start + 7 * 86_400_000L))
    }

    @Test
    fun `dated overnight window runs into the next day and only then stops`() {
        val overnight = dated("2026-11-20", start = 22 * 60, end = 6 * 60)
        val head = at("2026-11-20T22:30:00Z")
        val tail = at("2026-11-21T02:00:00Z")
        assertEquals(8 * 3_600_000L,
            ScheduleOccurrences.onDate(overnight, utc, LocalDate.parse("2026-11-20"))!!.let {
                it.endsAtEpochMs - it.startsAtEpochMs
            })
        assertNotNull(ScheduleOccurrences.containing(overnight, utc, head))
        // The post-midnight tail belongs to the dated start date, not to the 21st.
        assertNotNull(ScheduleOccurrences.containing(overnight, utc, tail))
        assertNull(ScheduleOccurrences.onDate(overnight, utc, LocalDate.parse("2026-11-21")))
        assertNull(ScheduleOccurrences.containing(overnight, utc, at("2026-11-21T06:00:00Z")))
        assertNull(ScheduleOccurrences.containing(overnight, utc, at("2026-11-19T23:00:00Z")))
    }

    @Test
    fun `dated window shifts a missing spring forward endpoint past the gap`() {
        // 2026-03-08 America/New_York: 02:00 -> 03:00. A 01:30-02:30 window therefore ends at
        // 03:30 EDT: the same §9 policy recurring windows use, never a shorter silent window.
        val schedule = dated("2026-03-08", start = 90, end = 150)
        val occurrence = ScheduleOccurrences.onDate(schedule, ny, LocalDate.parse("2026-03-08"))!!
        assertEquals(at("2026-03-08T06:30:00Z"), occurrence.startsAtEpochMs)
        assertEquals(at("2026-03-08T07:30:00Z"), occurrence.endsAtEpochMs)
        assertEquals(3_600_000L, occurrence.endsAtEpochMs - occurrence.startsAtEpochMs)
    }

    @Test
    fun `dated window takes the earlier ambiguous start and later ambiguous end`() {
        // 2026-11-01 America/New_York: 01:00-02:00 happens twice. Earlier start, later end.
        val schedule = dated("2026-11-01", start = 90, end = 105)
        val occurrence = ScheduleOccurrences.onDate(schedule, ny, LocalDate.parse("2026-11-01"))!!
        assertEquals(at("2026-11-01T05:30:00Z"), occurrence.startsAtEpochMs)
        assertEquals(at("2026-11-01T06:45:00Z"), occurrence.endsAtEpochMs)
        assertNotNull(ScheduleOccurrences.containing(schedule, ny, at("2026-11-01T06:20:00Z")))
    }

    @Test
    fun `dated window compares endpoints in its own zone not the caller's`() {
        // 09:00 in New York on 2026-11-20 is 14:00Z, so a UTC read at 09:05Z is still pending.
        val schedule = dated("2026-11-20")
        val occurrence = ScheduleOccurrences.onDate(schedule, ny, LocalDate.parse("2026-11-20"))!!
        assertEquals(at("2026-11-20T14:00:00Z"), occurrence.startsAtEpochMs)
        assertNull(ScheduleOccurrences.containing(schedule, ny, at("2026-11-20T09:05:00Z")))
        assertNotNull(ScheduleOccurrences.containing(schedule, ny, at("2026-11-20T14:05:00Z")))
    }

    @Test
    fun `invalid dated windows yield no occurrence`() {
        assertNull(ScheduleOccurrences.onDate(dated("2026-11-20", start = 600, end = 600), utc, LocalDate.parse("2026-11-20")))
        assertNull(ScheduleOccurrences.onDate(dated("2026-11-20", start = -1, end = 600), utc, LocalDate.parse("2026-11-20")))
        assertNull(ScheduleOccurrences.onDate(dated("2026-11-20", start = 600, end = 1440), utc, LocalDate.parse("2026-11-20")))
    }

    @Test
    fun `recurring schedules keep their weekday gate and eight day scan`() {
        val weekly = Schedule(setOf(5), 9 * 60, 17 * 60)
        assertNotNull(ScheduleOccurrences.onDate(weekly, utc, LocalDate.parse("2026-11-20")))
        assertNull(ScheduleOccurrences.onDate(weekly, utc, LocalDate.parse("2026-11-21")))
        val now = at("2026-11-20T12:00:00Z")
        assertEquals(at("2026-11-27T09:00:00Z"), ScheduleOccurrences.nextStart(weekly, utc, now))
        assertTrue(ScheduleOccurrences.nextStart(weekly, utc, at("2026-12-20T12:00:00Z"))!! >
            at("2026-12-20T12:00:00Z"))
    }
}
