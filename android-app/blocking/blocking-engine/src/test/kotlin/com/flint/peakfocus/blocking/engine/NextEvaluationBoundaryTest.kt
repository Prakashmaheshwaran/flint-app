package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class NextEvaluationBoundaryTest {
    private val utc = ZoneId.of("UTC")
    private val ny = ZoneId.of("America/New_York")
    private fun ms(text: String) = Instant.parse(text).toEpochMilli()
    private fun rule(start: Int = 600, end: Int = 660, days: Set<Int> = emptySet()) =
        BlockRule("r", "r", BlockTargets(apps = setOf(AppRef("app"))), Schedule(days, start, end))
    private fun next(now: String, rule: BlockRule, zone: ZoneId = utc, remaining: Long? = null) =
        NextEvaluationBoundary.next(ms(now), zone, "app", listOf(rule), remaining)

    @Test fun `no rule or budget means no timer`() {
        assertNull(NextEvaluationBoundary.next(0, utc, "app", emptyList()))
    }

    @Test fun `screen off never repeatedly arms an unchanged short remaining budget`() {
        val now = ms("2026-10-28T12:00:00Z")
        val midnight = ms("2026-10-29T00:00:00Z")
        repeat(60) { second ->
            assertEquals(midnight, NextEvaluationBoundary.next(now + second * 1_000L, utc, "app", emptyList(), 10, interactive = false))
        }
        assertEquals(now + 10, NextEvaluationBoundary.next(now, utc, "app", emptyList(), 10, interactive = true))
    }

    @Test fun `screen off retains schedules and one shot expiry`() {
        val now = ms("2026-10-28T09:59:00Z")
        assertEquals(now + 60_000, NextEvaluationBoundary.next(now, utc, "app", listOf(rule()), 10, interactive = false))
        val expiring = rule().copy(expiresAtEpochMs = now + 30_000)
        assertEquals(now + 30_000, NextEvaluationBoundary.next(now, utc, "app", listOf(expiring), 10, interactive = false))
    }

    @Test fun `floor recovery stops its minute polling while screen is off`() {
        val now = ms("2026-10-28T09:00:00Z")
        val midnight = ms("2026-10-29T00:00:00Z")
        repeat(120) { second ->
            assertEquals(midnight, NextEvaluationBoundary.next(now + second * 1_000L, utc, "app", emptyList(), 10,
                interactive = false, boundedRecovery = true))
        }
        assertEquals(ms("2026-10-28T10:00:00Z"), NextEvaluationBoundary.next(now, utc, "app", listOf(rule()),
            interactive = false, boundedRecovery = true))
        assertEquals(now + 60_000, NextEvaluationBoundary.next(now, utc, "app", emptyList(), 0,
            interactive = true, boundedRecovery = true))
    }

    @Test fun `floor recovery retains nearer boundaries and otherwise rechecks within one minute`() {
        val now = ms("2026-10-28T09:59:45Z")
        assertEquals(now + 15_000, NextEvaluationBoundary.next(now, utc, "app", listOf(rule()), boundedRecovery = true))
        assertEquals(now + 5_000, NextEvaluationBoundary.next(now, utc, "app", emptyList(), 5_000, boundedRecovery = true))
        assertEquals(now + 60_000, NextEvaluationBoundary.next(now, utc, "app", listOf(rule(660, 720)), boundedRecovery = true))
        assertNull(NextEvaluationBoundary.next(now, utc, "app", emptyList(), boundedRecovery = true))
    }

    @Test fun `unavailable usage is retried before midnight without a short budget loop`() {
        val now = ms("2026-10-28T12:00:00Z")
        assertEquals(now + 60_000, NextEvaluationBoundary.next(now, utc, "app", emptyList(), -Long.MAX_VALUE, boundedRecovery = true))
    }

    @Test fun `disabled expired and unrelated rules need no timer`() {
        for (r in listOf(rule().copy(enabled = false), rule().copy(expiresAtEpochMs = 1), rule().copy(targets = BlockTargets()))) {
            assertNull(next("2026-10-28T09:00:00Z", r))
        }
    }

    @Test fun `stationary app wakes at schedule start and then end`() {
        assertEquals(ms("2026-10-28T10:00:00Z"), next("2026-10-28T09:59:20Z", rule()))
        assertEquals(ms("2026-10-28T11:00:00Z"), next("2026-10-28T10:00:00Z", rule()))
    }

    @Test fun `ISO weekday gate skips disallowed start`() {
        // Wednesday, for a Monday-only schedule; the bounded next wake is local midnight.
        assertEquals(ms("2026-10-29T00:00:00Z"), next("2026-10-28T09:00:00Z", rule(days = setOf(1))))
        assertEquals(ms("2026-10-26T10:00:00Z"), next("2026-10-26T09:00:00Z", rule(days = setOf(1))))
    }

    @Test fun `overnight tail belongs to previous ISO weekday`() {
        val monday = rule(22 * 60, 6 * 60, setOf(1))
        assertEquals(ms("2026-10-27T06:00:00Z"), next("2026-10-27T00:30:00Z", monday))
        assertEquals(ms("2026-10-26T22:00:00Z"), next("2026-10-26T00:30:00Z", monday))
    }

    @Test fun `Sunday overnight spills into Monday`() {
        assertEquals(ms("2026-10-26T06:00:00Z"), next("2026-10-26T00:30:00Z", rule(1320, 360, setOf(7))))
    }

    @Test fun `equal schedule endpoints never cause a zero delay loop`() {
        assertEquals(ms("2026-10-29T00:00:00Z"), next("2026-10-28T10:00:00Z", rule(600, 600)))
    }

    @Test fun `one shot expiry is exact and takes precedence over schedule`() {
        val r = rule().copy(expiresAtEpochMs = ms("2026-10-28T10:30:12Z"))
        assertEquals(ms("2026-10-28T10:30:12Z"), next("2026-10-28T10:00:00Z", r))
        assertNull(next("2026-10-28T10:30:12Z", r))
    }

    @Test fun `budget exhaustion beats schedule and spent budget waits for midnight`() {
        assertEquals(ms("2026-10-28T09:59:01Z"), next("2026-10-28T09:59:00Z", rule(), remaining = 1_000))
        assertEquals(ms("2026-10-29T00:00:00Z"), NextEvaluationBoundary.next(ms("2026-10-28T23:59:59Z"), utc, "app", emptyList(), 0))
    }

    @Test fun `budget resets at actual local midnight across short and long days`() {
        assertEquals(ms("2026-03-09T04:00:00Z"), NextEvaluationBoundary.next(ms("2026-03-08T05:00:00Z"), ny, "app", emptyList(), 99_000_000))
        assertEquals(ms("2026-11-02T05:00:00Z"), NextEvaluationBoundary.next(ms("2026-11-01T04:00:00Z"), ny, "app", emptyList(), 99_000_000))
    }

    @Test fun `spring gap activates at the jump when start minute does not exist`() {
        assertEquals(ms("2026-03-08T07:00:00Z"), next("2026-03-08T06:59:00Z", rule(150, 240), ny))
    }

    @Test fun `window wholly in spring gap is never armed`() {
        assertEquals(ms("2026-03-09T04:00:00Z"), next("2026-03-08T06:59:00Z", rule(130, 150), ny))
    }

    @Test fun `fall overlap visits both start instants and detects the backwards jump`() {
        val r = rule(90, 120)
        assertEquals(ms("2026-11-01T05:30:00Z"), next("2026-11-01T05:00:00Z", r, ny))
        assertEquals(ms("2026-11-01T06:00:00Z"), next("2026-11-01T05:30:00Z", r, ny))
        assertEquals(ms("2026-11-01T06:30:00Z"), next("2026-11-01T06:00:00Z", r, ny))
        assertEquals(ms("2026-11-01T07:00:00Z"), next("2026-11-01T06:30:00Z", r, ny))
    }

    @Test fun `allow list and domain rules can schedule a foreground recheck`() {
        val r = rule().copy(targets = BlockTargets(allowListMode = true))
        assertEquals(ms("2026-10-28T10:00:00Z"), next("2026-10-28T09:00:00Z", r))
        assertNull(next("2026-10-28T09:00:00Z", r.copy(targets = r.targets.copy(apps = setOf(AppRef("app"))))))
        assertEquals(ms("2026-10-28T10:00:00Z"), next("2026-10-28T09:00:00Z", rule().copy(targets = BlockTargets(domains = setOf(DomainRef("example.com"))))))
    }

    @Test fun `multiple rules choose earliest future boundary`() {
        assertEquals(ms("2026-10-28T09:30:00Z"), NextEvaluationBoundary.next(ms("2026-10-28T09:00:00Z"), utc, "app", listOf(rule(), rule(570, 600))))
    }

    @Test fun `clock and timezone changes recompute from the new local time`() {
        assertEquals(ms("2026-10-28T14:00:00Z"), next("2026-10-28T13:59:00Z", rule(), ny))
        assertEquals(ms("2026-10-28T10:00:00Z"), next("2026-10-28T09:59:00Z", rule(), utc))
    }

    @Test fun `candidate boundaries agree with minute by minute engine across DST and weekdays`() {
        val engine = BlockDecisionEngine()
        val dates = listOf("2026-03-08T05:00:00Z", "2026-11-01T04:00:00Z", "2026-10-26T04:00:00Z")
        val windows = listOf(0 to 60, 60 to 90, 90 to 120, 120 to 150, 150 to 240, 1320 to 360, 0 to 1440, 600 to 600)
        for (date in dates) for ((start, end) in windows) for (days in listOf(emptySet(), setOf(1), setOf(7))) {
            val r = rule(start, end, days)
            for (elapsed in listOf(0L, 30, 90, 119, 120, 150, 180, 1_200)) {
                val now = ms(date) + elapsed * 60_000L
                val midnight = Instant.ofEpochMilli(now).atZone(ny).toLocalDate().plusDays(1)
                    .atStartOfDay(ny).toInstant().toEpochMilli()
                fun active(at: Long): Boolean {
                    val local = Instant.ofEpochMilli(at).atZone(ny)
                    return engine.scheduleActive(r.schedule, local.hour * 60 + local.minute, local.dayOfWeek.value)
                }
                var expected = midnight
                var candidate = now + 60_000L
                while (candidate < midnight) {
                    if (active(candidate - 1) != active(candidate)) { expected = candidate; break }
                    candidate += 60_000L
                }
                assertEquals("$date $start-$end $days +$elapsed", expected,
                    NextEvaluationBoundary.next(now, ny, "app", listOf(r)))
            }
        }
    }
}
