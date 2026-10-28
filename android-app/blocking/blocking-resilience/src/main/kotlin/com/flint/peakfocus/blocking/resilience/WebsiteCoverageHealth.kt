package com.flint.peakfocus.blocking.resilience

import com.flint.peakfocus.blocking.engine.BrowserWindow
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.engine.WebCoverageTracker

/** Process-local observation only: no URL, browsing history, persistence or network. */
object WebsiteCoverageHealth {
    private var tracker = WebCoverageTracker()

    @Synchronized fun foreground(window: BrowserWindow?, domainsActive: Boolean, isHttpHandler: Boolean) {
        tracker.foreground(window, domainsActive, isHttpHandler)
    }

    @Synchronized fun addressBarRead(window: BrowserWindow, found: Boolean, mainWindow: Boolean, nowMs: Long) {
        tracker.addressBarRead(window, found, mainWindow, nowMs)
    }

    @Synchronized fun snapshot(domainsActive: Boolean, accessibilityEnabled: Boolean): WebCoverage = when {
        !domainsActive -> WebCoverage.NotRequired
        !accessibilityEnabled -> WebCoverage.Paused
        tracker.state == WebCoverage.NotRequired -> WebCoverage.AwaitingBrowser
        else -> tracker.state
    }

    @Synchronized fun reset() { tracker = WebCoverageTracker() }
}
