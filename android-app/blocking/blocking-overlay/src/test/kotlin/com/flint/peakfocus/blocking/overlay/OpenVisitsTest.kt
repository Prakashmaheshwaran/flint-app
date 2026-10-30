package com.flint.peakfocus.blocking.overlay

import org.junit.Assert.*
import org.junit.Test

class OpenVisitsTest {
    @Test fun twoDetectionPathsShareOneVisitAndAnAcknowledgedLastOpenStaysPaid() {
        val visits = OpenVisits()
        val a = visits.observe("com.a", true, true, 1_000)!!
        val b = visits.observe("com.a", true, true, 1_001)!!
        assertSame(a, b)
        assertFalse(a.paid)
        visits.acknowledge(a)
        assertTrue(visits.current("com.a")!!.paid)
    }
    @Test fun systemBouncePreservesPaidVisitButAnotherRealAppStartsANewOne() {
        val visits = OpenVisits()
        val first = visits.observe("com.a", true, true, 1_000)!!
        visits.acknowledge(first)
        visits.observe("system", false, false, 1_100)
        assertSame(first, visits.observe("com.a", true, false, 1_200))
        visits.observe("com.b", true, true, 1_300)
        val reopened = visits.observe("com.a", true, true, 1_400)!!
        assertNotEquals(first.id, reopened.id)
        assertFalse(reopened.paid)
    }
    @Test fun delayedAcknowledgementCannotPayForADifferentNewVisit() {
        val visits = OpenVisits()
        val old = visits.observe("com.a", true, true, 1_000)!!
        visits.leave()
        val next = visits.observe("com.a", true, true, 1_100)!!
        visits.acknowledge(old)
        assertFalse(next.paid)
    }
    @Test fun unacceptedExpiredIdentityCanBeRefreshedWithoutReusingAnAcceptedOne() {
        val visits = OpenVisits()
        val first = visits.observe("com.a", true, true, 1_000)!!
        val refreshed = visits.observe("com.a", true, true, 1_000 + DurableGrantController.RETRY_HORIZON_MS)!!
        assertNotEquals(first.id, refreshed.id)
        visits.acknowledge(refreshed)
        assertSame(refreshed, visits.observe("com.a", true, false, 1_000 + 2 * DurableGrantController.RETRY_HORIZON_MS))
    }
}
