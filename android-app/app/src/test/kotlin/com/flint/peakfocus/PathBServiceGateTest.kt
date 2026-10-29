package com.flint.peakfocus

import android.app.AppOpsManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Process
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.blocking.overlay.ProtectionStatus.PathBState
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PathBServiceGateTest {
    private val context = ObservingContext(RuntimeEnvironment.getApplication())

    @Before fun setUp() = resetHealth()
    @After fun cleanup() = resetHealth()

    @Test fun `permission grant requests a start but never claims a live service`() {
        ProtectionStatusHub.publishPathBHealth(PathBState.OFF)
        grantUsage(true)
        PathBServiceGate.sync(context)
        assertEquals(1, context.starts)
        assertEquals(0, context.stops)
        assertEquals(PathBState.UNKNOWN, health().pathB)
    }

    @Test fun `a positively absent service with missing usage access is off`() {
        grantUsage(false)
        PathBServiceGate.sync(context)
        assertEquals(1, context.stops)
        assertEquals(PathBState.OFF, health().pathB)
        assertTrue("usage-access-missing" in health().degradedReasons)
    }

    @Test fun `an accepted stop of a running service waits for lifecycle confirmation`() {
        grantUsage(false)
        context.stopResult = true
        PathBServiceGate.sync(context)
        assertEquals(PathBState.UNKNOWN, health().pathB)
        assertTrue("usage-access-missing" in health().degradedReasons)
    }

    @Test fun `background start failure is unknown with an observed reason`() {
        grantUsage(true)
        context.startFails = true
        PathBServiceGate.sync(context)
        assertEquals(PathBState.UNKNOWN, health().pathB)
        assertTrue("foreground-start-failed" in health().degradedReasons)
    }

    @Test fun `permission probe failure neither invents a grant nor stops a potentially live service`() {
        context.permissionProbeFails = true
        PathBServiceGate.sync(context)
        assertEquals(0, context.starts)
        assertEquals(0, context.stops)
        assertEquals(PathBState.UNKNOWN, health().pathB)
        assertTrue("usage-access-check-failed" in health().degradedReasons)
    }

    @Test fun `failed stop cannot claim off`() {
        grantUsage(false)
        context.stopFails = true
        PathBServiceGate.sync(context)
        assertEquals(PathBState.UNKNOWN, health().pathB)
        assertTrue("foreground-stop-failed" in health().degradedReasons)
    }

    private fun grantUsage(allowed: Boolean) {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    private fun health() = ProtectionStatusHub.status.value.detectors
    private fun resetHealth() {
        ProtectionStatusHub.publish(ProtectionStatus.UNKNOWN)
        ProtectionStatusHub.publishPathBHealth(PathBState.UNKNOWN)
    }

    private class ObservingContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        var stops = 0
        var stopResult = false
        var startFails = false
        var stopFails = false
        var permissionProbeFails = false

        override fun getSystemService(name: String): Any? {
            if (name == APP_OPS_SERVICE && permissionProbeFails) throw IllegalStateException("AppOps binder unavailable")
            return super.getSystemService(name)
        }
        override fun startForegroundService(service: Intent): ComponentName? {
            starts++
            if (startFails) throw IllegalStateException("FGS start not allowed")
            return service.component
        }
        override fun stopService(service: Intent): Boolean {
            stops++
            if (stopFails) throw IllegalStateException("stop binder unavailable")
            return stopResult
        }
    }
}
