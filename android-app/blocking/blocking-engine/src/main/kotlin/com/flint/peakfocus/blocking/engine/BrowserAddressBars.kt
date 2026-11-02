package com.flint.peakfocus.blocking.engine

enum class BrowserFamily { CHROMIUM, GECKO, SAMSUNG, OPERA, DUCKDUCKGO }

data class SupportedBrowser(
    val packageName: String,
    val urlBarIds: List<String>,
    val family: BrowserFamily,
    /** Evidence, not a compatibility promise. Never invent a tested browser version. */
    val verifiedAt: String,
    val displayName: String,
)

/** Versioned address-bar adapters. Runtime coverage is observed separately by WebCoverageTracker. */
object BrowserAddressBars {
    const val MATRIX_VERSION = 1
    private const val BASELINE = "Source IDs pinned 2026-10-28; browser version/device unverified"

    private fun browser(pkg: String, name: String, family: BrowserFamily, vararg ids: String) =
        SupportedBrowser(pkg, ids.map { "$pkg:id/$it" }, family, BASELINE, name)

    val supportedBrowsers: List<SupportedBrowser> = listOf(
        browser("com.android.chrome", "Chrome", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.chrome.beta", "Chrome Beta", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.chrome.dev", "Chrome Dev", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.chrome.canary", "Chrome Canary", BrowserFamily.CHROMIUM, "url_bar"),
        browser("org.chromium.chrome", "Chromium", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.brave.browser", "Brave", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.brave.browser_beta", "Brave Beta", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.brave.browser_nightly", "Brave Nightly", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.microsoft.emmx", "Edge", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.microsoft.emmx.beta", "Edge Beta", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.microsoft.emmx.canary", "Edge Canary", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.vivaldi.browser", "Vivaldi", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.vivaldi.browser.snapshot", "Vivaldi Snapshot", BrowserFamily.CHROMIUM, "url_bar"),
        browser("com.kiwibrowser.browser", "Kiwi", BrowserFamily.CHROMIUM, "url_bar"),
        browser("org.mozilla.firefox", "Firefox", BrowserFamily.GECKO, "mozac_browser_toolbar_url_view"),
        browser("org.mozilla.firefox_beta", "Firefox Beta", BrowserFamily.GECKO, "mozac_browser_toolbar_url_view"),
        browser("org.mozilla.fenix", "Firefox Nightly", BrowserFamily.GECKO, "mozac_browser_toolbar_url_view"),
        browser("org.mozilla.focus", "Firefox Focus", BrowserFamily.GECKO, "mozac_browser_toolbar_url_view", "display_url"),
        browser("com.sec.android.app.sbrowser", "Samsung Internet", BrowserFamily.SAMSUNG, "location_bar_edit_text"),
        browser("com.sec.android.app.sbrowser.beta", "Samsung Internet Beta", BrowserFamily.SAMSUNG, "location_bar_edit_text"),
        browser("com.opera.browser", "Opera", BrowserFamily.OPERA, "url_field"),
        browser("com.opera.browser.beta", "Opera Beta", BrowserFamily.OPERA, "url_field"),
        browser("com.opera.mini.native", "Opera Mini", BrowserFamily.OPERA, "url_field"),
        browser("com.opera.gx", "Opera GX", BrowserFamily.OPERA, "url_field"),
        browser("com.duckduckgo.mobile.android", "DuckDuckGo", BrowserFamily.DUCKDUCKGO, "omnibarTextInput"),
    )
    private val byPackage = supportedBrowsers.associateBy { it.packageName }

    fun find(pkg: String): SupportedBrowser? = byPackage[pkg]
    fun isBrowser(pkg: String): Boolean = find(pkg) != null
    fun urlBarViewIds(pkg: String): List<String> = find(pkg)?.urlBarIds.orEmpty()
}
