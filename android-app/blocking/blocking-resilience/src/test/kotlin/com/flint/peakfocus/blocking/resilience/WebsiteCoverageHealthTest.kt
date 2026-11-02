package com.flint.peakfocus.blocking.resilience

import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.BrowserWindow
import com.flint.peakfocus.blocking.engine.WebCoverage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WebsiteCoverageHealthTest {
    @After fun reset() = WebsiteCoverageHealth.reset()

    @Test fun `PC-61 Path B pauses active website blocks even with usable app fallback`() {
        val coverage = WebsiteCoverageHealth.snapshot(true, false)
        assertEquals(WebCoverage.Paused, coverage)
        val health = HealthStatus(false, true, true, true, websiteCoverage = coverage)
        assertTrue(health.canEnforce)
        assertEquals(HealthLevel.DEGRADED, health.level)
    }

    @Test fun `inactive and allow-list-only websites need no URL coverage`() {
        assertEquals(WebCoverage.NotRequired, WebsiteCoverageHealth.snapshot(false, false))
        assertEquals(WebCoverage.NotRequired, WebsiteCoverageHealth.snapshot(false, true))
    }

    @Test fun `process-local degradation and recovery are exposed without changing grant facts`() {
        val window = BrowserWindow("com.android.chrome", 1)
        WebsiteCoverageHealth.foreground(window, true, true)
        WebsiteCoverageHealth.addressBarRead(window, true, true, 0)
        repeat(3) { WebsiteCoverageHealth.addressBarRead(window, false, true, 100) }
        val observed = WebsiteCoverageHealth.snapshot(true, true)
        assertTrue(observed is WebCoverage.Degraded)
        assertEquals(HealthLevel.DEGRADED, HealthStatus(true, true, true, true, websiteCoverage = observed).level)
        WebsiteCoverageHealth.addressBarRead(window, true, true, 200)
        assertEquals(WebCoverage.Available(BrowserAddressBars.find(window.packageName)!!), WebsiteCoverageHealth.snapshot(true, true))
        WebsiteCoverageHealth.reset()
        assertEquals(WebCoverage.AwaitingBrowser, WebsiteCoverageHealth.snapshot(true, true))
    }

    @Test fun `unsupported browser degrades while missing both paths stays broken`() {
        val unsupported = WebCoverage.Unsupported("test.browser")
        assertEquals(HealthLevel.DEGRADED, HealthStatus(true, true, true, true, websiteCoverage = unsupported).level)
        assertEquals(HealthLevel.BROKEN, HealthStatus(false, false, false, true, websiteCoverage = WebCoverage.Paused).level)
    }

    @Test fun `opening Flint health preserves the last browser gap while resetting foreground misses`() {
        val window = BrowserWindow("com.android.chrome", 1)
        WebsiteCoverageHealth.foreground(window, true, true)
        WebsiteCoverageHealth.addressBarRead(window, true, true, 0)
        repeat(3) { WebsiteCoverageHealth.addressBarRead(window, false, true, 100) }
        val gap = WebsiteCoverageHealth.snapshot(true, true)
        WebsiteCoverageHealth.foreground(null, true, false)
        assertEquals(gap, WebsiteCoverageHealth.snapshot(true, true))
        assertFalse(WebsiteCoverageHealth.isCurrentBrowser())
        assertEquals(HealthLevel.HEALTHY, HealthStatus(true, true, true, true,
            websiteCoverage = gap, websiteCoverageIsCurrent = WebsiteCoverageHealth.isCurrentBrowser()).level)
        WebsiteCoverageHealth.foreground(window, true, true)
        assertEquals(WebCoverage.AwaitingBrowser, WebsiteCoverageHealth.snapshot(true, true))
        WebsiteCoverageHealth.addressBarRead(window, false, true, 200)
        assertEquals(WebCoverage.AwaitingBrowser, WebsiteCoverageHealth.snapshot(true, true))
    }

    @Test fun `unconfirmed address bar is informational even in the current browser`() {
        val window = BrowserWindow("com.android.chrome", 1)
        WebsiteCoverageHealth.foreground(window, true, true)
        repeat(3) { WebsiteCoverageHealth.addressBarRead(window, false, true, 100, false) }
        val observed = WebsiteCoverageHealth.snapshot(true, true)
        assertTrue(observed is WebCoverage.Unconfirmed)
        assertEquals(HealthLevel.HEALTHY, HealthStatus(true, true, true, true, websiteCoverage = observed).level)
    }

    @Test fun `target reader receives retained coverage and current context atomically without refreshing it`() {
        val window = BrowserWindow("com.android.chrome", 1)
        WebsiteCoverageHealth.foreground(window, true, true)
        WebsiteCoverageHealth.addressBarRead(window, true, true, 100)
        val first = WebsiteCoverageHealth.read(true, true, nowWallMs = 100)
        assertTrue(first.currentBrowser)
        assertTrue(first.coverage is WebCoverage.Available)
        assertTrue(first.domainContext is WebCoverage.Available)
        repeat(20) { assertEquals(first, WebsiteCoverageHealth.read(true, true, nowWallMs = 100)) }
        WebsiteCoverageHealth.foreground(null, true, false)
        val retained = WebsiteCoverageHealth.read(true, true, nowWallMs = 100)
        assertEquals(first.coverage, retained.coverage)
        assertFalse(retained.currentBrowser)
        assertEquals(WebCoverage.Paused, WebsiteCoverageHealth.read(true, false).coverage)
    }

    @Test fun `last supported browser context survives Flint only inside the original freshness bound`() {
        val window = BrowserWindow("com.android.chrome", 1)
        WebsiteCoverageHealth.foreground(window, true, true)
        WebsiteCoverageHealth.addressBarRead(window, true, true, 10_000, observedAtElapsedMs = 1_000)
        WebsiteCoverageHealth.foreground(null, true, false)
        val fresh = WebsiteCoverageHealth.read(true, true, 129_999, 120_999)
        assertFalse(fresh.currentBrowser)
        assertTrue(fresh.domainContext is WebCoverage.Available)
        assertEquals(10_000L, fresh.observedAtWall)
        assertEquals(130_000L, fresh.validUntilWall)
        assertNull(WebsiteCoverageHealth.read(true, true, 130_000, 121_000).domainContext)
        assertNull(WebsiteCoverageHealth.read(true, true, 9_999, 2_000).domainContext)
        assertNull(WebsiteCoverageHealth.read(true, true, 20_000, 999).domainContext)
        assertEquals(10_000L, WebsiteCoverageHealth.read(true, true, 130_000, 121_000).observedAtWall)
    }

    @Test fun `misses unsupported browsers and permission loss never renew a successful domain context`() {
        val window = BrowserWindow("com.android.chrome", 1)
        WebsiteCoverageHealth.foreground(window, true, true)
        WebsiteCoverageHealth.addressBarRead(window, true, true, 10_000, observedAtElapsedMs = 1_000)
        WebsiteCoverageHealth.addressBarRead(window, false, true, 20_000, observedAtElapsedMs = 11_000)
        assertEquals(10_000L, WebsiteCoverageHealth.read(true, true, 20_000, 11_000).observedAtWall)
        assertNull(WebsiteCoverageHealth.read(true, false, 20_000, 11_000).domainContext)
        WebsiteCoverageHealth.foreground(BrowserWindow("unknown.browser", 2), true, true, isBrowserHandler = true)
        assertNull(WebsiteCoverageHealth.read(true, true, 20_000, 11_000).domainContext)
        assertEquals(WebCoverage.NotRequired, WebsiteCoverageHealth.read(false, true).coverage)
    }
}
