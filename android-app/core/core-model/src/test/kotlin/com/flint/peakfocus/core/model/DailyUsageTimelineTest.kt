package com.flint.peakfocus.core.model

import org.junit.Assert.*
import org.junit.Test

class DailyUsageTimelineTest {
    private val resume = ForegroundEvent(100, ForegroundEvent.Kind.RESUMED, "app")

    @Test fun `long lookback happens once then queries stay incremental`() {
        val ranges = mutableListOf<Pair<Long, Long>>()
        val timeline = DailyUsageTimeline { start, end -> ranges += start to end; listOf(resume) }
        assertEquals(900L, timeline.millis("app", 0, 1_000))
        assertEquals(60_900L, timeline.millis("app", 0, 61_000))
        assertEquals(61_900L, timeline.millis("app", 0, 62_000))
        assertTrue(ranges[0].first < -86_400_000)
        assertEquals(-1_000L, ranges[1].first)
        assertEquals(59_000L, ranges[2].first)
    }

    @Test fun `new local day rebuilds carry in and clips the still open session`() {
        var queries = 0
        val timeline = DailyUsageTimeline { _, _ -> queries++; listOf(resume) }
        assertEquals(900L, timeline.millis("app", 0, 1_000))
        assertEquals(500L, timeline.millis("app", 1_000, 1_500))
        assertEquals(2, queries)
    }

    @Test fun `clock rewind rebuilds from current platform timestamps`() {
        val timeline = DailyUsageTimeline { _, end -> listOf(resume.copy(timestampMs = if (end < 1_000) 50 else 100)) }
        assertEquals(900L, timeline.millis("app", 0, 1_000))
        assertEquals(450L, timeline.millis("app", 0, 500))
    }

    @Test fun `failed query does not advance the cursor or lose accumulated usage`() {
        var fail = false
        val starts = mutableListOf<Long>()
        val timeline = DailyUsageTimeline { start, _ ->
            starts += start
            if (fail) error("binder unavailable") else listOf(resume)
        }
        timeline.millis("app", 0, 1_000)
        fail = true
        assertTrue(runCatching { timeline.millis("app", 0, 2_000) }.isFailure)
        fail = false
        assertEquals(2_900L, timeline.millis("app", 0, 3_000))
        assertEquals(starts[1], starts[2])
    }

    @Test fun `equal timestamp transitions preserve stream order and do not replay`() {
        val pause = resume.copy(kind = ForegroundEvent.Kind.PAUSED)
        val timeline = DailyUsageTimeline { _, _ -> listOf(resume, pause) }
        assertEquals(0L, timeline.millis("app", 0, 1_000))
        assertEquals(0L, timeline.millis("app", 0, 2_000))
        val cursor = ForegroundEventCursor()
        val tracker = ForegroundTracker()
        cursor.commit(1_000, listOf(resume, pause)).forEach(tracker::accept)
        cursor.commit(2_000, listOf(resume, pause)).forEach(tracker::accept)
        assertNull(tracker.packageName)
    }

    @Test fun `identical resumed events around a pause at the same millisecond remain distinct`() {
        val pause = resume.copy(kind = ForegroundEvent.Kind.PAUSED)
        val batch = listOf(resume, pause, resume)
        val timeline = DailyUsageTimeline { _, _ -> batch }
        assertEquals(900L, timeline.millis("app", 0, 1_000))
        assertEquals(1_900L, timeline.millis("app", 0, 2_000))
        val cursor = ForegroundEventCursor()
        val tracker = ForegroundTracker()
        cursor.commit(1_000, batch).forEach(tracker::accept)
        assertTrue(cursor.commit(2_000, batch).isEmpty())
        assertEquals("app", tracker.packageName)
    }
}
