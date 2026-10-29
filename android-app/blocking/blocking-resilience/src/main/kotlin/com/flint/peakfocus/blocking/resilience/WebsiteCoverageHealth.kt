package com.flint.peakfocus.blocking.resilience

import com.flint.peakfocus.blocking.engine.BrowserWindow
import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.engine.WebCoverageTracker

/** Process-local observation only: no URL, browsing history, persistence or network. */
object WebsiteCoverageHealth {
    private var tracker = WebCoverageTracker()
    private var lastBrowserObservation: WebCoverage? = null
    private var currentBrowser = false

    @Synchronized fun foreground(
        window: BrowserWindow?, domainsActive: Boolean, isHttpHandler: Boolean,
        handlerLabel: String? = null, isBrowserHandler: Boolean = false,
    ) {
        tracker.foreground(window, domainsActive, isHttpHandler, handlerLabel, isBrowserHandler)
        currentBrowser = domainsActive && (isHttpHandler || window?.let { BrowserAddressBars.isBrowser(it.packageName) } == true)
        if (!domainsActive) lastBrowserObservation = null
        else if (isHttpHandler || window?.let { BrowserAddressBars.isBrowser(it.packageName) } == true) {
            lastBrowserObservation = tracker.state
        }
    }

    @Synchronized fun addressBarRead(
        window: BrowserWindow, found: Boolean, mainWindow: Boolean, nowMs: Long,
        addressBarNodePresent: Boolean = true,
    ) {
        tracker.addressBarRead(window, found, mainWindow, nowMs, addressBarNodePresent)
        if (tracker.state is WebCoverage.Available || tracker.state is WebCoverage.Degraded ||
            tracker.state is WebCoverage.Unconfirmed) {
            lastBrowserObservation = tracker.state
        }
    }

    @Synchronized fun isCurrentBrowser(): Boolean = currentBrowser

    @Synchronized fun snapshot(domainsActive: Boolean, accessibilityEnabled: Boolean): WebCoverage = when {
        !domainsActive -> WebCoverage.NotRequired
        !accessibilityEnabled -> WebCoverage.Paused
        else -> lastBrowserObservation ?: WebCoverage.AwaitingBrowser
    }

    @Synchronized fun reset() {
        tracker = WebCoverageTracker()
        lastBrowserObservation = null
        currentBrowser = false
    }
}
