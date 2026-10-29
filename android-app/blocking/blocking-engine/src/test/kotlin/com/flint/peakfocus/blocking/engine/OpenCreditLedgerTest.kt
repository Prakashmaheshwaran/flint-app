package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OpenCreditLedgerTest {
    private val base = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private fun at(ms: Long = 0, wall: Long = ms, boot: String = "boot") = AccessClockStamp(base + wall, 1_000 + ms, boot)
    private fun initial(count: Int = 0) = OpenCreditLedgerPolicy.initial(at(), "UTC", mapOf("app" to count))
    private fun accept(value: OpenCreditLedger, id: String = "p1-request", now: AccessClockStamp = at(),
                       caps: List<Int> = listOf(3), blockers: List<String> = emptyList(), floor: Boolean = false,
                       target: String = "app") =
        OpenCreditLedgerPolicy.acceptOpenCredit(value, target, id, caps, blockers, floor, now)

    @Test fun `PC-28 PC-30 exactly three explicit credits and last zero-left interval remains usable`() {
        var ledger = initial()
        repeat(3) { index ->
            val now = at(index * 300_000L)
            val mutation = accept(ledger, "p1-$index", now)
            val result = mutation.acceptance as OpenCreditPolicy.Acceptance.Accepted
            assertEquals(300_000L, result.grant.durationMs)
            assertEquals(2 - index, result.remainingCredits)
            assertEquals(300_000L, mutation.next.active.getValue("app").interval.remainingMs(now))
            ledger = mutation.next
        }
        assertEquals(0, OpenCreditLedgerPolicy.remainingCredits(ledger, "app", listOf(3)))
        assertEquals(OpenCreditPolicy.Acceptance.Denied(OpenCreditPolicy.Denial.EXHAUSTED),
            accept(ledger, "p1-fourth", at(900_000)).acceptance)
        assertEquals(3, ledger.counters.getValue("app").used)
    }

    @Test fun `PC-22 credit expires at 300 seconds without another foreground transition`() {
        val paid = accept(initial()).next
        assertTrue(OpenCreditLedgerPolicy.reconcile(paid, at(299_999)).next.active.containsKey("app"))
        val expired = OpenCreditLedgerPolicy.reconcile(paid, at(300_000))
        assertTrue(expired.next.active.isEmpty())
        assertEquals(setOf("p1-request"), expired.endedOperationIds)
        assertEquals(1, expired.next.counters.getValue("app").used)
    }

    @Test fun `PC-23 PC-31 repeated callback and distinct request reuse the exact interval`() {
        val paid = accept(initial()).next
        val exact = paid.active.getValue("app")
        val duplicate = accept(paid, now = at(100_000))
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("p1-request", exact.grant), duplicate.acceptance)
        val other = accept(duplicate.next, "p1-other", at(200_000))
        assertEquals(OpenCreditPolicy.Acceptance.AlreadyActive(exact.grant, 100_000), other.acceptance)
        assertEquals(exact, other.next.active.getValue("app"))
        assertEquals(1, other.next.counters.getValue("app").used)
        val expiredRetry = accept(other.next, now = at(300_001))
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("p1-request", null), expiredRetry.acceptance)
        assertTrue(expiredRetry.next.active.isEmpty())
    }

    @Test fun `PC-31 operation identity cannot be rebound to another target`() {
        val paid = accept(initial()).next
        val rebound = accept(paid, target = "other")
        assertEquals(OpenCreditPolicy.Acceptance.Denied(OpenCreditPolicy.Denial.UNAVAILABLE), rebound.acceptance)
        assertFalse(rebound.next.counters.containsKey("other"))
    }

    @Test fun `PC-32 PC-34 PC-36 blockers floor no-limit zero-cap and over-cap never spend`() {
        val untouched = initial()
        val cases = listOf(
            accept(untouched, blockers = listOf("Hardcore work")) to OpenCreditPolicy.Denial.BLOCKED_BY_OTHER_CAUSE,
            accept(untouched, floor = true) to OpenCreditPolicy.Denial.SAFETY_FLOOR,
            accept(untouched, caps = emptyList()) to OpenCreditPolicy.Denial.NO_LIMIT,
            accept(untouched, caps = listOf(0)) to OpenCreditPolicy.Denial.ZERO_CAP,
            accept(initial(9), caps = listOf(5, 2)) to OpenCreditPolicy.Denial.EXHAUSTED,
        )
        cases.forEach { (mutation, reason) ->
            assertEquals(reason, (mutation.acceptance as OpenCreditPolicy.Acceptance.Denied).reason)
            assertTrue(mutation.next.active.isEmpty())
            assertTrue(mutation.next.counters.getValue("app").consumedOperationIds.isEmpty())
        }
        assertEquals(9, cases.last().first.next.counters.getValue("app").used)
        val last = accept(initial(1), caps = listOf(5, 2)).acceptance as OpenCreditPolicy.Acceptance.Accepted
        assertEquals(0, last.remainingCredits)
    }

    @Test fun `PC-35 midnight clips active access closes old period once and retains duplicate id`() {
        val midnight = at().copy(wallEpochMs = Instant.parse("2026-10-28T23:58:00Z").toEpochMilli())
        val old = OpenCreditLedgerPolicy.initial(midnight, "UTC")
        val paid = accept(old, now = midnight)
        assertEquals(120_000L, (paid.acceptance as OpenCreditPolicy.Acceptance.Accepted).grant.durationMs)
        val end = midnight.copy(wallEpochMs = midnight.wallEpochMs + 120_000, elapsedRealtimeMs = midnight.elapsedRealtimeMs + 120_000)
        val rolled = OpenCreditLedgerPolicy.reconcile(paid.next, end)
        assertTrue(rolled.periodChanged); assertTrue(rolled.next.active.isEmpty())
        assertEquals(old.periodId + 1, rolled.next.periodId)
        assertEquals(0, rolled.next.counters.getValue("app").used)
        assertEquals(setOf("p1-request"), rolled.next.counters.getValue("app").previousPeriodOperationIds)
        val retry = accept(rolled.next, now = end)
        assertEquals(OpenCreditPolicy.Acceptance.Duplicate("p1-request", null), retry.acceptance)
        assertFalse(retry.periodChanged)
        assertEquals(rolled.next.periodId, OpenCreditLedgerPolicy.reconcile(retry.next, end).next.periodId)
    }

    @Test fun `PC-54 PC-55 wall edits neither refill nor shorten the following anchored day`() {
        val original = initial(3)
        val forward = OpenCreditLedgerPolicy.reconcile(original, at(60_000, 3 * 86_400_000L)).next
        assertTrue(forward.timeUncertain)
        assertEquals(3, forward.counters.getValue("app").used)
        assertEquals(12 * 3_600_000L - 60_000, forward.reset.remainingToResetMs)
        val backward = OpenCreditLedgerPolicy.reconcile(forward, at(120_000, -86_400_000L)).next
        assertEquals(original.periodId, backward.periodId)
        val reset = OpenCreditLedgerPolicy.reconcile(backward, at(12 * 3_600_000L, -86_400_000L)).next
        assertEquals(original.periodId + 1, reset.periodId)
        assertEquals(24 * 3_600_000L, reset.reset.remainingToResetMs)
        assertEquals(base + 12 * 3_600_000L, reset.logicalCheckpoint.wallEpochMs)
    }

    @Test fun `PC-54 discontinuity threshold is strictly greater than five seconds`() {
        assertFalse(OpenCreditLedgerPolicy.reconcile(initial(), at(1_000, 6_000)).next.timeUncertain)
        assertTrue(OpenCreditLedgerPolicy.reconcile(initial(), at(1_000, 6_001)).next.timeUncertain)
    }

    @Test fun `PC-56 saved quota zone survives process travel and DST has one bounded period`() {
        listOf("2026-03-08T05:00:00Z" to 23L, "2026-11-01T04:00:00Z" to 25L).forEach { (date, hours) ->
            val stamp = AccessClockStamp(Instant.parse(date).toEpochMilli(), 1_000, "boot")
            val ledger = OpenCreditLedgerPolicy.initial(stamp, "America/New_York")
            assertEquals(hours * 3_600_000, ledger.reset.remainingToResetMs)
            val resumed = OpenCreditLedgerPolicy.reconcile(ledger, stamp.copy(elapsedRealtimeMs = 2_000, wallEpochMs = stamp.wallEpochMs + 1_000)).next
            assertEquals("America/New_York", resumed.quotaZone)
            assertEquals(hours * 3_600_000 - 1_000, resumed.reset.remainingToResetMs)
        }
    }

    @Test fun `PC-54 reboot ends credit preserves consumed quota and credits no powered off interval`() {
        val paid = accept(initial(2)).next
        val checked = OpenCreditLedgerPolicy.reconcile(paid, at(60_000)).next
        val reboot = at(5, 5 * 86_400_000L, "new-boot")
        val resumed = OpenCreditLedgerPolicy.reconcile(checked, reboot).next
        assertTrue(resumed.active.isEmpty()); assertTrue(resumed.accessEndedAfterRestart)
        assertTrue(resumed.timeUncertain)
        assertEquals(3, resumed.counters.getValue("app").used)
        assertEquals(checked.reset.remainingToResetMs, resumed.reset.remainingToResetMs)
        assertEquals(OpenCreditPolicy.Denial.EXHAUSTED, (accept(resumed, "p1-new", reboot).acceptance as OpenCreditPolicy.Acceptance.Denied).reason)
        val end = reboot.copy(elapsedRealtimeMs = reboot.elapsedRealtimeMs + resumed.reset.remainingToResetMs,
            wallEpochMs = reboot.wallEpochMs + resumed.reset.remainingToResetMs)
        val rolled = OpenCreditLedgerPolicy.reconcile(resumed, end)
        assertTrue(rolled.periodChanged)
        assertEquals(paid.periodId + 1, rolled.next.periodId)
        assertEquals(0, rolled.next.counters.getValue("app").used)
    }

    @Test fun `PC-35 long process absence performs one reset without replaying missed midnights`() {
        val old = initial(3)
        val rolled = OpenCreditLedgerPolicy.reconcile(old, at(10 * 86_400_000L))
        assertTrue(rolled.periodChanged)
        assertEquals(old.periodId + 1, rolled.next.periodId)
        assertEquals(12 * 3_600_000L, rolled.next.reset.remainingToResetMs)
    }

    @Test fun `PC-37 exhausted period identity reports period boundary without reset loop`() {
        val old = initial().let { value ->
            val reset = value.reset.copy(remainingToResetMs = 0)
            value.copy(periodId = Long.MAX_VALUE, reset = reset, counters = mapOf("app" to
                value.counters.getValue("app").copy(periodId = Long.MAX_VALUE, reset = reset)))
        }
        val result = accept(old)
        assertEquals(OpenCreditPolicy.Acceptance.Denied(OpenCreditPolicy.Denial.PERIOD_BOUNDARY), result.acceptance)
        assertEquals(Long.MAX_VALUE, result.next.periodId)
        assertEquals(0, result.next.counters.getValue("app").used)
    }
}
