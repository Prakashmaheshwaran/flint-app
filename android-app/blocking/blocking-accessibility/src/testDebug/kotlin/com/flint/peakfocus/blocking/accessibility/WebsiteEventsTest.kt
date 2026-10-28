package com.flint.peakfocus.blocking.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.BrowserWindow
import com.flint.peakfocus.blocking.engine.UrlReadThrottle
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.resilience.WebsiteCoverageHealth
import com.flint.peakfocus.core.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

/** Synthetic events, fake monotonic scheduler and fake node response; no Compose looper drain. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [CountingServiceShadow::class, AddressNodeShadow::class])
class WebsiteEventsTest {
    private lateinit var service: FlintAccessibilityService
    private var now = 0L
    private var pending: (() -> Unit)? = null
    private val rule = BlockRule("web", "Web", BlockTargets(domains = setOf(DomainRef("blocked.example"))))

    @Before fun setUp() {
        CountingServiceShadow.root = null
        CountingServiceShadow.roots = 0
        AddressNodeShadow.lookups.clear()
        AddressNodeShadow.urlText = "allowed.example"
        AddressNodeShadow.availableId = null
        ActiveRulesHolder.publish(SOURCE, listOf(rule))
        service = Robolectric.buildService(FlintAccessibilityService::class.java).create().get()
        // Production URL read path with an injected fake clock/scheduler; the coordinator is
        // deliberately absent, so a URL that matches a rule cannot mount an animated overlay.
        val throttle = UrlReadThrottle<BrowserWindow>({ now }, { _, callback ->
            pending = callback
            ({ pending = null })
        }, { window, _ ->
            FlintAccessibilityService::class.java.getDeclaredMethod("readBrowserWindow", BrowserWindow::class.java)
                .apply { isAccessible = true }.invoke(service, window)
        })
        setField("urlThrottle", throttle)
        setField("lastForegroundPackage", CHROME)
        setField("browserWindow", BrowserWindow(CHROME, 7))
        setField("mainBrowserWindow", BrowserWindow(CHROME, 7))
        val root = AccessibilityNodeInfo.obtain().apply { packageName = CHROME }
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(7)
        shadowOf(root).setAccessibilityWindowInfo(window)
        CountingServiceShadow.root = root
    }

    @After fun tearDown() {
        ActiveRulesHolder.publish(SOURCE, emptyList())
        service.onDestroy()
        CountingServiceShadow.root = null
        WebsiteCoverageHealth.reset()
    }

    @Test fun `non-browser content events do no root work even with active websites`() {
        repeat(100) { content("com.instagram.android") }
        assertEquals(0, CountingServiceShadow.roots)
        assertTrue(AddressNodeShadow.lookups.isEmpty())
    }

    @Test fun `browser content events do no work without currently applicable domain rules`() {
        for (inactive in listOf(
            rule.copy(enabled = false),
            rule.copy(expiresAtEpochMs = 1),
            rule.copy(targets = rule.targets.copy(allowListMode = true)),
            rule.copy(targets = BlockTargets()),
            rule.copy(schedule = Schedule(setOf(1, 2, 3, 4, 5, 6, 7), 0, 0)),
        )) {
            ActiveRulesHolder.publish(SOURCE, listOf(inactive))
            content(CHROME)
        }
        assertEquals(0, CountingServiceShadow.roots)
        assertTrue(AddressNodeShadow.lookups.isEmpty())
    }

    @Test fun `same-tab event burst does one lookup then trailing read observes final URL`() {
        content(CHROME)
        assertEquals(1, AddressNodeShadow.lookups.size)
        repeat(499) { now = it + 1L; content(CHROME) }
        assertEquals(1, AddressNodeShadow.lookups.size)
        AddressNodeShadow.urlText = "final.example"
        now = 500
        pending!!.invoke()
        assertEquals(2, AddressNodeShadow.lookups.size)
        assertEquals("final.example", field("lastForegroundUrl"))
    }

    @Test fun `stale and background browser window content is ignored`() {
        content(CHROME, 8)
        content("org.mozilla.firefox", 7)
        assertEquals(0, CountingServiceShadow.roots)
        assertTrue(AddressNodeShadow.lookups.isEmpty())
    }

    @Test fun `missing address bar degrades after three throttled main-window reads and recovers`() {
        AddressNodeShadow.urlText = null
        repeat(3) { now = it * 500L; content(CHROME) }
        assertTrue(WebsiteCoverageHealth.snapshot(true, true) is WebCoverage.Degraded)
        now = 1500
        AddressNodeShadow.urlText = "recovered.example"
        content(CHROME)
        assertTrue(WebsiteCoverageHealth.snapshot(true, true) is WebCoverage.Available)
    }

    @Test fun `overlay root is never probed or counted as a browser miss`() {
        CountingServiceShadow.root!!.packageName = service.packageName
        repeat(5) { now = it * 500L; content(CHROME) }
        assertTrue(AddressNodeShadow.lookups.isEmpty())
        assertEquals(WebCoverage.AwaitingBrowser, WebsiteCoverageHealth.snapshot(true, true))
    }

    @Test fun `window switch cancels queued browser read`() {
        content(CHROME)
        now = 10; content(CHROME)
        val queued = pending!!
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.android.settings"
            className = "com.android.settings.Settings"
        }
        service.onAccessibilityEvent(event)
        now = 500; queued()
        assertEquals(1, AddressNodeShadow.lookups.size)
    }

    @Test fun `unsupported foreground web handler is reported without reading its content`() {
        val pkg = "test.unsupported.browser"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(pkg)
        shadowOf(service.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.MainActivity" }
        })
        setField("lastForegroundPackage", pkg)
        setField("browserWindow", null)
        FlintAccessibilityService::class.java.getDeclaredMethod("refreshWebsiteCoverage", String::class.java)
            .apply { isAccessible = true }.invoke(service, pkg)
        assertEquals(WebCoverage.Unsupported(pkg), WebsiteCoverageHealth.snapshot(true, true))
        assertEquals(0, CountingServiceShadow.roots)
        assertTrue(AddressNodeShadow.lookups.isEmpty())
    }

    @Test fun `alternate address-bar ids rotate across throttle windows with only one lookup per read`() {
        val pkg = "org.mozilla.focus"
        val window = BrowserWindow(pkg, 7)
        setField("lastForegroundPackage", pkg)
        setField("browserWindow", window)
        setField("mainBrowserWindow", window)
        CountingServiceShadow.root!!.packageName = pkg
        AddressNodeShadow.availableId = "$pkg:id/display_url"
        content(pkg)
        assertEquals(listOf("$pkg:id/mozac_browser_toolbar_url_view"), AddressNodeShadow.lookups)
        now = 500; content(pkg)
        now = 1000; content(pkg)
        assertEquals(listOf("$pkg:id/mozac_browser_toolbar_url_view", "$pkg:id/display_url", "$pkg:id/display_url"), AddressNodeShadow.lookups)
        assertTrue(WebsiteCoverageHealth.snapshot(true, true) is WebCoverage.Available)
    }

    @Test fun `only an activity-backed state event establishes a main browser window`() {
        setField("mainBrowserWindow", null)
        val cls = "$CHROME.MainActivity"
        shadowOf(service.packageManager).addOrUpdateActivity(ActivityInfo().apply {
            packageName = CHROME; name = cls
        })
        val activity = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = CHROME; className = cls
        }
        shadowOf(activity).setWindowId(7)
        service.onAccessibilityEvent(activity)
        assertEquals(BrowserWindow(CHROME, 7), field("mainBrowserWindow"))
        val dialog = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = CHROME; className = "android.app.Dialog"
        }
        shadowOf(dialog).setWindowId(8)
        service.onAccessibilityEvent(dialog)
        assertNull(field("mainBrowserWindow"))
    }

    private fun content(pkg: String, windowId: Int = 7) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply { packageName = pkg }
        shadowOf(event).setWindowId(windowId)
        service.onAccessibilityEvent(event)
    }
    private fun field(name: String): Any? = FlintAccessibilityService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)
    private fun setField(name: String, value: Any?) = FlintAccessibilityService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(service, value)

    private companion object {
        const val SOURCE = "website-events-test"
        const val CHROME = "com.android.chrome"
    }
}

@Implements(AccessibilityService::class)
class CountingServiceShadow : ShadowAccessibilityService() {
    @Implementation override fun getRootInActiveWindow(): AccessibilityNodeInfo? {
        roots++
        return root
    }
    companion object {
        var root: AccessibilityNodeInfo? = null
        var roots = 0
    }
}

@Implements(AccessibilityNodeInfo::class)
class AddressNodeShadow : ShadowAccessibilityNodeInfo() {
    @Implementation fun findAccessibilityNodeInfosByViewId(id: String): List<AccessibilityNodeInfo> {
        lookups += id
        if (availableId != null && id != availableId) return emptyList()
        return urlText?.let { listOf(AccessibilityNodeInfo.obtain().apply { this.text = it }) }.orEmpty()
    }
    companion object {
        val lookups = mutableListOf<String>()
        var urlText: String? = null
        var availableId: String? = null
    }
}
