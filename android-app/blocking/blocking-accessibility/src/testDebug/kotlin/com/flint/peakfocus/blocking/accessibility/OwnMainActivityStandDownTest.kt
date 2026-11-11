package com.flint.peakfocus.blocking.accessibility

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.overlay.BlockExemptions
import com.flint.peakfocus.blocking.overlay.BlockScreenCoordinator
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.FlintIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * ADR-008 re-key: the installed package (`app.flint.focus`) differs from the Kotlin namespace
 * that owns MainActivity (`com.flint.peakfocus`). Opening Flint must still stand the shield
 * down, and that must not depend on deriving the class from `packageName`. Robolectric's
 * package for this library is its namespace, which also differs from the class's package.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OwnMainActivityStandDownTest {

    private lateinit var service: FlintAccessibilityService

    @Before fun setUp() {
        BlockExemptions.clearAll()
        ActiveRulesHolder.publish(
            SOURCE,
            listOf(
                BlockRule(
                    id = "hardcore-brick",
                    name = "Hardcore brick phone",
                    targets = BlockTargets(apps = setOf(AppRef("com.spotify.music")), allowListMode = true),
                    breakLevel = BreakLevel.HARDCORE,
                ),
            ),
        )
        service = Robolectric.buildService(FlintAccessibilityService::class.java).create().get()
        FlintAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }
            .invoke(service)
    }

    @After fun tearDown() {
        ActiveRulesHolder.publish(SOURCE, emptyList())
        BlockExemptions.clearAll()
        service.onDestroy()
    }

    @Test fun `opening Flint's MainActivity stands the shield down whatever the installed package`() {
        assertNotEquals(FlintIdentity.MAIN_ACTIVITY_CLASS, "${service.packageName}.MainActivity")
        window("com.instagram.android", "com.instagram.mainactivity.MainActivity")
        assertEquals("com.instagram.android", visiblePackage())

        // A package-derived class name is not Flint's Activity: it is treated like any other
        // Flint window (an overlay attach) and keeps the underlying target shielded.
        window(service.packageName, "${service.packageName}.MainActivity")
        assertEquals("com.instagram.android", visiblePackage())
        assertEquals("com.instagram.android", field("lastForegroundPackage"))

        window(service.packageName, FlintIdentity.MAIN_ACTIVITY_CLASS)
        assertNull("a real MainActivity visit hides the shield", visiblePackage())
        assertEquals(service.packageName, field("lastForegroundPackage"))
    }

    private fun window(pkg: String, cls: String) {
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = cls
        }
        // As in NoGuardInterceptionTest: never drain the main looper (the mounted block
        // screen's Compose animation keeps posting frames); the service path runs inline.
        check(Looper.myLooper() == Looper.getMainLooper()) { "test must run on the main looper" }
        service.onAccessibilityEvent(event)
    }

    private fun field(name: String): Any? = FlintAccessibilityService::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)

    /** The package whose shield is mounted on the accessibility overlay, if any. */
    private fun visiblePackage(): String? {
        val coordinator = field("coordinator") as BlockScreenCoordinator
        return BlockScreenCoordinator::class.java.getDeclaredField("visiblePackage")
            .apply { isAccessible = true }.get(coordinator) as String?
    }

    private companion object {
        const val SOURCE = "own-main-activity-test"
    }
}
