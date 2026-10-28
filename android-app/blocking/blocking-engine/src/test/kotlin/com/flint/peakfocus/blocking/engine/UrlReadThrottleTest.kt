package com.flint.peakfocus.blocking.engine

import org.junit.Assert.*
import org.junit.Test

class UrlReadThrottleTest {
    private var now = 0L
    private data class Pending(val at: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    private val pending = mutableListOf<Pending>()
    private val reads = mutableListOf<Triple<String, Long, Boolean>>()
    private val throttle = UrlReadThrottle<String>(
        nowMs = { now },
        post = { delay, callback ->
            val entry = Pending(now + delay, callback).also(pending::add)
            ({ entry.cancelled = true })
        },
        read = { key, trailing -> reads += Triple(key, now, trailing) },
    )
    private fun advance(to: Long) {
        while (true) {
            val entry = pending.filter { !it.cancelled && it.at <= to }.minByOrNull { it.at } ?: break
            pending.remove(entry)
            now = entry.at
            entry.callback()
        }
        now = to
    }

    @Test fun `leading read is immediate and an isolated event adds no trailing read`() {
        assertTrue(throttle.request("chrome:1"))
        advance(1000)
        assertEquals(listOf(Triple("chrome:1", 0L, false)), reads)
    }

    @Test fun `burst coalesces into one trailing read of the final URL`() {
        var url = "initial"
        val observed = mutableListOf<String>()
        val actual = UrlReadThrottle<String>({ now }, { delay, callback ->
            val entry = Pending(now + delay, callback).also(pending::add)
            ({ entry.cancelled = true })
        }, { _, _ -> observed += url })
        actual.request("chrome:1")
        repeat(499) { now = it + 1L; url = "url-$it"; actual.request("chrome:1") }
        assertEquals(listOf("initial"), observed)
        advance(500)
        assertEquals(listOf("initial", "url-498"), observed)
    }

    @Test fun `continuous events read at most once each 500ms and cannot starve trailing edge`() {
        throttle.request("chrome:1")
        for (time in 1L..1499L) { advance(time); throttle.request("chrome:1") }
        advance(1500)
        assertEquals(listOf(0L, 500L, 1000L, 1500L), reads.map { it.second })
    }

    @Test fun `window change and cancellation reject even already queued callbacks`() {
        throttle.request("chrome:1")
        now = 10; throttle.request("chrome:1")
        val stale = pending.single().callback
        now = 20; throttle.request("chrome:2")
        stale()
        assertEquals(listOf("chrome:1", "chrome:2"), reads.map { it.first })
        now = 30; throttle.request("chrome:2")
        val cancelled = pending.last().callback
        throttle.cancel()
        cancelled()
        advance(1000)
        assertEquals(2, reads.size)
    }

    @Test fun `event exactly at boundary cancels queued trailing callback without duplicate read`() {
        throttle.request("chrome:1")
        now = 1; assertFalse(throttle.request("chrome:1"))
        val queued = pending.single().callback
        now = 500; throttle.request("chrome:1"); queued()
        assertEquals(listOf(0L, 500L), reads.map { it.second })
    }
}
