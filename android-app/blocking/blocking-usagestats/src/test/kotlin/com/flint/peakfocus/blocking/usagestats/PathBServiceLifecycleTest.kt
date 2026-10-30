package com.flint.peakfocus.blocking.usagestats

import com.flint.peakfocus.blocking.overlay.ProtectionStatus.PathBState
import com.flint.peakfocus.core.model.FallbackMode
import org.junit.Assert.*
import org.junit.Test

class PathBServiceLifecycleTest {
    private data class Published(val state: PathBState, val reasons: Set<String>, val until: Long?)
    private var now = 1_000L
    private val events = mutableListOf<Published>()
    private val health = PathBServiceLifecycle(nowElapsed = { now }, leaseMs = 90_000L) { state, reasons, until ->
        events += Published(state, reasons, until)
    }
    private val owner = Any()

    @Test fun `an accepted start request is unknown and cannot overwrite a live owner`() {
        health.stopRequested(health.stopGeneration(), false, emptySet())
        health.startRequested()
        assertEquals(PathBState.UNKNOWN, events.last().state)
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.STANDBY)
        val count = events.size
        health.startRequested()
        assertEquals(count, events.size)
    }

    @Test fun `startup is unknown until foreground publication succeeds`() {
        health.starting(owner)
        health.mode(owner, FallbackMode.ENFORCING)
        assertEquals(PathBState.UNKNOWN, events.last().state)
        health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.ENFORCING)
        assertEquals(Published(PathBState.ENFORCING, emptySet(), 91_000L), events.last())
    }

    @Test fun `standby is live but handoff is unknown`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.STANDBY)
        assertEquals(PathBState.STANDBY, events.last().state)
        health.handoff(owner)
        assertEquals(PathBState.UNKNOWN, events.last().state)
        health.mode(owner, FallbackMode.ENFORCING)
        assertEquals(PathBState.ENFORCING, events.last().state)
    }

    @Test fun `permission loss does not claim stopped before lifecycle termination`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.ENFORCING)
        health.unavailable(owner, "usage-access-missing")
        assertEquals(PathBState.UNKNOWN, events.last().state)
        health.stopped(owner)
        assertEquals(Published(PathBState.OFF, setOf("usage-access-missing"), null), events.last())
    }

    @Test fun `clean destruction is off and has no live lease`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.STANDBY); health.stopped(owner)
        assertEquals(Published(PathBState.OFF, emptySet(), null), events.last())
    }

    @Test fun `permission recovery replaces the missing access reason retained after shutdown`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.unavailable(owner, "usage-access-missing")
        health.stopped(owner)
        assertEquals(setOf("usage-access-missing"), events.last().reasons)
        val next = Any()
        health.starting(next); health.foregroundStarted(next)
        health.mode(next, FallbackMode.STANDBY)
        assertEquals(Published(PathBState.STANDBY, emptySet(), 91_000L), events.last())
    }

    @Test fun `a stop request is unknown until the running instance ends`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.ENFORCING)
        health.stopRequested(health.stopGeneration(), true, setOf("usage-access-missing"))
        assertEquals(Published(PathBState.UNKNOWN, setOf("usage-access-missing"), null), events.last())
        health.stopped(owner)
        assertEquals(PathBState.OFF, events.last().state)
    }

    @Test fun `only an observed absent service without a local owner is off`() {
        health.stopRequested(health.stopGeneration(), true, emptySet())
        assertEquals(PathBState.UNKNOWN, events.last().state)
        health.stopRequested(health.stopGeneration(), false, emptySet())
        assertEquals(PathBState.OFF, events.last().state)
        health.starting(owner)
        health.stopRequested(health.stopGeneration(), false, emptySet())
        assertEquals(PathBState.UNKNOWN, events.last().state)
    }

    @Test fun `failed permission or stop observations never infer off`() {
        health.gateFailed("usage-access-check-failed")
        assertEquals(Published(PathBState.UNKNOWN, setOf("usage-access-check-failed"), null), events.last())
        health.gateFailed("foreground-stop-failed")
        assertEquals(PathBState.UNKNOWN, events.last().state)
    }

    @Test fun `a late stop result cannot erase a completed destruction report`() {
        health.starting(owner); health.foregroundStarted(owner)
        val generation = health.stopGeneration()
        health.stopped(owner)
        val count = events.size
        health.stopRequested(generation, true, emptySet())
        health.stopFailed(generation)
        assertEquals(count, events.size)
        assertEquals(PathBState.OFF, events.last().state)
    }

    @Test fun `an old stop result cannot erase a replacement service health report`() {
        health.starting(owner); health.foregroundStarted(owner)
        val generation = health.stopGeneration()
        val replacement = Any()
        health.starting(replacement); health.foregroundStarted(replacement)
        health.mode(replacement, FallbackMode.STANDBY)
        health.stopRequested(generation, false, setOf("usage-access-missing"))
        health.stopFailed(generation)
        assertEquals(Published(PathBState.STANDBY, emptySet(), 91_000L), events.last())
    }

    @Test fun `replacement rejects old instance completion and destruction`() {
        health.starting(owner); health.foregroundStarted(owner)
        val replacement = Any()
        health.starting(replacement)
        val count = events.size
        health.mode(owner, FallbackMode.ENFORCING); health.stopped(owner)
        assertEquals(count, events.size)
        health.foregroundStarted(replacement); health.mode(replacement, FallbackMode.STANDBY)
        assertEquals(PathBState.STANDBY, events.last().state)
    }

    @Test fun `successful recheck clears previous service reasons`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.unavailable(owner, "usage-access-check-failed")
        health.mode(owner, FallbackMode.ENFORCING)
        assertTrue(events.last().reasons.isEmpty())
    }

    @Test fun `stable successful ticks renew at half lease rather than every tick`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.ENFORCING)
        val count = events.size
        repeat(44) { now += 1_000; health.mode(owner, FallbackMode.ENFORCING) }
        assertEquals(count, events.size)
        assertEquals(91_000L, events.last().until)
        now += 1_000
        health.mode(owner, FallbackMode.ENFORCING)
        assertEquals(count + 1, events.size)
        assertEquals(136_000L, events.last().until)
    }

    @Test fun `mode transitions and recovery publish immediately even within an existing lease`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.STANDBY)
        now += 1_000
        health.mode(owner, FallbackMode.ENFORCING)
        assertEquals(Published(PathBState.ENFORCING, emptySet(), 92_000L), events.last())
        health.unavailable(owner, "usage-access-check-failed")
        now += 1_000
        health.mode(owner, FallbackMode.ENFORCING)
        assertEquals(Published(PathBState.ENFORCING, emptySet(), 93_000L), events.last())
    }

    @Test fun `failed external permission probe cannot replace a live service observation`() {
        health.starting(owner); health.foregroundStarted(owner)
        health.mode(owner, FallbackMode.STANDBY)
        val count = events.size
        health.gateFailed("usage-access-check-failed")
        assertEquals(count, events.size)
        assertEquals(PathBState.STANDBY, events.last().state)
    }
}
