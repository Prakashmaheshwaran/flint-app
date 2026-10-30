package com.flint.peakfocus.blocking.accessibility

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.overlay.BlockScreenCoordinator
import com.flint.peakfocus.blocking.overlay.OverlayController
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecoveryOverlayFocusTest {
    @Test fun PC_41_PC_76_bothWindowHostsAcknowledgeOnlySuccessfulFocusUpdates() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        for (pathA in listOf(true, false)) {
            var updates = 0
            var failNext = false
            val wm = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
                when (method.name) {
                    "addView" -> { (args!![0] as View).layoutParams = args[1] as WindowManager.LayoutParams; null }
                    "updateViewLayout" -> {
                        updates++
                        // Android can set local layoutParams before a Binder failure. A matching
                        // local bit after that exception must not masquerade as acknowledgement.
                        (args!![0] as View).layoutParams = args[1] as WindowManager.LayoutParams
                        if (failNext) { failNext = false; throw IllegalStateException("injected window failure") }
                        null
                    }
                    "removeView", "removeViewImmediate" -> null
                    else -> null
                }
            } as WindowManager
            val context = object : ContextWrapper(app) {
                override fun getSystemService(name: String): Any? = if (name == Context.WINDOW_SERVICE) wm else super.getSystemService(name)
            }
            val view = View(context)
            val setFocus: (Boolean) -> Boolean
            val detach: () -> Unit
            if (pathA) {
                val service = FlintAccessibilityService()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, context)
                // Supply the already-created service window manager; platform creation casts
                // to WindowManagerImpl to set its Binder token, which this fault fake lacks.
                android.accessibilityservice.AccessibilityService::class.java.getDeclaredField("mWindowManager")
                    .apply { isAccessible = true }.set(service, wm)
                val type = FlintAccessibilityService::class.java.declaredClasses.single { it.simpleName == "AccessibilityOverlayHost" }
                val host = type.getDeclaredConstructor(FlintAccessibilityService::class.java).apply { isAccessible = true }
                    .newInstance(service) as BlockScreenCoordinator.SurfaceHost
                assertTrue(host.attach(view))
                setFocus = host::setRecoveryHandoffFocus
                detach = { host.detach(view) }
            } else {
                ShadowSettings.setCanDrawOverlays(true)
                val overlay = OverlayController(context)
                overlay.show(view); assertTrue(overlay.isShowing())
                setFocus = overlay::setRecoveryHandoffFocus
                detach = overlay::hide
            }
            try {
                val initial = (view.layoutParams as WindowManager.LayoutParams).flags
                failNext = true
                assertFalse(setFocus(true)); assertEquals(1, updates)
                assertTrue(setFocus(true)); assertEquals("Retry actual update after partial failure", 2, updates)
                val yielded = view.layoutParams as WindowManager.LayoutParams
                assertEquals(initial or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, yielded.flags)
                assertEquals(PixelFormat.OPAQUE, yielded.format)
                failNext = true
                assertFalse(setFocus(false)); assertEquals(3, updates)
                assertTrue(setFocus(false)); assertEquals(4, updates)
                assertEquals(initial, (view.layoutParams as WindowManager.LayoutParams).flags)
                assertTrue(setFocus(false)); assertEquals("Confirmed matching flags need no update", 4, updates)
                // A later OEM relayout can change flags behind the last acknowledged state.
                (view.layoutParams as WindowManager.LayoutParams).flags = initial or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                assertTrue(setFocus(false)); assertEquals(5, updates)
                assertEquals(initial, (view.layoutParams as WindowManager.LayoutParams).flags)
                assertTrue(setFocus(true)); assertEquals(6, updates)
                (view.layoutParams as WindowManager.LayoutParams).flags = initial
                assertTrue(setFocus(true)); assertEquals(7, updates)
                assertEquals(initial or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, (view.layoutParams as WindowManager.LayoutParams).flags)
            } finally { detach(); ShadowSettings.setCanDrawOverlays(false) }
        }
    }
}
