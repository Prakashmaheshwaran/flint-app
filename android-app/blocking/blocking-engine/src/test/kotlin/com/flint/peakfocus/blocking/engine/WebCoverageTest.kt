package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.Schedule
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class WebCoverageTest {
    private val chrome = BrowserWindow("com.android.chrome", 1)
    private val firefox = BrowserWindow("org.mozilla.firefox", 2)
    private val tracker = WebCoverageTracker()
    private fun miss(window: BrowserWindow = chrome, main: Boolean = true, now: Long = 100) =
        tracker.addressBarRead(window, false, main, now)

    @Test fun `three consecutive main window misses degrade and preserve the first degradation time`() {
        tracker.foreground(chrome, true, true)
        repeat(2) { miss() }
        assertEquals(WebCoverage.AwaitingBrowser, tracker.state)
        miss(now = 300)
        assertEquals(WebCoverage.Degraded(BrowserAddressBars.find(chrome.packageName)!!, 300), tracker.state)
        miss(now = 400)
        assertEquals(300L, (tracker.state as WebCoverage.Degraded).since)
    }

    @Test fun `one successful read clears degradation and resets consecutive misses`() {
        tracker.foreground(chrome, true, true)
        repeat(3) { miss() }
        tracker.addressBarRead(chrome, true, true, 500)
        assertTrue(tracker.state is WebCoverage.Available)
        repeat(2) { miss() }
        assertTrue(tracker.state is WebCoverage.Available)
        miss()
        assertTrue(tracker.state is WebCoverage.Degraded)
    }

    @Test fun `dialog and stale window misses never count`() {
        tracker.foreground(chrome, true, true)
        repeat(10) { miss(main = false); miss(firefox) }
        assertEquals(WebCoverage.AwaitingBrowser, tracker.state)
    }

    @Test fun `browser and window switches start a new session`() {
        tracker.foreground(chrome, true, true)
        repeat(2) { miss() }
        tracker.foreground(firefox, true, true)
        miss(firefox)
        assertEquals(WebCoverage.AwaitingBrowser, tracker.state)
        tracker.foreground(chrome.copy(windowId = 9), true, true)
        miss(chrome.copy(windowId = 9))
        assertEquals(WebCoverage.AwaitingBrowser, tracker.state)
    }

    @Test fun `unsupported http handler is named while ordinary apps are not browsers`() {
        val unknown = BrowserWindow("test.browser", 3)
        tracker.foreground(unknown, true, true)
        assertEquals(WebCoverage.Unsupported("test.browser"), tracker.state)
        tracker.foreground(unknown, true, false)
        assertEquals(WebCoverage.AwaitingBrowser, tracker.state)
        tracker.foreground(chrome, true, true)
        assertEquals(WebCoverage.AwaitingBrowser, tracker.state)
    }

    @Test fun `inactive domains clear all coverage observations and misses`() {
        tracker.foreground(chrome, true, true)
        repeat(3) { miss() }
        tracker.foreground(chrome, false, true)
        miss()
        assertEquals(WebCoverage.NotRequired, tracker.state)
        tracker.foreground(chrome, true, true)
        miss()
        assertEquals(WebCoverage.AwaitingBrowser, tracker.state)
    }

    @Test fun `website activity uses enabled expiry schedule and excludes allow-list domains`() {
        val now = Instant.parse("2026-10-27T01:00:00Z").toEpochMilli() // Tuesday; Monday overnight tail
        val rule = BlockRule("web", "Web", BlockTargets(domains = setOf(DomainRef("example.com"))),
            schedule = Schedule(setOf(1), 22 * 60, 6 * 60))
        fun active(r: BlockRule) = WebsiteRulePolicy.hasActiveDomains(listOf(r), now, ZoneId.of("UTC"))
        assertTrue(active(rule))
        assertFalse(active(rule.copy(enabled = false)))
        assertFalse(active(rule.copy(expiresAtEpochMs = now)))
        assertFalse(active(rule.copy(targets = rule.targets.copy(allowListMode = true))))
        assertFalse(active(rule.copy(schedule = Schedule(setOf(2), 22 * 60, 6 * 60))))
        assertFalse(active(rule.copy(targets = BlockTargets())))
    }
}
