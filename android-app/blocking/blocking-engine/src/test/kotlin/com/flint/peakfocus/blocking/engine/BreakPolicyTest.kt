package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BreakDecision
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakPolicyTest {

    private val policy = BreakPolicy()
    private val now = 1_000_000L

    @Test
    fun easyGrantsImmediatelyAndCountsTheBreak() {
        val decision = policy.requestBreak(BreakLevel.EASY, BreakSessionState(), now)
        assertTrue(decision is BreakDecision.Granted)
        val session = (decision as BreakDecision.Granted).session
        assertEquals(1, session.breaksTaken)
        assertNull(session.pending)
    }

    @Test
    fun easyGrantEvenWithStalePendingClearsIt() {
        val stale = BreakSessionState(breaksTaken = 2, pending = BreakRequestState(now - 10, now + 10))
        val decision = policy.requestBreak(BreakLevel.EASY, stale, now)
        val session = (decision as BreakDecision.Granted).session
        assertEquals(3, session.breaksTaken)
        assertNull(session.pending)
    }

    @Test
    fun harderFirstAskArmsTheFrictionDelay() {
        val decision = policy.requestBreak(BreakLevel.HARDER, BreakSessionState(), now)
        assertTrue(decision is BreakDecision.Pending)
        val pending = (decision as BreakDecision.Pending).session.pending!!
        assertEquals(now, pending.requestedAtEpochMs)
        assertEquals(now + 60_000L, pending.effectiveAtEpochMs) // first delay: 60s
    }

    @Test
    fun harderStaysPendingUntilTheDelayElapses() {
        val armed = (policy.requestBreak(BreakLevel.HARDER, BreakSessionState(), now) as BreakDecision.Pending).session
        val again = policy.requestBreak(BreakLevel.HARDER, armed, now + 59_999L)
        assertTrue(again is BreakDecision.Pending)
        assertEquals(armed, (again as BreakDecision.Pending).session) // unchanged, still counting down
    }

    @Test
    fun harderGrantsOnceTheDelayHasElapsed() {
        val armed = (policy.requestBreak(BreakLevel.HARDER, BreakSessionState(), now) as BreakDecision.Pending).session
        val decision = policy.requestBreak(BreakLevel.HARDER, armed, now + 60_000L)
        assertTrue(decision is BreakDecision.Granted)
        val session = (decision as BreakDecision.Granted).session
        assertEquals(1, session.breaksTaken)
        assertNull(session.pending)
    }

    @Test
    fun harderDelayEscalatesWithBreaksTakenAndClamps() {
        assertEquals(60L, policy.harderDelaySeconds(0))
        assertEquals(300L, policy.harderDelaySeconds(1))
        assertEquals(900L, policy.harderDelaySeconds(2))
        assertEquals(900L, policy.harderDelaySeconds(7))   // clamps at the last entry
        assertEquals(60L, policy.harderDelaySeconds(-1))   // defensive clamp low
    }

    @Test
    fun harderSecondBreakArmsTheLongerDelay() {
        val afterOneBreak = BreakSessionState(breaksTaken = 1)
        val decision = policy.requestBreak(BreakLevel.HARDER, afterOneBreak, now)
        val pending = (decision as BreakDecision.Pending).session.pending!!
        assertEquals(now + 300_000L, pending.effectiveAtEpochMs) // second delay: 5 min
    }

    @Test
    fun hardcoreIsAlwaysDenied() {
        assertEquals(BreakDecision.DeniedHardcore, policy.requestBreak(BreakLevel.HARDCORE, BreakSessionState(), now))
        val withElapsedPending = BreakSessionState(pending = BreakRequestState(now - 100, now - 1))
        assertEquals(BreakDecision.DeniedHardcore, policy.requestBreak(BreakLevel.HARDCORE, withElapsedPending, now))
    }

    @Test
    fun cancelPendingClearsOnlyThePendingRequest() {
        val armed = BreakSessionState(breaksTaken = 2, pending = BreakRequestState(now, now + 60_000L))
        val cancelled = policy.cancelPending(armed)
        assertNull(cancelled.pending)
        assertEquals(2, cancelled.breaksTaken)
    }

    @Test
    fun customDelaysAreRespected() {
        val quick = BreakPolicy(harderDelaysSeconds = listOf(10L))
        val decision = quick.requestBreak(BreakLevel.HARDER, BreakSessionState(breaksTaken = 4), now)
        val pending = (decision as BreakDecision.Pending).session.pending!!
        assertEquals(now + 10_000L, pending.effectiveAtEpochMs)
    }

    @Test
    fun rejectsInvalidDelayConfigurations() {
        assertThrows(IllegalArgumentException::class.java) { BreakPolicy(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { BreakPolicy(listOf(60L, -1L)) }
    }
}
