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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Protection contract ADR-012 §10 / PC-51 on the real Path A service: with an active Hardcore
 * commitment that blocks everything but one app, the uninstaller, Settings (app info,
 * Accessibility disable, force-stop) and the permission controller are never shielded — no
 * guard interception and no overlay — while an ordinary app IS shielded by the same rule.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NoGuardInterceptionTest {

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

    @Test fun `uninstall, app info, accessibility and permission screens are never shielded`() {
        window("com.instagram.android", "com.instagram.mainactivity.MainActivity")
        assertEquals("the Hardcore rule is live", "com.instagram.android", visiblePackage())
        for ((pkg, cls) in listOf(
            "com.google.android.packageinstaller" to "com.android.packageinstaller.UninstallerActivity",
            "com.android.packageinstaller" to "com.android.packageinstaller.UninstallerActivity",
            "com.samsung.android.packageinstaller" to "com.android.packageinstaller.UninstallerActivity",
            "com.miui.packageinstaller" to "com.android.packageinstaller.UninstallerActivity",
            "com.android.settings" to "com.android.settings.applications.InstalledAppDetailsTop",
            "com.android.settings" to "com.android.settings.SubSettings",
            "com.google.android.permissioncontroller" to "com.android.permissioncontroller.permission.ui.ManagePermissionsActivity",
        )) {
            window(pkg, cls)
            assertNull("$pkg/$cls must not be shielded", visiblePackage())
        }
    }

    private fun window(pkg: String, cls: String) {
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = pkg
            className = cls
        }
        service.onAccessibilityEvent(event)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The package whose shield is mounted on the accessibility overlay, if any. */
    private fun visiblePackage(): String? {
        val coordinator = FlintAccessibilityService::class.java.getDeclaredField("coordinator")
            .apply { isAccessible = true }.get(service) as BlockScreenCoordinator
        return BlockScreenCoordinator::class.java.getDeclaredField("visiblePackage")
            .apply { isAccessible = true }.get(coordinator) as String?
    }

    private companion object {
        const val SOURCE = "no-guard-test"
    }
}
