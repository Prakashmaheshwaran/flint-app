package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.core.datastore.InMemoryBlockRulesStore
import com.flint.peakfocus.core.datastore.InMemoryFocusStateStore
import com.flint.peakfocus.core.datastore.InMemoryLimitsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecoveryActivityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun ownCoordinator() {
        // These are Activity lifecycle tests, not persistence tests. Do not let onCreate
        // create the process-lifetime Main coordinator: an editor migration can enqueue a
        // DataStore transform on Robolectric's paused main looper and outlive this test,
        // wedging a later runBlocking cleanup on that same looper. Production never blocks
        // its main looper on such cleanup. Accounting uses the dedicated real-store tests.
        val app = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = PathBBlockHandoff.create(app, scope,
            focusStateStore = InMemoryFocusStateStore(), limitsStore = InMemoryLimitsStore(),
            rulesStore = InMemoryBlockRulesStore())
        singleton(coordinator)
    }

    @After fun releaseCoordinator() {
        scope.cancel()
        singleton(null)
    }

    private fun singleton(value: BlockScreenCoordinator?) = PathBBlockHandoff::class.java
        .getDeclaredField("coordinator").apply { isAccessible = true }.set(null, value)

    @Test @Config(sdk = [24, 34]) fun PC_41_PC_76_multiWindowCallbackRefreshesRecoveryExactlyOnce() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var readiness = 0
        val (id, cancel) = RecoverySurfaceHandoff.register(onReady = { readiness++ }, onDrawn = {})
        BlockActivity.launchRecovery(app, id, "com.example.target")
        val activity = Robolectric.buildActivity(BlockActivity::class.java, shadowOf(app).nextStartedActivity).create()
        try {
            readiness = 0
            if (android.os.Build.VERSION.SDK_INT >= 26)
                activity.get().onMultiWindowModeChanged(true, android.content.res.Configuration())
            else activity.get().onMultiWindowModeChanged(true)
            assertEquals(1, readiness)
        } finally { cancel(); activity.destroy() }
    }

    @Test fun PC_41_PC_76_recoveryMountGuardOnlyCoversItsTargetAndExpiresWithoutRenewal() {
        val activity = Robolectric.buildActivity(BlockActivity::class.java).get()
        try {
            RecoverySurfaceHandoff.showing(activity, "com.example.target")
            assertTrue(RecoverySurfaceHandoff.isShowing("com.example.target"))
            assertFalse(RecoverySurfaceHandoff.isShowing("com.example.other"))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(RecoverySurfaceHandoff.VISIBLE_LEASE_MS))
            assertFalse(RecoverySurfaceHandoff.isShowing("com.example.target"))
            RecoverySurfaceHandoff.showing(activity, "com.example.target")
            RecoverySurfaceHandoff.hidden(activity)
            assertFalse(RecoverySurfaceHandoff.isShowing("com.example.target"))
        } finally { RecoverySurfaceHandoff.hidden(activity) }
    }
    @Test fun PC_41_recoveryLaunchWithNoBlockedTargetSurvivesInitialValidation() {
        val app: Context = ApplicationProvider.getApplicationContext()
        assertTrue(BlockActivity.launchRecovery(app))
        val intent = shadowOf(app as android.app.Application).nextStartedActivity
        assertTrue(intent.getBooleanExtra(BlockActivity.EXTRA_RECOVERY, false))
        val activity = Robolectric.buildActivity(BlockActivity::class.java, intent).create()
        try { assertFalse("Recovery is independently reachable without a blocked target", activity.get().isFinishing) }
        finally { activity.destroy() }
    }

    @Test fun PC_41_PC_76_pathAHandoffPreservesRecoveryOnStartResumeAndDetectorChanges() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val handoff = PathBFallbackActivityHandoff(app)
        val owner = Any()
        val intent = Intent(app, BlockActivity::class.java).putExtra(BlockActivity.EXTRA_RECOVERY, true)
        val activity = Robolectric.buildActivity(BlockActivity::class.java, intent).create()
        try {
            EnforcementSignals.detectors.connected(owner)
            handoff.onActivityStarted(activity.get())
            handoff.onActivityResumed(activity.get())
            assertFalse("Path A must not close the pass review screen", activity.get().isFinishing)
            EnforcementSignals.detectors.disconnected(owner)
            handoff.onActivityStarted(activity.get())
            EnforcementSignals.detectors.connected(owner)
            assertFalse("A later takeover must preserve recovery too", activity.get().isFinishing)
        } finally {
            EnforcementSignals.detectors.disconnected(owner)
            app.unregisterActivityLifecycleCallbacks(handoff)
            activity.destroy()
        }
    }

    @Test fun PC_76_pathAHandoffStillFinishesAnOrdinaryFallbackActivity() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val handoff = PathBFallbackActivityHandoff(app)
        val owner = Any()
        val activity = Robolectric.buildActivity(BlockActivity::class.java).get()
        try {
            EnforcementSignals.detectors.connected(owner)
            handoff.onActivityStarted(activity)
            assertTrue(activity.isFinishing)
        } finally {
            EnforcementSignals.detectors.disconnected(owner)
            app.unregisterActivityLifecycleCallbacks(handoff)
        }
    }
}
