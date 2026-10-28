package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule
import java.time.Instant
import java.time.ZoneId

/** A current observation, never a claim that every website or browser is protected. */
sealed interface WebCoverage {
    data object NotRequired : WebCoverage
    data object AwaitingBrowser : WebCoverage
    data object Paused : WebCoverage
    data class Available(val browser: SupportedBrowser) : WebCoverage
    data class Degraded(val browser: SupportedBrowser, val since: Long) : WebCoverage
    data class Unconfirmed(val browser: SupportedBrowser) : WebCoverage
    data class Unsupported(
        val packageName: String,
        val displayName: String = packageName,
        val isBrowser: Boolean = false,
    ) : WebCoverage
}

data class BrowserWindow(val packageName: String, val windowId: Int)

object WebsiteRulePolicy {
    private val engine = BlockDecisionEngine()
    fun hasActiveDomains(rules: List<BlockRule>, nowMs: Long, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        return rules.any {
            it.enabled && !it.isExpired(nowMs) && !it.targets.allowListMode &&
                it.targets.domains.isNotEmpty() && engine.scheduleActive(
                    it.schedule, local.hour * 60 + local.minute, local.dayOfWeek.value,
                )
        }
    }
}

/** Serialized by the detector. Misses count only for its foreground application window. */
class WebCoverageTracker(private val missThreshold: Int = 3) {
    init { require(missThreshold > 0) }
    var state: WebCoverage = WebCoverage.NotRequired
        private set
    private var window: BrowserWindow? = null
    private var misses = 0
    private var unconfirmedReads = 0
    private var everRead = false

    fun foreground(
        current: BrowserWindow?, domainsActive: Boolean, isHttpHandler: Boolean,
        handlerLabel: String? = null, isBrowserHandler: Boolean = false,
    ) {
        if (!domainsActive || current != window) {
            window = current
            misses = 0
            unconfirmedReads = 0
            everRead = false
            state = WebCoverage.AwaitingBrowser
        }
        state = when {
            !domainsActive -> WebCoverage.NotRequired
            current == null -> WebCoverage.AwaitingBrowser
            BrowserAddressBars.isBrowser(current.packageName) ->
                state.takeUnless { it == WebCoverage.NotRequired || it is WebCoverage.Unsupported }
                    ?: WebCoverage.AwaitingBrowser
            isHttpHandler -> WebCoverage.Unsupported(current.packageName, handlerLabel ?: current.packageName, isBrowserHandler)
            else -> WebCoverage.AwaitingBrowser
        }
    }

    fun addressBarRead(
        current: BrowserWindow, found: Boolean, mainWindow: Boolean, nowMs: Long,
        addressBarNodePresent: Boolean = true,
    ) {
        if (current != window || state == WebCoverage.NotRequired) return
        val browser = BrowserAddressBars.find(current.packageName) ?: return
        if (found) {
            misses = 0
            unconfirmedReads = 0
            everRead = true
            state = WebCoverage.Available(browser)
        } else if (mainWindow && !everRead) {
            if (++unconfirmedReads >= missThreshold) state = WebCoverage.Unconfirmed(browser)
        } else if (mainWindow && addressBarNodePresent) {
            misses++
            if (misses >= missThreshold && state !is WebCoverage.Degraded) {
                state = WebCoverage.Degraded(browser, nowMs)
            }
        } else if (mainWindow) {
            // An absent bar could be History/Downloads/tab switcher or a renamed ID. Do not
            // manufacture degraded protection from that ambiguity; surface it informationally.
            misses = 0
        }
    }
}
