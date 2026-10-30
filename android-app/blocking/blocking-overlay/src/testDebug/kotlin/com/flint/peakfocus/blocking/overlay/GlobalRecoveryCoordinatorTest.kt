package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class GlobalRecoveryCoordinatorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val base = AccessClockStamp(1_800_000_000_000, 10_000, "boot")
    private val pkg = "com.example.a"
    private val session = BlockRule("session-timer-v2-one", "Focus", BlockTargets(apps = setOf(AppRef(pkg))),
        breakLevel = BreakLevel.HARDCORE, expiresAtEpochMs = base.wallEpochMs + 7_200_000)
    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }
    @After fun clean() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        BlockExemptions.clearAll(); OpenVisits.process.leave(); ProtectionStatusHub.resetForTests()
        PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, null)
    }

    private fun initial(tier: BreakLevel = BreakLevel.HARDCORE): ProtectionSnapshot {
        val rule = session.copy(breakLevel = tier)
        val interval = AnchoredInterval(base, 7_200_000, base.wallEpochMs + 7_200_000)
        val editor = EditorCommitmentState(causes = mapOf("rule:${rule.id}" to EditorCause(rule.id, interval, "UTC"),
            "time:b" to EditorCause("day", interval, "UTC"), "open:c" to EditorCause("day", interval, "UTC")), timeline = base)
        return ProtectionSnapshot(FocusSnapshot(protection = ProtectionLedger(base, base), openCounts = OpenCountState(3, mapOf("c" to 9))),
            listOf(rule), listOf(TimeLimit("b", 10, BreakLevel.HARDCORE)), listOf(OpenLimit("c", 2)), editor,
            HarderWaitPolicy.Ledger(), base, OpenCreditLedgerPolicy.initial(base, "UTC", mapOf("c" to 9)))
    }

    private class Disk(initial: ProtectionSnapshot, val clock: () -> AccessClockStamp) : ProtectionTransactionStore {
        var latest = initial
        val values = MutableStateFlow<StorageState<ProtectionSnapshot>>(StorageState.Ready(initial))
        override val state: StateFlow<StorageState<ProtectionSnapshot>> = values
        private val lock = Mutex()
        var fail = false
        var loseAck = false
        var release: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun <R> transact(transform: (ProtectionSnapshot) -> ProtectionMutation<R>): ProtectionCommit<R> = lock.withLock {
            val state = values.value
            if (state !is StorageState.Ready) return@withLock ProtectionCommit.Unavailable((state as? StorageState.Unavailable)?.reason ?: StorageFailure.IO)
            val before = latest.copy(observedAt = clock())
            val mutation = transform(before)
            val accepting = (mutation.result as? RecoveryResult.Accepted)?.newlyAccepted == true || (mutation.result as? GrantResult.Granted)?.newlyAccepted == true
            if (accepting) { started.complete(Unit); release?.await(); if (fail) return@withLock ProtectionCommit.Unavailable(StorageFailure.IO) }
            val focus = mutation.focus.copy(revision = latest.focus.revision + if (mutation.focus != latest.focus || mutation.rules != null || mutation.harder != null) 1 else 0)
            latest = before.copy(focus = focus, rules = mutation.rules ?: before.rules, times = mutation.times ?: before.times,
                opens = mutation.opens ?: before.opens, editor = mutation.editor ?: before.editor, harder = mutation.harder ?: before.harder,
                openCredits = mutation.openCredits ?: before.openCredits)
            values.value = StorageState.Ready(latest)
            if (accepting && loseAck) throw CancellationException("crash after durable acceptance")
            ProtectionCommit.Committed(latest, mutation.result)
        }
    }

    private fun <A, B> Flow<StorageState<A>>.project(transform: (A) -> B): Flow<StorageState<B>> = map<StorageState<A>, StorageState<B>> { state ->
        when (state) {
            is StorageState.Ready -> StorageState.Ready<B>(transform(state.value))
            is StorageState.Unavailable -> StorageState.Unavailable(state.reason)
            StorageState.Loading -> StorageState.Loading
        }
    }

    private fun TestScope.coordinator(disk: Disk, surface: BlockScreenCoordinator.SurfaceHost = host,
        context: Context = app, changed: () -> Unit = {}): BlockScreenCoordinator {
        val focus = object : FocusStateStore by InMemoryFocusStateStore() { override val state = disk.state.project { it.focus } }
        val rules = object : BlockRulesStore by InMemoryBlockRulesStore() { override val rulesState = disk.state.project { it.rules } }
        val limits = object : LimitsStore by InMemoryLimitsStore() {
            override val timeLimitsState = disk.state.project { it.times }
            override val openLimitsState = disk.state.project { it.opens }
        }
        val editor = object : EditorCommitmentStore {
            override val state = disk.state.project { it.editor }
            override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("unused")
            override suspend fun requestEnabled(ruleIds: Set<String>, enabled: Boolean, label: String, now: AccessClockStamp, safety: EditorSafety, requireAll: Boolean): EditorCommitResult = error("unused")
            override suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult = error("unused")
            override suspend fun cancel(id: Long) = Unit
            override suspend fun dismissMigrationNotice() = Unit
            override suspend fun reconcile(now: AccessClockStamp) = Unit
            override suspend fun reconcileRules(now: AccessClockStamp): List<BlockRule> = disk.latest.rules
            override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety): Boolean = error("unused")
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { disk.state.collect { s -> if (s is StorageState.Ready) ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, s.value.rules) } }
        return BlockScreenCoordinator(context, backgroundScope, surface, nowEpochMs = { disk.clock().wallEpochMs },
            onEnforcementChanged = changed, safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { disk.clock().elapsedRealtimeMs }, requiredRuntimeSources = emptySet()),
            focusStateStore = focus, configuredLimitsStore = limits, blockRulesStore = rules, editorCommitmentStore = editor,
            statusClock = disk.clock, statusDispatcher = StandardTestDispatcher(testScheduler), protectionTransactionStore = disk)
    }

    private fun TestScope.clock() = base.copy(wallEpochMs = base.wallEpochMs + testScheduler.currentTime, elapsedRealtimeMs = base.elapsedRealtimeMs + testScheduler.currentTime)

    @Test fun PC_41_PC_43_PC_76_recoveryReviewRemovesShieldButFailedCommitReblocksTarget() = recoveryHandoff(fail = true)
    @Test fun PC_41_PC_44_PC_76_recoveryReviewRemovesShieldAndOnlyDurableCommitReleasesTarget() = recoveryHandoff(fail = false)

    private fun recoveryHandoff(fail: Boolean) = runTest {
        var mounted = false
        val surface = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean { mounted = true; return true }
            override fun detach(view: View) { mounted = false }
            override fun goHome() = Unit
        }
        val disk = Disk(initial()) { clock() }
        val c = coordinator(disk, surface = surface); runCurrent()
        val cause = c.currentBlockCause(pkg)!!
        c.showBlocked(pkg, null, cause)
        assertTrue(mounted)
        invokeRecoveryCallback(c)
        assertTrue("Accepted navigation must keep the shield until recovery draws", mounted)
        c.showBlocked(pkg, null, cause) // detector evaluation between launch and first frame
        assertTrue(mounted)
        val intent = shadowOf(app as android.app.Application).nextStartedActivity
        PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, c)
        val activity = Robolectric.buildActivity(BlockActivity::class.java, intent).create().start().resume().visible()
        activity.get().onTopResumedActivityChanged(true)
        activity.get().onWindowFocusChanged(true)
        compose.waitForIdle()
        activity.get().window.decorView.viewTreeObserver.dispatchOnDraw()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("No shield can cover the recovery review controls", mounted)
        c.showBlocked(pkg, null, cause) // stale detector target after recovery is foreground
        assertFalse("A late evaluation must not remount above recovery", mounted)
        assertFalse(c.canDismiss(pkg, cause))
        val preview = c.previewEmergencyPass()!!
        assertFalse(mounted)
        val before = disk.latest
        disk.release = CompletableDeferred(); disk.fail = fail
        val pending = async { c.confirmEmergencyPass(preview) }; runCurrent(); disk.started.await()
        assertFalse(mounted)
        assertFalse("Navigation/review does not authorize target access", c.canDismiss(pkg, cause))
        assertFalse(BlockExemptions.hasGlobalRecovery())
        disk.release!!.complete(Unit)
        val result = pending.await(); runCurrent()
        assertFalse("Commit publication does not put a shield over recovery", mounted)
        if (fail) {
            assertTrue(result is RecoveryResult.Unavailable)
            assertEquals(before.focus, disk.latest.focus)
            assertEquals(before.rules, disk.latest.rules)
        } else assertTrue(result is RecoveryResult.Accepted)
        // Returning to the target is evaluated again on either detector, without another
        // grant action. A failed commit must restore the shield; success permits access.
        activity.pause()
        val returnedCause = c.evaluateBlockCause(app.packageName, pkg,
            ruleBlockCause(session, clock().wallEpochMs, 0), timeLimitSpent = false)
        returnedCause?.let { c.showBlocked(pkg, null, it) } ?: c.hideIfShown()
        assertEquals(fail, mounted)
        assertEquals(!fail, BlockExemptions.hasGlobalRecovery())
        c.hideIfShown()
        activity.stop().destroy()
    }

    private fun invokeRecoveryCallback(c: BlockScreenCoordinator) {
        val requests = BlockScreenCoordinator::class.java.getDeclaredField("surfaceRequests").apply { isAccessible = true }
            .get(c) as BlockScreenCoordinator.SurfaceRequests
        val callback = BlockScreenCoordinator::class.java.declaredMethods.single { it.name == "handleGrantRequested" }
            .apply { isAccessible = true }
        if (callback.parameterCount == 3) callback.invoke(c, pkg, requests, true)
        else callback.invoke(c, pkg, requests, true, null)
    }

    @Test fun PC_41_PC_76_droppedRecoveryLaunchKeepsShieldAndLateFrameCannotRemoveNewMount() = runTest {
        var mounted = false
        var wakes = 0
        val surface = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean { mounted = true; return true }
            override fun detach(view: View) { mounted = false }
            override fun goHome() = Unit
        }
        val disk = Disk(initial()) { clock() }
        val c = coordinator(disk, surface = surface) { wakes++ }; runCurrent()
        val cause = c.currentBlockCause(pkg)!!
        c.showBlocked(pkg, null, cause)
        invokeRecoveryCallback(c)
        shadowOf(app as android.app.Application).nextStartedActivity
        val before = wakes
        advanceTimeBy(BlockScreenCoordinator.RECOVERY_SURFACE_TIMEOUT_MS); runCurrent()
        assertTrue(mounted)
        assertTrue(wakes > before)
        assertTrue("Undrawn launch retains protection after timeout", mounted)
        invokeRecoveryCallback(c)
        val second = shadowOf(app).nextStartedActivity.getStringExtra(BlockActivity.EXTRA_RECOVERY_HANDOFF)!!
        c.hideIfShown(); c.showBlocked("com.example.new", null, cause)
        RecoverySurfaceHandoff.drawn(second)
        assertTrue("Frame from old target cannot remove new mount", mounted)
        assertFalse(BlockExemptions.hasGlobalRecovery())
        c.hideIfShown()
    }

    @Test fun PC_41_PC_76_slowRecoveryFrameCanAcknowledgeAfterTimeoutWithoutASecondLaunch() = runTest {
        var mounted = false
        var recoveryFocus = false
        val surface = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean { mounted = true; return true }
            override fun detach(view: View) { mounted = false }
            override fun goHome() = Unit
            override fun setRecoveryHandoffFocus(enabled: Boolean) { recoveryFocus = enabled }
        }
        val c = coordinator(Disk(initial()) { clock() }, surface = surface); runCurrent()
        c.showBlocked(pkg, null, c.currentBlockCause(pkg)!!); invokeRecoveryCallback(c)
        val intent = shadowOf(app as android.app.Application).nextStartedActivity
        advanceTimeBy(BlockScreenCoordinator.RECOVERY_SURFACE_TIMEOUT_MS); runCurrent()
        assertTrue(mounted); assertFalse(recoveryFocus)
        PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, c)
        val activity = Robolectric.buildActivity(BlockActivity::class.java, intent).create().start().resume().visible()
        try {
            activity.get().onTopResumedActivityChanged(true)
            compose.waitForIdle()
            activity.get().window.decorView.viewTreeObserver.dispatchOnDraw()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Drawn topmost recovery can acquire focus through the opaque shield", recoveryFocus)
            assertTrue(mounted)
            activity.get().onWindowFocusChanged(true)
            assertFalse("Late first frame completes the same handoff", mounted)
            assertFalse(recoveryFocus)
            assertFalse(BlockExemptions.hasGlobalRecovery())
        } finally { activity.pause().stop().destroy(); c.hideIfShown() }
    }

    @Test fun PC_41_PC_51_PC_76_resumedRecoveryLosingFocusOrTopOrEnteringMultiWindowReblocksBothPaths() = runTest {
        for (pathB in listOf(false, true)) for (loss in listOf("focus", "top", "multi")) {
            var mounted = false
            val surface = object : BlockScreenCoordinator.SurfaceHost {
                override fun attach(view: View): Boolean { mounted = true; return true }
                override fun detach(view: View) { mounted = false }
                override fun goHome() = Unit
            }
            val c = coordinator(Disk(initial()) { clock() }, surface = surface); runCurrent()
            val cause = c.currentBlockCause(pkg)!!
            c.showBlocked(pkg, null, cause); invokeRecoveryCallback(c)
            val intent = shadowOf(app as android.app.Application).nextStartedActivity
            PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, c)
            val activity = Robolectric.buildActivity(BlockActivity::class.java, intent).create().start().resume().visible()
            val unsubscribe = EnforcementSignals.detectors.subscribe {
                if (pathB) PathBBlockHandoff.onForegroundPolled(app, pkg, Verdict.Block(session.id, "test"))
                else c.evaluateBlockCause(pkg, pkg, cause, false)?.let { c.showBlocked(pkg, null, it) }
            }
            try {
                activity.get().onTopResumedActivityChanged(true); activity.get().onWindowFocusChanged(true)
                compose.waitForIdle(); activity.get().window.decorView.viewTreeObserver.dispatchOnDraw(); shadowOf(Looper.getMainLooper()).idle()
                assertFalse(mounted)
                c.showBlocked("com.example.other", null, cause)
                assertTrue("Recovery of X never suppresses another package", mounted)
                c.hideIfShown()
                when (loss) {
                    "focus" -> activity.get().onWindowFocusChanged(false)
                    "top" -> activity.get().onTopResumedActivityChanged(false)
                    else -> activity.get().onMultiWindowModeChanged(true, android.content.res.Configuration())
                }
                assertTrue(activity.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                assertFalse(RecoverySurfaceHandoff.isShowing(pkg))
                assertTrue("$loss must re-shield X on pathB=$pathB while recovery stays resumed", mounted)
                assertFalse(BlockExemptions.hasGlobalRecovery())
            } finally { unsubscribe(); activity.pause().stop().destroy(); c.hideIfShown() }
        }
    }

    @Test fun PC_41_PC_76_recoveryFocusRestoresOnTimeoutRevocationAndMultiWindow() = runTest {
        for (mode in listOf("timeout", "revoked", "focus", "multi")) {
            var mounted = false
            var recoveryFocus = false
            val surface = object : BlockScreenCoordinator.SurfaceHost {
                override fun attach(view: View): Boolean { mounted = true; return true }
                override fun detach(view: View) { mounted = false }
                override fun goHome() = Unit
                override fun setRecoveryHandoffFocus(enabled: Boolean) { recoveryFocus = enabled }
            }
            val c = coordinator(Disk(initial()) { clock() }, surface = surface); runCurrent()
            c.showBlocked(pkg, null, c.currentBlockCause(pkg)!!); invokeRecoveryCallback(c)
            val intent = shadowOf(app as android.app.Application).nextStartedActivity
            PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, c)
            val activity = Robolectric.buildActivity(BlockActivity::class.java, intent).create().start().resume().visible()
            try {
                activity.get().onTopResumedActivityChanged(true)
                if (mode == "multi") activity.get().onMultiWindowModeChanged(true, android.content.res.Configuration())
                compose.waitForIdle(); activity.get().window.decorView.viewTreeObserver.dispatchOnDraw(); shadowOf(Looper.getMainLooper()).idle()
                assertEquals(mode != "multi", recoveryFocus)
                when (mode) {
                    "timeout" -> { advanceTimeBy(BlockScreenCoordinator.RECOVERY_SURFACE_TIMEOUT_MS); runCurrent() }
                    "revoked" -> activity.get().onTopResumedActivityChanged(false)
                    "focus" -> activity.get().onWindowFocusChanged(false)
                    else -> activity.get().onWindowFocusChanged(true)
                }
                assertFalse("$mode restores key focus to the opaque shield", recoveryFocus)
                assertTrue(mounted)
                assertFalse(RecoverySurfaceHandoff.isShowing(pkg))
            } finally { activity.pause().stop().destroy(); c.hideIfShown() }
        }
    }

    @Test fun PC_41_PC_76_rejectedRecoveryLaunchNeverYieldsShieldFocus() = runTest {
        val context = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: android.content.Intent) { throw android.content.ActivityNotFoundException("injected") }
        }
        var mounted = false
        val focusChanges = mutableListOf<Boolean>()
        val surface = object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View): Boolean { mounted = true; return true }
            override fun detach(view: View) { mounted = false }
            override fun goHome() = Unit
            override fun setRecoveryHandoffFocus(enabled: Boolean) { focusChanges += enabled }
        }
        val c = coordinator(Disk(initial()) { clock() }, surface = surface, context = context); runCurrent()
        c.showBlocked(pkg, null, c.currentBlockCause(pkg)!!)
        assertFalse(c.launchRecovery())
        assertTrue(mounted)
        assertTrue(focusChanges.isNotEmpty())
        assertTrue(focusChanges.none { it })
        c.hideIfShown()
    }

    @Test fun PC_41_PC_76_fallbackRecoveryCallbackWaitsForNewIntentAndKeepsProtection() = runTest {
        val disk = Disk(initial()) { clock() }
        val c = coordinator(disk); runCurrent()
        PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, c)
        BlockActivity.launch(app, pkg, null, c.currentBlockCause(pkg)!!)
        val application = app as android.app.Application
        val intent = shadowOf(application).nextStartedActivity
        val activity = Robolectric.buildActivity(BlockActivity::class.java, intent).create()
        try {
            assertFalse(activity.get().isRecoverySurface)
            val requests = BlockScreenCoordinator.SurfaceRequests()
            activity.get().handleRecoveryRequested(c, requests)
            assertTrue(requests.retired)
            assertFalse("Fallback still covers target before the recovery intent arrives", activity.get().isRecoverySurface)
            assertFalse(c.canDismiss(pkg, c.currentBlockCause(pkg)!!))
            activity.newIntent(shadowOf(application).nextStartedActivity)
            assertTrue(activity.get().isRecoverySurface)
            assertFalse(activity.get().isFinishing)
            assertFalse(BlockExemptions.hasGlobalRecovery())
        } finally { activity.destroy() }
    }

    @Test fun PC_41_PC_43_beforeAcknowledgementNoGlobalReleaseOrEventAndFailedWritePreservesEverything() = runTest {
        val disk = Disk(initial()) { clock() }; val c = coordinator(disk); runCurrent()
        val preview = c.previewEmergencyPass()!!
        val before = disk.latest
        disk.release = CompletableDeferred(); disk.fail = true
        val events = mutableListOf<String>()
        val observer = object : BlockEventHooks.Listener {
            override fun onEmergencyPassCommitted(operationId: String, affectedRuleIds: Set<String>, recoveryEndEpochMs: Long, atEpochMs: Long) { events += operationId }
        }
        BlockEventHooks.register(observer)
        try {
            val pending = async { c.confirmEmergencyPass(preview) }; runCurrent(); disk.started.await()
            assertFalse(BlockExemptions.hasGlobalRecovery())
            assertFalse(c.canDismiss(pkg, ruleBlockCause(session, base.wallEpochMs, 0)))
            assertTrue(events.isEmpty())
            disk.release!!.complete(Unit)
            assertTrue(pending.await() is RecoveryResult.Unavailable)
            assertEquals(before.focus, disk.latest.focus)
            assertEquals(before.rules, disk.latest.rules)
            assertTrue(events.isEmpty())
        } finally { BlockEventHooks.unregister(observer) }
    }

    @Test fun PC_41_PC_44_committedGlobalRecoveryEndsTransientAndCoversEveryPackageWithoutForegroundDebits() = runTest {
        val disk = Disk(initial()) { clock() }; val c = coordinator(disk); runCurrent()
        val preview = c.previewEmergencyPass()!!
        assertEquals(3, preview.affected.size)
        assertTrue(c.confirmEmergencyPass(preview) is RecoveryResult.Accepted); runCurrent()
        assertTrue(BlockExemptions.hasGlobalRecovery())
        assertTrue(disk.latest.rules.isEmpty())
        assertEquals(9, disk.latest.focus.openCounts.opensFor("c"))
        assertEquals(9, disk.latest.openCredits!!.counters.getValue("c").used)
        for (target in listOf(pkg, "b", "c", "anything")) {
            assertNull(c.evaluateBlockCause(null, target, ruleBlockCause(session, base.wallEpochMs, 0), true))
            assertTrue(c.onForegroundChanged(null, target) is OpenLimitDecision.Allowed)
        }
        assertEquals(9, disk.latest.focus.openCounts.opensFor("c"))
        assertEquals(1, disk.latest.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.PASS_COMMITTED })
        val restored = coordinator(disk); BlockExemptions.clearAll(); runCurrent()
        assertTrue(restored.isExempt("anything"))
    }

    @Test fun PC_44_PC_45_lostAcknowledgementAndConcurrentPathsKeepOneOperationAndOneOutboxIntent() = runTest {
        val disk = Disk(initial()) { clock() }; val a = coordinator(disk); val b = coordinator(disk); runCurrent()
        val p = a.previewEmergencyPass()!!; val q = b.previewEmergencyPass()!!
        disk.loseAck = true
        try { a.confirmEmergencyPass(p); fail("Lost ack did not cancel") } catch (_: CancellationException) { }
        disk.loseAck = false; runCurrent()
        assertTrue(b.confirmEmergencyPass(p) is RecoveryResult.Accepted)
        assertFalse(b.confirmEmergencyPass(q) is RecoveryResult.Accepted)
        assertEquals(1, disk.latest.focus.protection!!.operations.size)
        assertEquals(setOf("pass:${p.operationId}"), disk.latest.focus.protection!!.outbox.keys)
        assertEquals(p.operationId, disk.latest.focus.protection!!.recovery!!.operationId)
    }

    @Test fun PC_42_PC_59_expiryWakesStationaryDetectorsAndAnEndedTransientNeverReplays() = runTest {
        val disk = Disk(initial()) { clock() }; var changes = 0
        val c = coordinator(disk) { changes++ }; runCurrent()
        val p = c.previewEmergencyPass()!!; c.confirmEmergencyPass(p); runCurrent()
        val afterCommit = changes
        advanceTimeBy(3_600_000); runCurrent()
        assertFalse(BlockExemptions.hasGlobalRecovery())
        assertTrue(changes > afterCommit)
        assertNull(c.currentBlockCause(pkg, ruleBlockCause(session, clock().wallEpochMs, 0)))
        assertNotNull(disk.latest.focus.protection!!.cooldown)
    }

    @Test fun PC_42_newScheduleWindowsStaySuppressedAndOnlyTheStillActiveTemplateResumes() = runTest {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = base.wallEpochMs }
        val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val long = session.copy(id = "future-long", name = "Long", expiresAtEpochMs = null,
            schedule = Schedule(startMinuteOfDay = (minute + 30) % 1_440, endMinuteOfDay = (minute + 120) % 1_440))
        val short = long.copy(id = "future-short", name = "Short", schedule = long.schedule!!.copy(endMinuteOfDay = (minute + 45) % 1_440))
        val inactive = EditorCause("inactive", AnchoredInterval(base, 0, base.wallEpochMs), java.util.TimeZone.getDefault().id)
        val initial = initial().let { it.copy(rules = it.rules + long + short,
            editor = it.editor.copy(causes = it.editor.causes + ("rule:future-long" to inactive) + ("rule:future-short" to inactive))) }
        val disk = Disk(initial) { clock() }; val c = coordinator(disk); runCurrent()
        c.confirmEmergencyPass(c.previewEmergencyPass()!!); runCurrent()
        advanceTimeBy(33 * 60_000L); runCurrent()
        assertNull(c.evaluateBlockCause(pkg, pkg, ruleBlockCause(long, clock().wallEpochMs, 0), false))
        assertNull(c.evaluateBlockCause(pkg, pkg, ruleBlockCause(short, clock().wallEpochMs, 0), false))
        advanceTimeBy(27 * 60_000L); runCurrent()
        assertFalse(BlockExemptions.hasGlobalRecovery())
        assertEquals("future-long", c.currentBlockCause(pkg)!!.ruleId)
        assertEquals(setOf("future-long", "future-short"), disk.latest.rules.map { it.id }.toSet())
        assertEquals(9, disk.latest.openCredits!!.counters.getValue("c").used)
    }

    @Test fun PC_59_PC_60_rebootClosesGlobalAccessKeepsSpendAndReportsTheAdjustedCooldown() = runTest {
        var boot = "boot"
        var wallOffset = 0L
        val disk = Disk(initial()) { clock().copy(bootSessionId = boot, wallEpochMs = clock().wallEpochMs + wallOffset) }
        val c = coordinator(disk); runCurrent(); c.confirmEmergencyPass(c.previewEmergencyPass()!!); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        val saved = disk.latest.focus.protection!!.cooldown!!.remainingAtCheckpointMs
        boot = "other"; wallOffset = 100 * 86_400_000L
        assertTrue(c.retryProtectionState()); runCurrent()
        assertFalse(BlockExemptions.hasGlobalRecovery())
        assertEquals(saved, disk.latest.focus.protection!!.cooldown!!.remainingAtCheckpointMs)
        assertTrue(disk.latest.focus.protection!!.previousAccessEnded)
        assertEquals(base.wallEpochMs, disk.latest.focus.emergencyPass.usedAtEpochMs)
        advanceTimeBy(BlockScreenCoordinator.STATUS_COALESCE_MS + 1); runCurrent()
        val status = ProtectionStatusHub.status.value
        assertTrue(status.pass is ProtectionStatus.PassStatus.CoolingDown)
        assertTrue("access-ended-after-restart" in status.detectors.degradedReasons)
    }

    @Test fun PC_47_unknownStorageAndStaleLegacyCallbackCannotConsumeOrAdvertiseAPass() = runTest {
        val disk = Disk(initial()) { clock() }; val c = coordinator(disk); runCurrent()
        val p = c.previewEmergencyPass()!!
        disk.values.value = StorageState.Unavailable(StorageFailure.CORRUPT); runCurrent()
        assertFalse(c.isEmergencyPassAvailable())
        assertTrue(c.confirmEmergencyPass(p) is RecoveryResult.Unavailable)
        assertTrue(c.requestEmergencyPass(pkg, ruleBlockCause(session, base.wallEpochMs, 0), GrantRequestId.issue(base)) is GrantResult.Unavailable)
        assertNull(disk.latest.focus.emergencyPass.usedAtEpochMs)
        assertFalse(BlockExemptions.hasGlobalRecovery())
    }

    @Test fun PC_28_PC_47_foregroundNeverWritesLegacyQuotaAndAnUnspentCreditCannotBecomeAFreeOrdinaryBreak() = runTest {
        val s = initial().copy(openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"))
        val disk = Disk(s) { clock() }; val c = coordinator(disk); runCurrent()
        val before = disk.latest.focus.openCounts
        assertTrue(c.onForegroundChanged(null, "c") is OpenLimitDecision.Blocked)
        runCurrent()
        assertEquals(before, disk.latest.focus.openCounts)
        assertTrue(disk.latest.openCredits!!.counters.isEmpty())
        val cause = openLimitBlockCause(OpenLimit("c", 2), clock().wallEpochMs, 0)
        assertTrue(c.requestBreak("c", cause, GrantRequestId.issue(clock())) is GrantResult.Unavailable)
        assertEquals(before, disk.latest.focus.openCounts)
        assertTrue(disk.latest.openCredits!!.counters.isEmpty())
    }

    @Test fun PC_22_PC_25_PC_63_ordinaryGrantHasRealScopeAndNewHardcoreRuleBlocksAndProducesMatchingEnd() = runTest {
        val disk = Disk(initial(BreakLevel.EASY)) { clock() }; val c = coordinator(disk); runCurrent()
        val cause = ruleBlockCause(session.copy(breakLevel = BreakLevel.EASY), base.wallEpochMs, 0)
        val id = GrantRequestId.issue(clock())
        assertTrue(c.requestBreak(pkg, cause, id) is GrantResult.Granted); runCurrent()
        assertTrue(c.isExempt(pkg, cause))
        val start = disk.latest.focus.protection!!.outbox.values.single { it.kind == ProtectionLifecycleKind.BREAK_STARTED }
        val stronger = session.copy(id = "other", name = "Other", breakLevel = BreakLevel.HARDCORE)
        disk.latest = disk.latest.copy(rules = disk.latest.rules + stronger, editor = disk.latest.editor.copy(causes = disk.latest.editor.causes +
            ("rule:other" to EditorCause("other", AnchoredInterval(clock(), 7_200_000, clock().wallEpochMs + 7_200_000), "UTC"))))
        disk.values.value = StorageState.Ready(disk.latest); runCurrent()
        assertFalse(c.isExempt(pkg, ruleBlockCause(stronger, clock().wallEpochMs, 0))); runCurrent()
        val end = disk.latest.focus.protection!!.outbox.values.single { it.kind == ProtectionLifecycleKind.BREAK_ENDED }
        assertEquals(start.grantId, end.grantId)
        assertEquals(start.affectedRuleIds, end.affectedRuleIds)
        assertEquals(ProtectionBreakEndReason.SUPERSEDED, end.breakEndReason)
    }
}
