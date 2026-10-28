package com.flint.peakfocus.blocking.resilience

import com.flint.peakfocus.blocking.engine.BrowserWindow
import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.engine.WebCoverageTracker

/** Process-local observation only: no URL, browsing history, persistence or network. */
object WebsiteCoverageHealth {
    private var tracker = WebCoverageTracker()
    private var lastBrowserObservation: WebCoverage? = null

    @Synchronized fun foreground(window: BrowserWindow?, domainsActive: Boolean, isHttpHandler: Boolean) {
        tracker.foreground(window, domainsActive, isHttpHandler)
        if (!domainsActive) lastBrowserObservation = null
        else if (isHttpHandler || window?.let { BrowserAddressBars.isBrowser(it.packageName) } == true) {
            lastBrowserObservation = tracker.state
        }
    }

    @Synchronized fun addressBarRead(window: BrowserWindow, found: Boolean, mainWindow: Boolean, nowMs: Long) {
        tracker.addressBarRead(window, found, mainWindow, nowMs)
        if (tracker.state is WebCoverage.Available || tracker.state is WebCoverage.Degraded) {
            lastBrowserObservation = tracker.state
        }
    }

    @Synchronized fun snapshot(domainsActive: Boolean, accessibilityEnabled: Boolean): WebCoverage = when {
        !domainsActive -> WebCoverage.NotRequired
        !accessibilityEnabled -> WebCoverage.Paused
        else -> lastBrowserObservation ?: WebCoverage.AwaitingBrowser
    }

    @Synchronized fun reset() {
        tracker = WebCoverageTracker()
        lastBrowserObservation = null
    }
}
