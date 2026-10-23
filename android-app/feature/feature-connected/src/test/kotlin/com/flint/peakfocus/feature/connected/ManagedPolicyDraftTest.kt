package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.model.ManagedRule
import com.flint.peakfocus.core.model.ManagedRuleScope
import com.flint.peakfocus.core.model.ManagedSchedule
import com.flint.peakfocus.core.model.ManagedTargets
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ManagedPolicyDraftTest {
    private val members = listOf(ConnectedMember("owner", "Owner", "owner"), ConnectedMember("member", "Taylor", "member"))
    private val base = ManagedPolicyDraft(name = "Study", allApps = true)
    private fun input(draft: ManagedPolicyDraft) = managedPolicyInput(draft, members, "owner")

    @Test fun `recurring schedule uses ISO start days and preserves overnight window`() {
        val result = input(base.copy(zoneId = "America/New_York", startTime = "22:30", endTime = "06:00", isoWeekdays = setOf(7, 1)))
        assertEquals(ManagedSchedule.Recurring(1350, 360, setOf(7, 1)), result.schedule)
        assertEquals(ManagedRuleScope.Group, result.scope)
        assertEquals(ManagedTargets.AllApps, result.targets)
        assertFalse(result.unblocksAllowed)
    }

    @Test fun `new session defaults cross UTC midnight with exactly one hour duration`() {
        val draft = newManagedPolicyDraft(Instant.parse("2026-10-11T23:45:50Z").toEpochMilli())
            .copy(name = "Rest", allApps = true, kind = ManagedPolicyKind.SESSION)
        val session = input(draft).schedule as ManagedSchedule.Session
        assertEquals("2026-10-11", draft.startDate)
        assertEquals("2026-10-12", draft.endDate)
        assertEquals("23:45", draft.startTime)
        assertEquals("00:45", draft.endTime)
        assertEquals(3_600_000L, session.endsAtEpochMs - session.startsAtEpochMs)
    }

    @Test fun `session draft roundtrip preserves seconds milliseconds targets and rule revision`() {
        val schedule = ManagedSchedule.Session(Instant.parse("2026-10-11T10:00:15.123Z").toEpochMilli(),
            Instant.parse("2026-10-11T11:02:05.456Z").toEpochMilli())
        val rule = ManagedRule("rule", "group", 7, "Work", "UTC", false, ManagedRuleScope.Member("member"),
            schedule, ManagedTargets.Selection(setOf("com.example.app"), setOf("example.com")), true, 1, 2)
        val draft = rule.toPolicyDraft()
        val result = input(draft)
        assertEquals(7L, draft.expectedRevision)
        assertEquals(schedule, result.schedule)
        assertEquals(rule.targets, result.targets)
        assertEquals(rule.scope, result.scope)
        assertTrue(result.unblocksAllowed)
        assertFalse(result.enabled)
    }

    @Test fun `only current non-owner members can be individually targeted`() {
        assertEquals(ManagedRuleScope.Member("member"), input(base.copy(memberUserId = "member")).scope)
        invalid(base.copy(memberUserId = "owner"))
        invalid(base.copy(memberUserId = "left-member"))
    }

    @Test fun `selected targets reject duplicates invalid packages and invalid websites`() {
        val valid = base.copy(allApps = false, packagesText = "com.example.one\ncom.example.two", domainsText = "example.com")
        assertEquals(2, (input(valid).targets as ManagedTargets.Selection).packages.size)
        invalid(valid.copy(packagesText = "com.example.one\ncom.example.one"))
        invalid(valid.copy(packagesText = "not-a-package"))
        invalid(valid.copy(domainsText = "https://example.com/path"))
        invalid(valid.copy(domainsText = "127.0.0.1"))
        invalid(valid.copy(domainsText = "Example.com"))
        invalid(valid.copy(domainsText = "*.example.com"))
        invalid(valid.copy(packagesText = "", domainsText = ""))
        invalid(valid.copy(packagesText = (1..21).joinToString("\n") { "com.example.app$it" }))
    }

    @Test fun `session bounds use strict calendar UTC instants and seven day maximum`() {
        val valid = base.copy(kind = ManagedPolicyKind.SESSION, startDate = "2026-10-11", endDate = "2026-10-18",
            startTime = "12:00", endTime = "12:00")
        assertTrue(input(valid).schedule is ManagedSchedule.Session)
        invalid(valid.copy(endTime = "12:01"))
        invalid(valid.copy(endDate = valid.startDate))
        invalid(valid.copy(startDate = "2026-02-30"))
        invalid(valid.copy(startTime = "24:00"))
    }

    @Test fun `recurrence rejects equal times empty weekdays and non-IANA zones`() {
        invalid(base.copy(startTime = "09:00", endTime = "09:00"))
        invalid(base.copy(isoWeekdays = emptySet()))
        invalid(base.copy(isoWeekdays = setOf(0)))
        invalid(base.copy(zoneId = "EST"))
        invalid(base.copy(zoneId = "+05:30"))
        invalid(base.copy(zoneId = "Fake/Nowhere"))
    }

    private fun invalid(draft: ManagedPolicyDraft) {
        assertThrows(IllegalArgumentException::class.java) { input(draft) }
    }
}
