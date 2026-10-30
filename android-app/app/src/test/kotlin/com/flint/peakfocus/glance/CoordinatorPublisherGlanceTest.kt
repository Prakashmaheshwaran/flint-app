package com.flint.peakfocus.glance

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.view.View
import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.overlay.*
import com.flint.peakfocus.blocking.overlay.ProtectionStatus as C
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Uses C's actual coordinator publication loop, then K's production read/display/delivery path. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoordinatorPublisherGlanceTest {
    private val owner = Any()
    private val app get() = RuntimeEnvironment.getApplication()
    private val epoch = 1_800_000_000_000L
    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }

    @Before fun reset() {
        ProtectionStatusHub.publish(C.UNKNOWN)
        ProtectionStatusHub.publishPathBHealth(C.PathBState.UNKNOWN)
    }

    @After fun clean() {
        EnforcementSignals.detectors.disconnected(owner)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ProtectionStatusHub.publish(C.UNKNOWN)
        ProtectionStatusHub.publishPathBHealth(C.PathBState.UNKNOWN)
        BlockExemptions.clearAll()
    }

    @Test fun realPublisherFeedsExactCauseNextHistoryAndNotificationThenReplacementEndAndFailure() = runTest {
        val start = AccessClockStamp(epoch, SystemClock.elapsedRealtime(), "fixture-boot")
        fun clock() = start.copy(wallEpochMs = epoch + testScheduler.currentTime,
            elapsedRealtimeMs = start.elapsedRealtimeMs + testScheduler.currentTime)
        val timer = BlockRule("session-timer-v2-fixture", " Study\n now ",
            BlockTargets(apps = setOf(AppRef("com.example.fixture"))), breakLevel = BreakLevel.HARDCORE,
            expiresAtEpochMs = epoch + 4_000)
        val future = timer.copy(id = "sleep-future", name = "Tomorrow", expiresAtEpochMs = null,
            schedule = Schedule(startMinuteOfDay = 0, endMinuteOfDay = 60))
        val rules = InMemoryBlockRulesStore(listOf(timer, future))
        val editor = ReadOnlyEditor(EditorCommitmentState(causes = mapOf(
            ruleCauseKey(timer.id) to EditorCause("exact-occurrence-1", AnchoredInterval(start, 4_000, epoch + 4_000), "UTC"),
            ruleCauseKey(future.id) to EditorCause("inactive", null, "UTC")), timeline = start))
        val focusBacking = InMemoryFocusStateStore()
        val focusState = MutableStateFlow<StorageState<FocusSnapshot>>(StorageState.Ready(FocusSnapshot()))
        val focus = object : FocusStateStore by focusBacking { override val state = focusState }
        val history = object : GlanceFocusReader {
            override val changes = flowOf(Unit)
            override suspend fun read() = FocusTodayReading("5m", day = Instant.ofEpochMilli(epoch)
                .atZone(ZoneId.systemDefault()).toLocalDate())
        }
        val reader = CoordinatorGlanceReader()
        suspend fun display() = readGlanceDisplay(reader, history, { clock().elapsedRealtimeMs }, { clock().wallEpochMs })
        assertEquals("Protection state unavailable", display().status)
        assertEquals("5m", display().focusedToday)
        EnforcementSignals.detectors.connected(owner)
        ProtectionStatusHub.publishPathBHealth(C.PathBState.OFF)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(timer, future))
        BlockScreenCoordinator(app, backgroundScope, host, focusStateStore = focus,
            configuredLimitsStore = InMemoryLimitsStore(), blockRulesStore = rules, editorCommitmentStore = editor,
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) },
                { clock().elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")),
            statusClock = ::clock, statusDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val published = ProtectionStatusHub.status.value
        val active = display()
        assertEquals("Protection active at last check", active.status)
        assertEquals("exact-occurrence-1", active.session?.occurrenceId)
        assertEquals(start.elapsedRealtimeMs + 4_000, active.countdownEndElapsedMs)
        assertTrue(active.detail.contains("Study now · Hardcore"))
        assertTrue(active.next.startsWith("Next: Tomorrow"))
        assertEquals("5m", active.focusedToday)
        assertEquals("Emergency Pass unavailable", active.pass)
        assertEquals(published, ProtectionStatusHub.status.value) // K reads cannot republish/check.

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifier = SessionStatusNotifier(app, { clock().elapsedRealtimeMs }, { clock().wallEpochMs })
        notifier.update(active)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.single().notification.timeoutAfter <= 2_000)
        editor.state.value = StorageState.Ready((editor.state.value as StorageState.Ready).value.copy(causes = mapOf(
            ruleCauseKey(timer.id) to EditorCause("exact-occurrence-2", AnchoredInterval(start, 4_000, epoch + 4_000), "UTC"),
            ruleCauseKey(future.id) to EditorCause("inactive", null, "UTC"))))
        runCurrent(); advanceTimeBy(25); runCurrent()
        assertEquals("exact-occurrence-2", display().session?.occurrenceId)
        advanceTimeBy(5_000); runCurrent()
        val ended = display()
        assertEquals("No active protection at last check", ended.status)
        assertNull(ended.countdownEndElapsedMs)
        notifier.update(ended)
        assertTrue(manager.activeNotifications.isEmpty())
        focusState.value = StorageState.Unavailable(StorageFailure.IO)
        runCurrent(); advanceTimeBy(25); runCurrent()
        assertEquals("Protection state unavailable", display().status)
        assertEquals("5m", display().focusedToday)
    }

    @Test fun realPublisherAndHubLeaseNeverTurnAnExpiredBServiceIntoPositivelyOff() = runTest {
        val start = AccessClockStamp(epoch, SystemClock.elapsedRealtime(), "fixture-boot")
        EnforcementSignals.detectors.connected(owner)
        BlockScreenCoordinator(app, backgroundScope, host, focusStateStore = InMemoryFocusStateStore(),
            configuredLimitsStore = InMemoryLimitsStore(), blockRulesStore = InMemoryBlockRulesStore(),
            editorCommitmentStore = ReadOnlyEditor(EditorCommitmentState(timeline = start)),
            safetyFloorGate = SafetyFloorGate(app.packageName, { mapOf("home" to emptySet()) },
                { start.elapsedRealtimeMs }, requiredRuntimeSources = setOf("home")),
            statusClock = { start }, statusDispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val observation = ProtectionStatusHub.status.value.observedAt
        ProtectionStatusHub.publishPathBHealth(C.PathBState.STANDBY, leaseUntilElapsedRealtimeMs = start.elapsedRealtimeMs + 500)
        assertEquals(GlanceDetector.PATH_B_FOREGROUND, CoordinatorGlanceReader().read().detector)
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        val expired = CoordinatorGlanceReader().read()
        assertEquals(GlanceDetector.UNKNOWN, expired.detector)
        assertEquals(ProtectionStatus.DEGRADED, expired.status)
        assertEquals(observation, ProtectionStatusHub.status.value.observedAt)
        val display = GlanceDisplayMapper.map(expired, FocusTodayReading(), SystemClock.elapsedRealtime(), epoch + 500)
        assertEquals("Protection check needed", display.status)
        assertNull(display.session)
        assertNull(display.countdownEndElapsedMs)
    }

    private class ReadOnlyEditor(initial: EditorCommitmentState) : EditorCommitmentStore {
        override val state = MutableStateFlow<StorageState<EditorCommitmentState>>(StorageState.Ready(initial))
        override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("read only")
        override suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean): EditorCommitResult = error("read only")
        override suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("read only")
        override suspend fun cancel(id: Long) = error("read only")
        override suspend fun dismissMigrationNotice() = error("read only")
        override suspend fun reconcile(now: AccessClockStamp) = error("read only")
        override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = error("read only")
        override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): Boolean = error("read only")
    }
}
