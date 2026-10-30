package com.flint.peakfocus.blocking.accessibility

import android.app.AppOpsManager
import android.content.Context
import android.os.Looper
import android.os.Process
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.overlay.BlockExemptions
import com.flint.peakfocus.blocking.overlay.BlockScreenCoordinator
import com.flint.peakfocus.core.data.LimitStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ZeroBudgetInterceptionTest {
    @Test fun `zero budget shields immediately even with Usage Access denied`() {
        val service = Robolectric.buildService(FlintAccessibilityService::class.java).create().get()
        val ops = service.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), service.packageName, AppOpsManager.MODE_IGNORED)
        val target = "com.example.zero.budget"
        val limits = LimitStore(service)
        BlockExemptions.clearAll()
        limits.setLimit(target, 0)
        try {
            assertEquals(AppOpsManager.MODE_IGNORED,
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), service.packageName))
            FlintAccessibilityService::class.java.getDeclaredMethod("onServiceConnected")
                .apply { isAccessible = true }.invoke(service)
            @Suppress("DEPRECATION")
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                packageName = target
                className = "$target.MainActivity"
            }
            check(Looper.myLooper() == Looper.getMainLooper())
            service.onAccessibilityEvent(event)
            // No IO completion or looper drain: a zero budget has a synchronous known verdict.
            val coordinator = FlintAccessibilityService::class.java.getDeclaredField("coordinator")
                .apply { isAccessible = true }.get(service) as BlockScreenCoordinator
            assertEquals(target, BlockScreenCoordinator::class.java.getDeclaredField("visiblePackage")
                .apply { isAccessible = true }.get(coordinator))
        } finally {
            service.onDestroy()
            limits.clear(target)
            BlockExemptions.clearAll()
        }
    }
}
