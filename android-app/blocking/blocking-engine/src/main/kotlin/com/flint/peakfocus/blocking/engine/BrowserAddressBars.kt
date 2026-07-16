package com.flint.peakfocus.blocking.engine

/**
 * Which packages are browsers, and which view ids hold their address bar.
 *
 * Website rules are only enforceable where Flint can read a URL, so this table *is* the
 * enforcement boundary: a browser missing from it means "blocked site, opened in that browser,
 * not blocked". It lives in the pure decision core rather than next to the AccessibilityService
 * so the pairing of package → id can be unit-tested; the service just reads nodes.
 *
 * Chromium forks all name the omnibox `<pkg>:id/url_bar`, so they need no per-browser entry —
 * [urlBarViewIds] appends that id for any package in [CHROMIUM_FORKS]. Everything else declares
 * its own. An id that is wrong or that a future release renames costs nothing at runtime: the
 * node lookup finds nothing and the URL reads as null, exactly as it does today for an unknown
 * browser. That is also why guessing wide is safe here and narrow is not.
 */
object BrowserAddressBars {

    /** Blink/Chromium-based, all shipping Chrome's `url_bar` omnibox id. */
    private val CHROMIUM_FORKS = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.brave.browser_nightly",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.canary",
        "com.vivaldi.browser",
        "com.vivaldi.browser.snapshot",
        "com.kiwibrowser.browser",
    )

    /** Browsers whose address bar is named something other than `url_bar`. */
    private val CUSTOM_URL_BAR_IDS: Map<String, List<String>> = mapOf(
        // GeckoView / Mozilla Android Components toolbar.
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view"),
        "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view"),
        "org.mozilla.fenix" to listOf("mozac_browser_toolbar_url_view"),
        "org.mozilla.focus" to listOf("mozac_browser_toolbar_url_view", "display_url"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
        "com.sec.android.app.sbrowser.beta" to listOf("location_bar_edit_text"),
        "com.opera.browser" to listOf("url_field"),
        "com.opera.browser.beta" to listOf("url_field"),
        "com.opera.mini.native" to listOf("url_field"),
        "com.opera.gx" to listOf("url_field"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
    )

    /** True when [pkg] is a browser whose address bar Flint knows how to read. */
    fun isBrowser(pkg: String): Boolean = pkg in CHROMIUM_FORKS || pkg in CUSTOM_URL_BAR_IDS

    /**
     * Fully-qualified view ids to try, in order, for [pkg]'s address bar. Empty for non-browsers.
     * Ids are package-qualified because `findAccessibilityNodeInfosByViewId` matches the whole
     * `pkg:id/name` string, and a fork's resources live under the fork's own package name.
     */
    fun urlBarViewIds(pkg: String): List<String> {
        val custom = CUSTOM_URL_BAR_IDS[pkg].orEmpty().map { "$pkg:id/$it" }
        return if (pkg in CHROMIUM_FORKS) custom + "$pkg:id/url_bar" else custom
    }
}
