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
        repeat(3) { WebsiteCoverageHealth.addressBarRead(window, false, true, 100) }
        val gap = WebsiteCoverageHealth.snapshot(true, true)
        WebsiteCoverageHealth.foreground(null, true, false)
        assertEquals(gap, WebsiteCoverageHealth.snapshot(true, true))
        WebsiteCoverageHealth.foreground(window, true, true)
        assertEquals(WebCoverage.AwaitingBrowser, WebsiteCoverageHealth.snapshot(true, true))
        WebsiteCoverageHealth.addressBarRead(window, false, true, 200)
        assertEquals(WebCoverage.AwaitingBrowser, WebsiteCoverageHealth.snapshot(true, true))
    }
}
