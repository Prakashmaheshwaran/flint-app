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
    data class Unsupported(val packageName: String) : WebCoverage
}

data class BrowserWindow(val packageName: String, val windowId: Int)

object WebsiteRulePolicy {
    fun hasActiveDomains(rules: List<BlockRule>, nowMs: Long, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        return rules.any {
            it.enabled && !it.isExpired(nowMs) && !it.targets.allowListMode &&
                it.targets.domains.isNotEmpty() && BlockDecisionEngine().scheduleActive(
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

    fun foreground(current: BrowserWindow?, domainsActive: Boolean, isHttpHandler: Boolean) {
        if (!domainsActive || current != window) {
            window = current
            misses = 0
            state = WebCoverage.AwaitingBrowser
        }
        state = when {
            !domainsActive -> WebCoverage.NotRequired
            current == null -> WebCoverage.AwaitingBrowser
            BrowserAddressBars.isBrowser(current.packageName) ->
                state.takeUnless { it == WebCoverage.NotRequired || it is WebCoverage.Unsupported }
                    ?: WebCoverage.AwaitingBrowser
            isHttpHandler -> WebCoverage.Unsupported(current.packageName)
            else -> WebCoverage.AwaitingBrowser
        }
    }

    fun addressBarRead(current: BrowserWindow, found: Boolean, mainWindow: Boolean, nowMs: Long) {
        if (current != window || state == WebCoverage.NotRequired) return
        val browser = BrowserAddressBars.find(current.packageName) ?: return
        if (found) {
            misses = 0
            state = WebCoverage.Available(browser)
        } else if (mainWindow) {
            misses++
            if (misses >= missThreshold && state !is WebCoverage.Degraded) {
                state = WebCoverage.Degraded(browser, nowMs)
            }
        }
    }
}
