package com.flint.peakfocus.blocking.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browser table is the enforcement boundary for website rules, so the invariant worth
 * pinning is that no package can be *declared* a browser while exposing no id to read — that
 * combination reads as "site allowed" at runtime and looks like a working block in the UI.
 */
class BrowserAddressBarsTest {

    /** Every browser Flint claims to support, from both halves of the table. */
    private val browsers = listOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.chromium.chrome", "com.brave.browser", "com.microsoft.emmx",
        "com.vivaldi.browser", "com.kiwibrowser.browser",
        "org.mozilla.firefox", "org.mozilla.fenix", "org.mozilla.focus",
        "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native",
        "com.duckduckgo.mobile.android",
    )

    @Test
    fun `every declared browser yields at least one url bar id`() {
        for (pkg in browsers) {
            assertTrue("$pkg is not recognized as a browser", BrowserAddressBars.isBrowser(pkg))
            assertTrue(
                "$pkg is a browser with no url bar id — its sites would never block",
                BrowserAddressBars.urlBarViewIds(pkg).isNotEmpty(),
            )
        }
    }

    @Test
    fun `isBrowser and urlBarViewIds agree for every package`() {
        val probes = browsers + listOf("com.instagram.android", "", "com.android.chrome.x")
        for (pkg in probes) {
            assertEquals(
                "isBrowser($pkg) disagrees with urlBarViewIds($pkg)",
                BrowserAddressBars.isBrowser(pkg),
                BrowserAddressBars.urlBarViewIds(pkg).isNotEmpty(),
            )
        }
    }

    @Test
    fun `ids are qualified with the browser's own package`() {
        for (pkg in browsers) {
            for (id in BrowserAddressBars.urlBarViewIds(pkg)) {
                assertTrue("$id must start with '$pkg:id/'", id.startsWith("$pkg:id/"))
            }
        }
    }

    @Test
    fun `chromium forks get chrome's omnibox id`() {
        // Edge is Chromium: it was previously declared a browser with no id at all.
        for (pkg in listOf("com.android.chrome", "com.brave.browser", "com.microsoft.emmx")) {
            assertEquals(listOf("$pkg:id/url_bar"), BrowserAddressBars.urlBarViewIds(pkg))
        }
    }

    @Test
    fun `non-chromium browsers use their own toolbar ids`() {
        assertEquals(
            listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"),
            BrowserAddressBars.urlBarViewIds("org.mozilla.firefox"),
        )
        assertEquals(
            listOf("com.sec.android.app.sbrowser:id/location_bar_edit_text"),
            BrowserAddressBars.urlBarViewIds("com.sec.android.app.sbrowser"),
        )
        // Opera was likewise declared a browser with no id.
        assertEquals(
            listOf("com.opera.browser:id/url_field"),
            BrowserAddressBars.urlBarViewIds("com.opera.browser"),
        )
        assertEquals(
            listOf("com.duckduckgo.mobile.android:id/omnibarTextInput"),
            BrowserAddressBars.urlBarViewIds("com.duckduckgo.mobile.android"),
        )
    }

    @Test
    fun `non-browsers are not probed for urls`() {
        assertFalse(BrowserAddressBars.isBrowser("com.instagram.android"))
        assertFalse(BrowserAddressBars.isBrowser(""))
        assertTrue(BrowserAddressBars.urlBarViewIds("com.instagram.android").isEmpty())
    }

    @Test
    fun `the browsers shipped before this table are all still covered`() {
        // Regression guard: the AccessibilityService's old BROWSER_PACKAGES set.
        val legacy = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
            "com.opera.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
        )
        for (pkg in legacy) assertTrue("$pkg lost browser coverage", BrowserAddressBars.isBrowser(pkg))
    }
}
