package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Red team R-30: adversarial re-verification of "a shield mount that delivered nothing must
 * publish health and must not be reported as a working surface".
 *
 * These are independent fixtures (not PR #249's tests) for the ordering, fallback, rebind,
 * retry, safety-floor and process-restart variants. Every expected weakening is asserted
 * directly; tests that pass are evidence that variant is closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RedTeamR30CoordinatorMountTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val owner = Any()
    private val initial = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private val rule = BlockRule("session-timer-v2-rt30", "Focus", BlockTargets(apps = setOf(AppRef(PKG))),
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
        EnforcementSignals.detectors.disconnected(owner)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ProtectionStatusHub.resetForTests()
        BlockSurfaceMountHealth.publisher.resetForTests()
        BlockExemptions.clearAll()
    }

    private fun TestScope.coordinator(
        surface: BlockScreenCoordinator.SurfaceHost = host,
        fallback: ((Context, String, String?, BlockCause) -> Boolean)? = null,
    ): BlockScreenCoordinator {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))
        return BlockScreenCoordinator(app, backgroundScope, surface, onBlockSurfaceUnavailable = fallback,
            focusStateStore = InMemoryFocusStateStore(), configuredLimitsStore = InMemoryLimitsStore(),
            blockRulesStore = InMemoryBlockRulesStore(listOf(rule)), editorCommitmentStore = editor,
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) },
                { initial.elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")),
            statusClock = { initial }, statusDispatcher = StandardTestDispatcher(testScheduler))
    }

    private fun TestScope.settle() {
        runCurrent(); advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS + 1); runCurrent()
    }

    private fun TestScope.tick() {
        advanceTimeBy(ProtectionStatusProjection.TICK_MS + BlockScreenCoordinator.STATUS_COALESCE_MS + 1)
        runCurrent()
    }

    private fun reasons() = ProtectionStatusHub.status.value.detectors.degradedReasons
    private fun cause() = ruleBlockCause(rule, System.currentTimeMillis(), 0L)

    /**
     * PC-01/PC-02/PC-61/PC-65: the original reproduction. Path A bound, storage ready, safety
     * resolved, finite Timer active, real `addView` failure. Health must carry the failure, and
     * neither a policy tick nor service rebind/health reports may erase it.
     */
    @Test fun RedTeam_R30_failedPrimaryMountSurvivesPolicyTicksRebindAndPathBHealthReports() = runTest {
        val coordinator = coordinator()
        EnforcementSignals.detectors.connected(owner)
        settle()
        assertEquals(ProtectionStatus.Readiness.READY, ProtectionStatusHub.status.value.readiness)
        assertFalse(failed in reasons())

        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertTrue("the failed mount must be published", failed in reasons())
        assertFalse(fallbackOnly in reasons())
        // The policy still re-derives the saved Timer cause: only the reason protects the claim.
        assertEquals(rule.id, ProtectionStatusHub.status.value.activeCauses.single().ruleId)

        repeat(3) { tick() }
        assertTrue("a policy tick erased the observed mount failure", failed in reasons())

        // Detector reconnect/rebind (PC-77) republishes binding liveness only.
        EnforcementSignals.detectors.disconnected(owner)
        settle()
        assertTrue("unbind erased the observed mount failure", failed in reasons())
        assertTrue(ProtectionStatusHub.status.value.detectors.degradedReasons.contains("path-a-disconnected"))
        EnforcementSignals.detectors.connected(owner)
        settle()
        assertTrue("rebind erased the observed mount failure", failed in reasons())
        assertTrue(ProtectionStatusHub.status.value.detectors.accessibilityConnected)

        // Path B's service owns its own reason set; it must union, never replace.
        ProtectionStatusHub.publishPathBHealth(ProtectionStatus.PathBState.STANDBY,
            setOf("usage-access-missing"), initial.elapsedRealtimeMs + 60_000)
        assertTrue("a Path B health report erased the observed mount failure", failed in reasons())
    }

    /**
     * PC-02: every `addView` rejection shape must be an unavailable surface, not a crash and not
     * a silent success. A throwing host must also keep being retried (fail-closed).
     */
    @Test fun RedTeam_R30_everyRejectedAttachShapeIsPublishedFailedAndRetried() = runTest {
        val shapes = listOf<() -> Boolean>(
            { throw WindowManager.BadTokenException("token gone") },
            { throw WindowManager.InvalidDisplayException("display gone") },
            { throw SecurityException("permission revoked between canShow and addView") },
            { throw IllegalStateException("The specified child already has a parent") },
            { throw IllegalArgumentException("view has a parent") },
            { throw RuntimeException("window manager died") },
            { false },
        )
        val coordinator = coordinator()
        settle()
        var expected = 0
        shapes.forEachIndexed { index, shape ->
            attach = shape
            // Forget the previous observation so each shape must produce its own failure.
            BlockSurfaceMountHealth.publisher.resetForTests()
            coordinator.showBlocked(PKG, null, cause())
            settle()
            expected++
            assertEquals("shape $index recorded the wrong outcome",
                BlockSurfaceMountPublisher.Outcome.FAILED, BlockSurfaceMountHealth.publisher.latest)
            assertTrue("shape $index did not publish a failed mount", failed in reasons())
            assertEquals("shape $index was not retried", expected, attaches)
        }
    }

    /** The fallback request is not delivery proof: it can only ever worsen the reported state. */
    @Test fun RedTeam_R30_failingFallbackTightensToFailedAndKeepsRetrying() = runTest {
        var launches = 0
        var result = true
        val coordinator = coordinator { _, _, _, _ -> launches++; result }
        settle()
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertEquals(1, launches)
        assertTrue(fallbackOnly in reasons())
        assertFalse(failed in reasons())

        result = false
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertEquals(2, launches)
        assertTrue("a refused fallback launch must be a failed mount", failed in reasons())
        assertFalse(fallbackOnly in reasons())

        result = true
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertEquals(3, launches)
        assertTrue(fallbackOnly in reasons())
        assertFalse(failed in reasons())
    }

    /**
     * Documented scope of the merged change: the record is one process-wide "latest *new* mount"
     * outcome, so a success on any other surface/coordinator clears it. This is only a hole if a
     * per-target mount failure can persist while another target mounts, which no production host
     * can express (one accessibility window / one SYSTEM_ALERT_WINDOW singleton per process).
     */
    @Test fun RedTeam_R30_documentedScope_anUnrelatedSuccessfulMountClearsTheLatestOutcome() = runTest {
        val coordinator = coordinator()
        settle()
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertTrue(failed in reasons())

        attach = { true }
        coordinator.showBlocked(OTHER_PKG, null, cause())
        settle()
        assertEquals(2, attaches)
        assertFalse("the latest-outcome model clears on any later primary mount", failed in reasons())
        assertFalse(fallbackOnly in reasons())
        // A Timer for PKG would read Armed again from here; see the owner note in the report.
        assertEquals(ProtectionStatus.Readiness.READY, ProtectionStatusHub.status.value.readiness)
    }

    /** Path B's successful overlay attach clears a Path A failure through the shared publisher. */
    @Test fun RedTeam_R30_documentedScope_aSecondPathMountClearsTheFirstPathsFailure() = runTest {
        val pathAFailure = coordinator()
        settle()
        pathAFailure.showBlocked(PKG, null, cause())
        settle()
        assertTrue(failed in reasons())

        val pathBSuccess = coordinator(surface = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = true
            override fun detach(view: View) = Unit
            override fun goHome() = Unit
        })
        pathBSuccess.showBlocked(PKG, null, cause())
        settle()
        assertFalse("one path's success clears the other path's failure", failed in reasons())
    }

    /** §7: the floor is a terminal allow — never mounted, so never a mount failure either. */
    @Test fun RedTeam_R30_safetyFloorTargetIsNeverMountedAndNeverReportsAFailure() = runTest {
        val coordinator = coordinator()
        settle()
        val floorPackage = "com.android.settings"
        assertTrue(coordinator.isSafetyFloor(floorPackage))
        coordinator.showBlocked(floorPackage, null, cause())
        settle()
        assertEquals("a floor surface must never be mounted", 0, attaches)
        assertFalse(failed in reasons())
        assertFalse(fallbackOnly in reasons())
    }

    /**
     * Process restart is memory-only by design. Proven here: the restarted process reports no
     * observation (never a fake healthy mount claim), and the *next* failing mount is recorded.
     */
    @Test fun RedTeam_R30_processRestartKeepsNoStaleFailureAndTheNextFailedMountIsRecorded() = runTest {
        val coordinator = coordinator()
        settle()
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertTrue(failed in reasons())

        ProtectionStatusHub.resetForTests() // process death: hub + mount record restart at CLEAR
        tick(); tick(); tick()
        assertEquals(ProtectionStatus.Readiness.READY, ProtectionStatusHub.status.value.readiness)
        assertFalse("a restart has no observation, not a healthy one", failed in reasons())

        attach = { false }
        coordinator.showBlocked(PKG, null, cause())
        settle()
        assertTrue("the next real failing mount must be recorded", failed in reasons())
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

    private companion object {
        const val PKG = "com.example.rt30.mount"
        const val OTHER_PKG = "com.example.rt30.other"
    }
}
