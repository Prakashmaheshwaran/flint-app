package com.flint.peakfocus.blocking.engine

import org.junit.Assert.*
import org.junit.Test

class EvaluationTimerTest {
    private class Clock {
        var now = 0L
        var evaluations = 0
        data class Pending(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)
        val jobs = mutableListOf<Pending>()
        val timer = EvaluationTimer({ now }, { delay, callback ->
            val job = Pending(now + delay, callback)
            jobs += job
            ({ job.cancelled = true })
        }, { evaluations++ })
    }

    @Test fun `rearming keeps one pending callback and cancels obsolete foreground`() {
        val c = Clock()
        c.timer.arm(1_000)
        c.timer.arm(2_000)
        assertEquals(1, c.jobs.count { !it.cancelled })
        c.now = 1_000
        c.jobs.first().run() // even if cancellation races an already queued callback
        assertEquals(0, c.evaluations)
        c.now = 2_000
        c.jobs.last().run()
        assertEquals(1, c.evaluations)
    }

    @Test fun `no boundary and service stop leave no callback`() {
        val c = Clock()
        c.timer.arm(1_000)
        c.timer.arm(null)
        c.timer.cancel()
        c.now = 2_000
        c.jobs.first().run()
        assertTrue(c.jobs.all { it.cancelled })
        assertEquals(0, c.evaluations)
    }

    @Test fun `early wake after clock rewind rearms remaining time`() {
        val c = Clock()
        c.timer.arm(1_000)
        c.now = -1_000
        c.jobs.first().run()
        assertEquals(1_000L, c.jobs.last().at)
        assertEquals(0, c.evaluations)
        c.now = 1_000
        c.jobs.last().run()
        assertEquals(1, c.evaluations)
    }
}
