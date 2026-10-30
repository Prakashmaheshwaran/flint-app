package com.flint.peakfocus.blocking.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AsyncEvaluationCancellationTest {
    @Test fun `a self foreground recheck cancels suspended usage before it can show a stale shield`() {
        val service = Robolectric.buildService(FlintAccessibilityService::class.java).create().get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val usage = CompletableDeferred<Long>()
        var shown = false
        // An injected suspended usage read exercises the real service's cancellation branch.
        // No real binder read, timer sleep, mounted Compose view or looper drain is needed.
        val pending = scope.launch { usage.await(); shown = true }
        try {
            assertTrue(pending.isActive)
            FlintAccessibilityService::class.java.getDeclaredField("evaluationJob")
                .apply { isAccessible = true }.set(service, pending)
            FlintAccessibilityService::class.java.getDeclaredField("lastForegroundPackage")
                .apply { isAccessible = true }.set(service, service.packageName)
            FlintAccessibilityService::class.java.getDeclaredMethod("reevaluateForeground")
                .apply { isAccessible = true }.invoke(service)
            assertTrue(pending.isCancelled)
            usage.complete(60_000L)
            assertFalse("late usage completion must never show over Flint", shown)
        } finally {
            service.onDestroy()
            scope.cancel()
        }
    }
}
