package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * R-30: a real shield mount that delivers nothing is published through the production
 * projector, a throwing WindowManager never crashes Main, and only a primary mount clears it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BlockSurfaceMountHealthTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val initial = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private val rule = BlockRule("session-timer-v2-mount", "Focus", BlockTargets(apps = setOf(AppRef(PKG))),
        breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = initial.wallEpochMs + 600_000)
    private val editor = PublishedEditor(EditorCommitmentState(causes = mapOf("rule:${rule.id}" to
        EditorCause(rule.id, AnchoredInterval(initial, 600_000, initial.wallEpochMs + 600_000), "UTC")), timeline = initial))
    private var attach: () -> Boolean = { false }
    private var attaches = 0
    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View): Boolean { attaches++; return attach() }
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }
    private val failed = ProtectionStatus.REASON_BLOCK_SURFACE_MOUNT_FAILED
    private val fallbackOnly = ProtectionStatus.REASON_BLOCK_SURFACE_FALLBACK_ONLY

    @After fun clean() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ProtectionStatusHub.resetForTests()
        BlockSurfaceMountHealth.publisher.resetForTests()
        BlockExemptions.clearAll()
    }

    private fun TestScope.coordinator(fallback: ((Context, String, String?, BlockCause) -> Boolean)? = null): BlockScreenCoordinator {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
        return BlockScreenCoordinator(app, backgroundScope, host, onBlockSurfaceUnavailable = fallback,
            focusStateStore = InMemoryFocusStateStore(), configuredLimitsStore = InMemoryLimitsStore(),
            blockRulesStore = InMemoryBlockRulesStore(listOf(rule)), editorCommitmentStore = editor,
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) }, { initial.elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")),
            statusClock = { initial }, statusDispatcher = StandardTestDispatcher(testScheduler))
    }

    private fun TestScope.settle() {
        runCurrent(); advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS + 1); runCurrent()
    }

    private fun reasons() = ProtectionStatusHub.status.value.detectors.degradedReasons
    private fun cause() = ruleBlockCause(rule, System.currentTimeMillis(), 0L)

    @Test fun PC_61_failedPrimaryAttachWithoutFallbackIsPublishedAndOnlyARealMountClearsIt() = runTest {
        val coordinator = coordinator()
        settle()
        assertEquals(ProtectionStatus.Readiness.READY, ProtectionStatusHub.status.value.readiness)
        assertEquals(rule.id, ProtectionStatusHub.status.value.activeCauses.single().ruleId)
        assertFalse(failed in reasons())

        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertTrue(failed in reasons())
        // Policy ticks re-derive saved causes; they must not erase the observed mount failure.
        advanceTimeBy(ProtectionStatusProjection.TICK_MS * 3); runCurrent()
        assertTrue(failed in reasons())
        assertEquals(rule.id, ProtectionStatusHub.status.value.activeCauses.single().ruleId)

        // Fail-closed: the failure never suppresses the next attempt, which clears it on success.
        attach = { true }
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertEquals(2, attaches)
        assertFalse(failed in reasons())
        assertFalse(fallbackOnly in reasons())
    }

    @Test fun PC_02_throwingAttachFallsBackWithoutCrashingAndIsReportedFallbackOnly() = runTest {
        attach = { throw WindowManager.BadTokenException("token gone") }
        var launches = 0
        val coordinator = coordinator { _, _, _, _ -> launches++; true }
        settle()
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertEquals(1, launches)
        assertTrue(fallbackOnly in reasons())
        assertFalse(failed in reasons())
    }

    @Test fun PC_02_fallbackLaunchFailureIsAMountFailure() = runTest {
        val coordinator = coordinator { _, _, _, _ -> throw SecurityException("background launch refused") }
        settle()
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertTrue(failed in reasons())
        BlockSurfaceMountHealth.publisher.resetForTests()
        val refused = coordinator { _, _, _, _ -> false }
        refused.showBlocked(PKG, null, cause())
        settle()
        assertTrue(failed in reasons())
    }

    @Test fun PC_02_overlayControllerReportsRejectedAddViewOrMissingGrantAsNotShown() {
        val rejecting = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, _ ->
            if (method.name == "addView") throw WindowManager.BadTokenException("rejected") else null
        } as WindowManager
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? = if (name == Context.WINDOW_SERVICE) rejecting else super.getSystemService(name)
        }
        ShadowSettings.setCanDrawOverlays(true)
        val overlay = OverlayController(context)
        assertFalse(overlay.show(View(app)))
        assertFalse(overlay.isShowing())
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(OverlayController(app).show(View(app)))
    }

    private class PublishedEditor(initial: EditorCommitmentState) : EditorCommitmentStore {
        override val state = MutableStateFlow<StorageState<EditorCommitmentState>>(StorageState.Ready(initial))
        override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("read-only test")
        override suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean): EditorCommitResult = error("read-only test")
        override suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("read-only test")
        override suspend fun cancel(id: Long) = error("read-only test")
        override suspend fun dismissMigrationNotice() = error("read-only test")
        override suspend fun reconcile(now: AccessClockStamp) = error("read-only test")
        override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = error("read-only test")
        override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): Boolean = error("read-only test")
    }

    private companion object { const val PKG = "com.example.mount" }
}
