package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CachedRuleClockProjectionTest {
    private val anchor = AccessClockStamp(10_000, 1_000, "boot")
    private fun rules(duration: Long = 1_000) = listOf(BlockRule("timer", "Timer", BlockTargets(),
        expiresAtEpochMs = anchor.wallEpochMs + duration, runtime = RuleRuntime("timer", anchor,
            duration, anchor.wallEpochMs + duration, "UTC", anchor)))

    @Test fun `R-18 projection reuses immutable snapshots for at most 100ms and replaces a new snapshot immediately`() {
        var now = anchor
        val cache = CachedRuleClockProjection { now }
        val source = rules()
        val first = cache.project(source)
        now = now.copy(wallEpochMs = now.wallEpochMs + 99, elapsedRealtimeMs = now.elapsedRealtimeMs + 99)
        assertSame(first, cache.project(source))
        val replacement = listOf(source.single().copy(name = "New publication"))
        assertEquals("New publication", cache.project(replacement).single().name)
        assertSame(first, cache.project(source))
        now = now.copy(wallEpochMs = now.wallEpochMs + 1, elapsedRealtimeMs = now.elapsedRealtimeMs + 1)
        assertNotSame(first, cache.project(source))
    }

    @Test fun `R-18 cache cannot carry active protection beyond an elapsed boundary including wall tick jitter`() {
        var now = anchor
        val cache = CachedRuleClockProjection { now }
        val source = rules(50)
        val first = cache.project(source)
        now = now.copy(wallEpochMs = anchor.wallEpochMs + 48, elapsedRealtimeMs = anchor.elapsedRealtimeMs + 50)
        val ended = cache.project(source)
        assertNotSame(first, ended)
        assertFalse(ended.single().runtime!!.evaluation!!.active)
    }

    @Test fun `R-18 cache cannot miss a recurring start inside its 100ms reuse window`() {
        val before = AccessClockStamp(Instant.parse("2026-10-28T11:59:59.950Z").toEpochMilli(), 1_000, "boot")
        var now = before
        val source = listOf(BlockRule("window", "Window", BlockTargets(),
            schedule = Schedule(startMinuteOfDay = 12 * 60, endMinuteOfDay = 13 * 60),
            runtime = RuleRuntime("inactive", before, 0, before.wallEpochMs, "UTC", before)))
        val cache = CachedRuleClockProjection { now }
        assertFalse(cache.project(source).single().runtime!!.evaluation!!.active)
        now = before.copy(wallEpochMs = before.wallEpochMs + 50, elapsedRealtimeMs = before.elapsedRealtimeMs + 50)
        assertTrue(cache.project(source).single().runtime!!.evaluation!!.active)
    }

    @Test fun `R-18 clock edit reboot or elapsed rollback invalidates a cached snapshot immediately`() {
        val changes = listOf(anchor.copy(wallEpochMs = anchor.wallEpochMs + 3_600_000),
            anchor.copy(wallEpochMs = anchor.wallEpochMs - 3_600_000),
            anchor.copy(bootSessionId = "new-boot"), anchor.copy(elapsedRealtimeMs = anchor.elapsedRealtimeMs - 1))
        for (changed in changes) {
            var now = anchor
            val cache = CachedRuleClockProjection { now }
            val source = rules()
            val first = cache.project(source)
            now = changed
            assertNotSame(first, cache.project(source))
        }
    }
}
