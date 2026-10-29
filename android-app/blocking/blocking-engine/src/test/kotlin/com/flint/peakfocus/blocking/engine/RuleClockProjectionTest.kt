package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RuleClockProjectionTest {
    private val hour = 3_600_000L
    private val zone = ZoneId.of("UTC")
    private val start = Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()
    private val anchor = AccessClockStamp(start, 1_000, "boot-a")
    private val schedule = Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)
    private val engine = BlockDecisionEngine()
    private fun rule() = BlockRule("work", "Work", BlockTargets(apps = setOf(AppRef("com.target"))),
        schedule, BreakLevel.HARDCORE, runtime = RuleRuntime(
            (start - hour).toString(), anchor, 7 * hour, start + 7 * hour, "UTC", anchor))

    private fun verdict(rule: BlockRule, now: AccessClockStamp, deviceZone: ZoneId = zone): Verdict {
        val local = Instant.ofEpochMilli(now.wallEpochMs).atZone(deviceZone)
        return engine.decide("com.target", null, listOf(rule), "com.flint",
            local.hour * 60 + local.minute, local.dayOfWeek.value, now.wallEpochMs)
    }

    @After fun clearHolder() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        ActiveRulesHolder.configureProjection { it }
    }

    @Test fun `R_18 PC_54 forward wall jump cannot end a retained active occurrence`() {
        var now = anchor
        ActiveRulesHolder.configureProjection { RuleClockProjection.project(it, now) }
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule()))
        now = AccessClockStamp(start + 8 * hour, anchor.elapsedRealtimeMs + 60_000, "boot-a")
        val projected = ActiveRulesHolder.rules.single()
        assertTrue(verdict(projected, now) is Verdict.Block)
        assertEquals(now.wallEpochMs + 7 * hour - 60_000, projected.expiresAtEpochMs)
        assertEquals(schedule, projected.schedule)
        assertEquals(projected, (ActiveRulesHolder.dataStoreState as StorageState.Ready).value.single())
        // An unrelated writer and failed storage refresh cannot freeze the old wall estimate.
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, StorageState.Unavailable(StorageFailure.IO))
        now = now.copy(wallEpochMs = start - 3 * hour, elapsedRealtimeMs = now.elapsedRealtimeMs + 60_000)
        assertEquals(now.wallEpochMs + 7 * hour - 120_000, ActiveRulesHolder.rules.single().expiresAtEpochMs)
        assertTrue(verdict(ActiveRulesHolder.rules.single(), now) is Verdict.Block)
    }

    @Test fun `R_18 PC_55 rollback cannot extend or replay the elapsed occurrence`() {
        val end = AccessClockStamp(start + hour, anchor.elapsedRealtimeMs + 7 * hour, "boot-a")
        assertEquals(Verdict.Allow, verdict(RuleClockProjection.project(rule(), end), end))
        val before = end.copy(elapsedRealtimeMs = end.elapsedRealtimeMs - 1)
        val projected = RuleClockProjection.project(rule(), before)
        assertTrue(verdict(projected, before) is Verdict.Block)
        assertEquals(before.wallEpochMs + 1, projected.expiresAtEpochMs)
        assertEquals(before.wallEpochMs + 1, NextEvaluationBoundary.next(before.wallEpochMs, zone,
            "com.target", listOf(projected)))
    }

    @Test fun `R_18 PC_56 device zone cannot move active or future saved-zone windows`() {
        val movedZone = ZoneId.of("Pacific/Auckland")
        assertTrue(verdict(RuleClockProjection.project(rule(), anchor), anchor, movedZone) is Verdict.Block)
        val inactive = rule().copy(runtime = rule().runtime!!.copy(occurrenceId = "inactive", remainingAtAnchorMs = 0))
        val early = anchor.copy(wallEpochMs = start - 2 * hour, elapsedRealtimeMs = 100)
        val waiting = inactive.copy(runtime = inactive.runtime!!.copy(timeline = early, anchor = early))
        val projected = RuleClockProjection.project(waiting, early)
        assertEquals(Verdict.Allow, verdict(projected, early, movedZone))
        assertEquals(early.wallEpochMs + hour, NextEvaluationBoundary.next(early.wallEpochMs, movedZone,
            "com.target", listOf(projected)))
        val later = early.copy(wallEpochMs = early.wallEpochMs + 10 * hour, elapsedRealtimeMs = early.elapsedRealtimeMs + hour)
        assertTrue(verdict(RuleClockProjection.project(waiting, later), later, movedZone) is Verdict.Block)
    }

    @Test fun `R_18 PC_58 reboot uses bounded wall fallback and never replays an expired occurrence`() {
        val newBoot = anchor.copy(wallEpochMs = start + 6 * hour, elapsedRealtimeMs = 500, bootSessionId = "boot-b")
        assertEquals(newBoot.wallEpochMs + hour, RuleClockProjection.project(rule(), newBoot).expiresAtEpochMs)
        val pastEnd = newBoot.copy(wallEpochMs = start + 8 * hour)
        assertEquals(Verdict.Allow, verdict(RuleClockProjection.project(rule(), pastEnd), pastEnd))
        val completed = rule().copy(runtime = rule().runtime!!.copy(remainingAtAnchorMs = 0))
        val rollback = newBoot.copy(wallEpochMs = start)
        assertEquals(Verdict.Allow, verdict(RuleClockProjection.project(completed, rollback), rollback))
        val unknownBoot = newBoot.copy(bootSessionId = "")
        assertEquals(newBoot.wallEpochMs + hour, RuleClockProjection.project(rule(), unknownBoot).expiresAtEpochMs)
    }

    @Test fun `R_18 PC_55 finite timer cannot resurrect and queued template cannot start its neighbor`() {
        val timer = rule().copy(schedule = null, expiresAtEpochMs = start + 7 * hour)
        val tomorrow = anchor.copy(wallEpochMs = start + 24 * hour, elapsedRealtimeMs = anchor.elapsedRealtimeMs + 24 * hour)
        assertEquals(Verdict.Allow, verdict(RuleClockProjection.project(timer, tomorrow), tomorrow))
        val queued = rule().copy(runtime = rule().runtime!!.copy(stopAtOccurrenceEnd = true))
        assertEquals(Verdict.Allow, verdict(RuleClockProjection.project(queued, tomorrow), tomorrow))
        val next = RuleClockProjection.project(rule(), tomorrow)
        assertTrue(verdict(next, tomorrow) is Verdict.Block)
        assertEquals(tomorrow.wallEpochMs + 7 * hour, next.expiresAtEpochMs)
    }

    @Test fun `R_18 PC_57 DST endpoints choose earlier start later end and shift missing endpoint by gap`() {
        val ny = ZoneId.of("America/New_York")
        val overlap = ScheduleOccurrences.onDate(Schedule(startMinuteOfDay = 75, endMinuteOfDay = 105),
            ny, LocalDate.parse("2026-11-01"))!!
        assertEquals(Instant.parse("2026-11-01T05:15:00Z").toEpochMilli(), overlap.startsAtEpochMs)
        assertEquals(Instant.parse("2026-11-01T06:45:00Z").toEpochMilli(), overlap.endsAtEpochMs)
        for (instant in listOf("2026-11-01T05:20:00Z", "2026-11-01T06:20:00Z")) {
            assertEquals(overlap, ScheduleOccurrences.containing(Schedule(startMinuteOfDay = 75, endMinuteOfDay = 105),
                ny, Instant.parse(instant).toEpochMilli()))
        }
        val gap = ScheduleOccurrences.onDate(Schedule(startMinuteOfDay = 150, endMinuteOfDay = 240),
            ny, LocalDate.parse("2026-03-08"))!!
        assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(), gap.startsAtEpochMs)
        assertEquals(30 * 60_000L, gap.endsAtEpochMs - gap.startsAtEpochMs)
    }

    @Test fun `R_18 PC_57 overnight DST tail belongs to start date and duplicate reads preserve identity`() {
        val ny = ZoneId.of("America/New_York")
        val overnight = Schedule(setOf(6), 22 * 60, 6 * 60)
        val at = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        val occurrence = ScheduleOccurrences.containing(overnight, ny, at)!!
        assertEquals(9 * hour, occurrence.endsAtEpochMs - occurrence.startsAtEpochMs)
        val stamp = AccessClockStamp(at, 10, "boot-a")
        val rule = rule().copy(schedule = overnight, runtime = RuleRuntime(occurrence.id, stamp,
            occurrence.endsAtEpochMs - at, occurrence.endsAtEpochMs, ny.id, stamp))
        repeat(3) {
            val projected = RuleClockProjection.project(rule, stamp)
            assertTrue(verdict(projected, stamp) is Verdict.Block)
            assertEquals(occurrence.id, projected.runtime!!.occurrenceId)
            assertEquals(occurrence.endsAtEpochMs, projected.expiresAtEpochMs)
        }
        assertNull(ScheduleOccurrences.containing(overnight, ny, at + 24 * hour))
    }

    @Test fun `R_18 anchored rules preserve safety floor and unanchored legacy behavior`() {
        val now = anchor.copy(wallEpochMs = start + 8 * hour)
        val protected = RuleClockProjection.project(rule().copy(targets = BlockTargets(allowListMode = true)), now)
        assertEquals(Verdict.Allow, engine.decide("com.android.settings", null, listOf(protected), "com.flint"))
        val legacy = rule().copy(runtime = null)
        assertEquals(legacy, RuleClockProjection.project(legacy, now))
        assertEquals(Verdict.Allow, verdict(legacy, now))
    }
}
