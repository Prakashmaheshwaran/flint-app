package com.flint.peakfocus.core.model

import com.flint.peakfocus.core.model.ForegroundEvent.Kind.*
import org.junit.Assert.*
import org.junit.Test

class UsageTimelineTest {
    private fun event(time: Long, kind: ForegroundEvent.Kind = RESUMED, pkg: String = "app", activity: String = "Main") =
        ForegroundEvent(time, kind, pkg, activity)

    @Test fun `stationary foreground survives ticks with no events for minutes`() {
        val tracker = ForegroundTracker()
        val cursor = ForegroundEventCursor()
        var now = 1_000L
        cursor.commit(now, listOf(event(now))).forEach(tracker::accept)
        repeat(180) {
            now += 1_000
            cursor.commit(now, emptyList()).forEach(tracker::accept)
            assertEquals("app", tracker.packageName)
        }
    }

    @Test fun `resume moves foreground and old activity pause does not erase it`() {
        val tracker = ForegroundTracker()
        listOf(event(1), event(2, pkg = "next"), event(3, PAUSED)).forEach(tracker::accept)
        assertEquals("next", tracker.packageName)
        tracker.accept(event(4, STOPPED, "next"))
        assertNull(tracker.packageName)
    }

    @Test fun `same package activity handoff ignores the older activity stop`() {
        val tracker = ForegroundTracker()
        listOf(event(1), event(2, activity = "Detail"), event(3, STOPPED)).forEach(tracker::accept)
        assertEquals("app", tracker.packageName)
    }

    @Test fun `screen off shutdown and startup clear foreground`() {
        for (kind in listOf(SCREEN_OFF, SHUTDOWN, STARTUP)) {
            val tracker = ForegroundTracker()
            tracker.accept(event(1))
            tracker.accept(event(2, kind))
            assertNull(tracker.packageName)
        }
    }

    @Test fun `late old events do not rewind newer foreground`() {
        val tracker = ForegroundTracker()
        tracker.accept(event(2, pkg = "new"))
        tracker.accept(event(1))
        assertEquals("new", tracker.packageName)
    }

    @Test fun `cursor begins with long lookback then bounded overlap`() {
        val cursor = ForegroundEventCursor(initialLookbackMs = 100_000, overlapMs = 2_000)
        assertEquals(-90_000L, cursor.queryStart(10_000))
        cursor.commit(10_000, emptyList())
        assertEquals(8_000L, cursor.queryStart(11_000))
    }

    @Test fun `overlap deduplicates but accepts new delayed events in order`() {
        val cursor = ForegroundEventCursor()
        val resume = event(9_000)
        assertEquals(listOf(resume), cursor.commit(10_000, listOf(resume)))
        val pause = event(9_500, PAUSED)
        val next = event(10_500, pkg = "next")
        assertEquals(listOf(pause, next), cursor.commit(11_000, listOf(next, resume, pause, pause)))
        assertTrue(cursor.commit(11_500, listOf(resume, pause, next)).isEmpty())
    }

    @Test fun `rewind and explicit reset rebuild cursor and tracker`() {
        val cursor = ForegroundEventCursor(initialLookbackMs = 100_000)
        cursor.commit(10_000, listOf(event(9_000)))
        assertTrue(cursor.needsReset(5_000))
        assertEquals(-95_000L, cursor.queryStart(5_000))
        assertEquals(listOf(event(4_000)), cursor.commit(5_000, listOf(event(4_000))))
        val tracker = ForegroundTracker()
        tracker.accept(event(10_000))
        tracker.reset()
        tracker.accept(event(4_000, pkg = "rewound"))
        assertEquals("rewound", tracker.packageName)
        cursor.reset()
        assertEquals(-95_000L, cursor.queryStart(5_000))
    }

    @Test fun `ongoing session grows with a fake clock without any pause`() {
        val events = listOf(event(1_000))
        assertEquals(59_999L, ForegroundUsage.millis("app", events, 0, 60_999))
        assertEquals(60_000L, ForegroundUsage.millis("app", events, 0, 61_000))
    }

    @Test fun `carry in clips at midnight and closed intervals stop growing`() {
        val events = listOf(event(500), event(2_000, PAUSED), event(3_000), event(4_000, STOPPED))
        assertEquals(2_000L, ForegroundUsage.millis("app", events, 1_000, 10_000))
    }

    @Test fun `multiple resumed activities contribute their union`() {
        val events = listOf(event(0), event(1_000, activity = "Detail"), event(2_000, PAUSED), event(3_000, STOPPED, activity = "Detail"))
        assertEquals(3_000L, ForegroundUsage.millis("app", events, 0, 4_000))
    }

    @Test fun `duplicate resumes and pauses do not double count`() {
        val events = listOf(event(0), event(0), event(500), event(2_000, PAUSED), event(3_000, STOPPED))
        assertEquals(2_000L, ForegroundUsage.millis("app", events, 0, 4_000))
    }

    @Test fun `screen off closes a session and unrelated packages do not count`() {
        val events = listOf(event(0, pkg = "other"), event(500), event(2_000, SCREEN_OFF), event(3_000, pkg = "other"))
        assertEquals(1_500L, ForegroundUsage.millis("app", events, 0, 4_000))
    }

    @Test fun `shutdown gap never accrues usage even with stray resumes`() {
        val events = listOf(event(0), event(1_000, SHUTDOWN), event(2_000), event(3_000, STARTUP), event(4_000))
        assertEquals(2_000L, ForegroundUsage.millis("app", events, 0, 5_000))
    }

    @Test fun `startup without shutdown drops the unknown open interval`() {
        val events = listOf(event(0), event(3_000, STARTUP), event(4_000))
        assertEquals(1_000L, ForegroundUsage.millis("app", events, 0, 5_000))
    }

    @Test fun `missing history and future events do not invent usage`() {
        assertEquals(0L, ForegroundUsage.millis("app", listOf(event(1_000, PAUSED), event(6_000)), 0, 5_000))
        assertEquals(0L, ForegroundUsage.millis("app", emptyList(), 0, 5_000))
        assertEquals(0L, ForegroundUsage.millis("app", listOf(event(0)), 5_000, 4_000))
    }
}
