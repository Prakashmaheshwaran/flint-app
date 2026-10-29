package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.model.EnforcementSignals
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecoveryActivityTest {
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
}
