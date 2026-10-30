package com.flint.peakfocus.blocking.resilience

import com.flint.peakfocus.blocking.engine.BrowserWindow
import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.engine.WebCoverageTracker

/** Process-local observation only: no URL, browsing history, persistence or network. */
object WebsiteCoverageHealth {
    const val DOMAIN_CONTEXT_FRESHNESS_MS = 120_000L
    data class Observation(val coverage: WebCoverage, val currentBrowser: Boolean,
        val domainContext: WebCoverage? = null, val observedAtWall: Long? = null, val validUntilWall: Long? = null)
    private data class DomainContext(val coverage: WebCoverage, val wallMs: Long, val elapsedMs: Long)
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    private var tracker = WebCoverageTracker()
    private var lastBrowserObservation: WebCoverage? = null
    private var currentBrowser = false
    private var domainContext: DomainContext? = null

    @Synchronized fun foreground(
        window: BrowserWindow?, domainsActive: Boolean, isHttpHandler: Boolean,
        handlerLabel: String? = null, isBrowserHandler: Boolean = false,
    ) {
        tracker.foreground(window, domainsActive, isHttpHandler, handlerLabel, isBrowserHandler)
        currentBrowser = domainsActive && (isHttpHandler || window?.let { BrowserAddressBars.isBrowser(it.packageName) } == true)
        if (!domainsActive) { lastBrowserObservation = null; domainContext = null }
        else if (isHttpHandler || window?.let { BrowserAddressBars.isBrowser(it.packageName) } == true) {
            lastBrowserObservation = tracker.state
            // A different/unconfirmed foreground browser cannot inherit the old adapter proof.
            if (tracker.state !is WebCoverage.Available && tracker.state !is WebCoverage.Degraded &&
                tracker.state !is WebCoverage.Unconfirmed) domainContext = null
        }
    }

    @Synchronized fun addressBarRead(
        window: BrowserWindow, found: Boolean, mainWindow: Boolean, nowMs: Long,
        addressBarNodePresent: Boolean = true,
        observedAtElapsedMs: Long = monotonicMs(),
    ) {
        tracker.addressBarRead(window, found, mainWindow, nowMs, addressBarNodePresent)
        if (tracker.state is WebCoverage.Available || tracker.state is WebCoverage.Degraded ||
            tracker.state is WebCoverage.Unconfirmed) {
            lastBrowserObservation = tracker.state
            // Missing reads must not refresh an old successful Available observation forever.
            if (found || tracker.state is WebCoverage.Degraded || tracker.state is WebCoverage.Unconfirmed) {
                domainContext = DomainContext(tracker.state, nowMs, observedAtElapsedMs)
            }
        }
    }

    @Synchronized fun isCurrentBrowser(): Boolean = currentBrowser

    /** One atomic retained observation for display readers; no new check or timestamp. */
    @Synchronized fun read(domainsActive: Boolean, accessibilityEnabled: Boolean,
        nowWallMs: Long = System.currentTimeMillis(), nowElapsedMs: Long = monotonicMs()): Observation {
        val context = domainContext.takeIf { domainsActive && accessibilityEnabled }
        val fresh = context?.takeIf { nowWallMs >= it.wallMs && nowWallMs - it.wallMs < DOMAIN_CONTEXT_FRESHNESS_MS &&
            nowElapsedMs >= it.elapsedMs && nowElapsedMs - it.elapsedMs < DOMAIN_CONTEXT_FRESHNESS_MS }
        val end = context?.wallMs?.let { if (it > Long.MAX_VALUE - DOMAIN_CONTEXT_FRESHNESS_MS) Long.MAX_VALUE else it + DOMAIN_CONTEXT_FRESHNESS_MS }
        return Observation(snapshot(domainsActive, accessibilityEnabled), currentBrowser, fresh?.coverage, context?.wallMs, end)
    }

    @Synchronized fun snapshot(domainsActive: Boolean, accessibilityEnabled: Boolean): WebCoverage = when {
        !domainsActive -> WebCoverage.NotRequired
        !accessibilityEnabled -> WebCoverage.Paused
        else -> lastBrowserObservation ?: WebCoverage.AwaitingBrowser
    }

    @Synchronized fun reset() {
        tracker = WebCoverageTracker()
        lastBrowserObservation = null
        currentBrowser = false
        domainContext = null
    }
}
