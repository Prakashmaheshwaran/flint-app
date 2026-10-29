package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
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
        var writes = 0
        var fail = false
        var loseAck = false
        var release: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun <R> transact(transform: (ProtectionSnapshot) -> ProtectionMutation<R>): ProtectionCommit<R> = lock.withLock {
            val state = values.value
            if (state !is StorageState.Ready) return@withLock ProtectionCommit.Unavailable((state as? StorageState.Unavailable)?.reason ?: StorageFailure.IO)
            val before = latest.copy(observedAt = clock(), openCredits = latest.openCredits?.let { OpenCreditLedgerPolicy.reconcile(it, clock()).next })
            val mutation = transform(before)
            val accepting = (mutation.result == GrantResult.OpenAllowed && mutation.openCredits?.counters != before.openCredits?.counters) || (mutation.result as? RecoveryResult.Accepted)?.newlyAccepted == true || (mutation.result as? GrantResult.Granted)?.newlyAccepted == true
            if (accepting) { started.complete(Unit); release?.await(); if (fail) return@withLock ProtectionCommit.Unavailable(StorageFailure.IO) }
            writes++
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

    @Test fun PC_42_restartedProcessFailsClosedUntilTheFirstReadyRecoverySnapshot() = runTest {
        val initial = initial()
        val preview = GlobalRecoveryPolicy.preview(initial, emptyList(), "p1-aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")!!
        val committed = GlobalRecoveryPolicy.consume(initial, preview, emptyList())
        val recovered = initial.copy(focus = committed.focus, rules = committed.rules!!, editor = committed.editor!!)
        val disk = Disk(recovered) { clock() }
        disk.values.value = StorageState.Loading
        coordinator(disk); runCurrent()
        assertFalse(BlockExemptions.hasGlobalRecovery())
        disk.values.value = StorageState.Ready(recovered); runCurrent()
        assertTrue(BlockExemptions.hasGlobalRecovery())
    }

    @Test fun PC_28_inactiveOpenOccurrencePreservesEasyAndHarderBreaksButUnreadableStateDoesNot() = runTest {
        for (tier in listOf(BreakLevel.EASY, BreakLevel.HARDER)) {
            for (expired in listOf(false, true)) {
                val initial = initial(tier)
                val inactive = if (expired) EditorCause("ended-day", AnchoredInterval(base, 0, base.wallEpochMs), "UTC")
                    else EditorCause("inactive", null, "UTC")
                val s = initial.copy(opens = listOf(OpenLimit(pkg, 1, BreakLevel.HARDCORE)),
                    editor = initial.editor.copy(causes = initial.editor.causes + (openCauseKey(pkg) to inactive)))
                val disk = Disk(s) { clock() }; val c = coordinator(disk); runCurrent()
                val cause = c.currentBlockCause(pkg)!!
                assertEquals(tier, cause.breakLevel)
                assertTrue(c.onForegroundChanged(null, pkg) is OpenLimitDecision.Allowed)
                val ui = c.screenState(pkg, null, cause, clock().wallEpochMs)
                assertEquals(com.flint.peakfocus.feature.blockscreen.GrantStatus.READY, ui.grantStatus)
                assertNull(ui.openCreditsRemaining); assertNull(ui.openCreditRequestScope)
                val result = c.requestBreak(pkg, cause, GrantRequestId.issue(clock()))
                if (tier == BreakLevel.EASY) assertTrue(result is GrantResult.Granted) else assertTrue(result is GrantResult.Pending)
                assertEquals(s.openCredits!!.counters, disk.latest.openCredits!!.counters)
                disk.latest = disk.latest.copy(editor = disk.latest.editor.copy(causes = disk.latest.editor.causes - openCauseKey(pkg)))
                disk.values.value = StorageState.Ready(disk.latest); runCurrent()
                assertEquals(com.flint.peakfocus.feature.blockscreen.GrantStatus.UNAVAILABLE,
                    c.screenState(pkg, null, cause, clock().wallEpochMs).grantStatus)
                BlockExemptions.clearAll()
            }
        }
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
        assertEquals(GrantResult.Denied, c.requestBreak("c", cause, GrantRequestId.issue(clock())))
        assertEquals(before, disk.latest.focus.openCounts)
        assertTrue(disk.latest.openCredits!!.counters.isEmpty())
    }

    @Test fun PC_65_idleControllerWakesDoNotRewriteTheStoreOrBumpItsRevision() = runTest {
        val s = initial().copy(rules = emptyList(), times = emptyList(), opens = emptyList(),
            editor = EditorCommitmentState(timeline = base), openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"))
        val disk = Disk(s) { clock() }; var wakes = 0
        GlobalRecoveryController(disk, backgroundScope, disk.clock, { emptyList() }, { wakes++ }, StandardTestDispatcher(testScheduler))
        runCurrent()
        val writes = disk.writes; val revision = disk.latest.focus.revision
        val afterInitialization = wakes
        advanceTimeBy(4 * GlobalRecoveryController.CHECKPOINT_MS); runCurrent()
        assertEquals(writes, disk.writes); assertEquals(revision, disk.latest.focus.revision)
        assertEquals(afterInitialization, wakes)
    }

    @Test fun PC_22_PC_37_PC_65_failedCheckpointsStayQuietButCreditExpiryStillWakesWithUnavailableStorage() = runTest {
        val disk = Disk(initial().copy(openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"))) { clock() }
        var wakes = 0
        val controller = GlobalRecoveryController(disk, backgroundScope, disk.clock, { emptyList() }, { wakes++ }, StandardTestDispatcher(testScheduler))
        runCurrent()
        val input = OrdinaryProtectionInput("c", app.packageName, timeSpent = false, openBlocked = true)
        val id = GrantRequestId.issue(clock(), controller.creditView(input, clock())!!.requestScope)
        assertEquals(GrantResult.OpenAllowed, controller.requestCredit(input, id) { false }); runCurrent()
        val afterCommit = wakes
        disk.values.value = StorageState.Unavailable(StorageFailure.IO); runCurrent()
        assertFalse(controller.reconcile()); runCurrent()
        advanceTimeBy(299_999); runCurrent()
        assertEquals(afterCommit, wakes)
        assertEquals(1L, controller.creditRemaining("c", clock()))
        advanceTimeBy(1); runCurrent()
        assertEquals(afterCommit + 1, wakes)
        assertEquals(0L, controller.creditRemaining("c", clock()))
        assertTrue(disk.latest.openCredits!!.active.containsKey("c")) // failed pruning retains a harmless stale row
        advanceTimeBy(120_000); runCurrent()
        assertEquals(afterCommit + 1, wakes)
        assertEquals(1, disk.latest.openCredits!!.counters.getValue("c").used)
    }

    @Test fun PC_29_PC_31_PC_32_noonRuleAndOpenNomineeNeverChargeThroughOrdinaryOrRepeatedActions() =
        assertOverlapNeverCharges(Schedule(startMinuteOfDay = 9 * 60, endMinuteOfDay = 12 * 60),
            "2026-10-29T10:00:00Z", BlockScreenReason.OPEN_LIMIT)

    @Test fun PC_29_PC_31_PC_32_overnightRuleNomineeAndOpenNeverChargeThroughOrdinaryOrRepeatedActions() =
        assertOverlapNeverCharges(Schedule(startMinuteOfDay = 22 * 60, endMinuteOfDay = 7 * 60),
            "2026-10-29T23:00:00Z", BlockScreenReason.SCHEDULE)

    private fun assertOverlapNeverCharges(schedule: Schedule, wall: String, expectedNominee: BlockScreenReason) = runTest {
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val start = base.copy(wallEpochMs = java.time.Instant.parse(wall).toEpochMilli())
            val credits = OpenCreditLedgerPolicy.initial(start, "UTC")
            val open = OpenLimit(pkg, 2, BreakLevel.EASY)
            val openCause = EditorCause("open-day", AnchoredInterval(start, credits.reset.remainingToResetMs,
                start.wallEpochMs + credits.reset.remainingToResetMs), "UTC")
            val s = initial().copy(rules = emptyList(), times = emptyList(), opens = listOf(open),
                focus = FocusSnapshot(protection = ProtectionLedger(start, start)), openCredits = credits,
                editor = EditorCommitmentState(causes = mapOf(openCauseKey(pkg) to openCause), timeline = start), observedAt = start)
            val disk = Disk(s) { start.copy(wallEpochMs = start.wallEpochMs + testScheduler.currentTime,
                elapsedRealtimeMs = start.elapsedRealtimeMs + testScheduler.currentTime) }
            val c = coordinator(disk); runCurrent()
            val displayedOpen = c.currentBlockCause(pkg)!!
            val explicit = GrantRequestId.issue(disk.clock(), c.screenState(pkg, null, displayedOpen, disk.clock().wallEpochMs).openCreditRequestScope)
            assertNotNull(explicit.creditScope)
            val rule = BlockRule("overlap", "Other schedule", BlockTargets(apps = setOf(AppRef(pkg))), schedule = schedule, breakLevel = BreakLevel.EASY)
            val ruleEnd = ruleBlockCause(rule, start.wallEpochMs, 0).endsAtEpochMs!!
            disk.latest = disk.latest.copy(rules = listOf(rule), editor = disk.latest.editor.copy(causes = disk.latest.editor.causes +
                (ruleCauseKey(rule.id) to EditorCause("schedule-occurrence", AnchoredInterval(start, ruleEnd - start.wallEpochMs, ruleEnd), "UTC"))))
            disk.values.value = StorageState.Ready(disk.latest); runCurrent()
            val nominee = c.currentBlockCause(pkg)!!
            assertEquals(expectedNominee, nominee.reason)
            // Even leftover legacy visit bookkeeping cannot turn the nominee into a debit.
            OpenVisits.process.observe(pkg, countable = true, charge = true, now = start.wallEpochMs)
            val ui = c.screenState(pkg, null, nominee, disk.clock().wallEpochMs)
            assertEquals(listOf("Other schedule"), ui.openCreditBlockingContributors)
            assertNull(ui.openCreditRequestScope)
            val ordinary = GrantRequestId.issue(disk.clock())
            repeat(2) {
                c.onForegroundChanged(null, pkg)
                assertEquals(GrantResult.Denied, c.requestBreak(pkg, nominee, ordinary))
                assertEquals(GrantResult.Denied, c.requestBreak(pkg, nominee, explicit))
                assertFalse(c.canDismiss(pkg, nominee))
            }
            assertTrue(disk.latest.openCredits!!.counters.isEmpty())
            assertTrue(disk.latest.openCredits!!.active.isEmpty())
            assertTrue(disk.latest.focus.protection!!.outbox.isEmpty())
            disk.latest = disk.latest.copy(rules = emptyList(), editor = disk.latest.editor.copy(causes = mapOf(openCauseKey(pkg) to openCause)))
            disk.values.value = StorageState.Ready(disk.latest); runCurrent()
            assertEquals(GrantResult.Denied, c.requestBreak(pkg, displayedOpen, ordinary))
            assertEquals(GrantResult.OpenAllowed, c.requestBreak(pkg, displayedOpen, explicit)); runCurrent()
            val originalGrant = disk.latest.openCredits!!.active.getValue(pkg)
            repeat(2) {
                assertEquals(GrantResult.OpenAllowed, c.requestBreak(pkg, displayedOpen, explicit))
                assertEquals(GrantResult.OpenAllowed, c.requestBreak(pkg, displayedOpen, GrantRequestId.issue(disk.clock(), explicit.creditScope)))
                c.onForegroundChanged(null, pkg)
            }
            assertEquals(1, disk.latest.openCredits!!.counters.getValue(pkg).used)
            assertEquals(originalGrant, disk.latest.openCredits!!.active.getValue(pkg))
            assertEquals(1, disk.latest.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.ACCESS_STARTED })
        } finally { java.util.TimeZone.setDefault(zone) }
    }

    @Test fun PC_29_PC_30_PC_31_explicitCreditAcrossBothPathsCommitsOnceAndExpiryWakesStationaryForeground() = runTest {
        val s = initial().copy(opens = listOf(OpenLimit("c", 1, BreakLevel.HARDCORE)), openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"))
        val disk = Disk(s) { clock() }; var wakes = 0
        val a = coordinator(disk) { wakes++ }; val b = coordinator(disk) { wakes++ }; runCurrent()
        val cause = openLimitBlockCause(s.opens.single(), clock().wallEpochMs, 0)
        val ui = a.screenState("c", "C", cause, clock().wallEpochMs)
        assertEquals(1, ui.openCreditsRemaining); assertNotNull(ui.openCreditRequestScope)
        val first = GrantRequestId.issue(clock(), ui.openCreditRequestScope)
        val second = GrantRequestId.issue(clock(), ui.openCreditRequestScope)
        val results = awaitAll(async { a.requestBreak("c", cause, first) }, async { b.requestBreak("c", cause, second) }); runCurrent()
        assertTrue(results.all { it == GrantResult.OpenAllowed })
        assertEquals(1, disk.latest.openCredits!!.counters.getValue("c").used)
        assertEquals(1, disk.latest.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.ACCESS_STARTED })
        assertNull(a.evaluateBlockCause(null, "c", null, false))
        val deadline = disk.latest.openCredits!!.active.getValue("c").grant
        repeat(5) { a.onForegroundChanged(null, "c"); b.onForegroundChanged("self", "c") }
        assertEquals(deadline, disk.latest.openCredits!!.active.getValue("c").grant)
        val before = wakes; advanceTimeBy(300_000); runCurrent()
        assertTrue(wakes > before)
        assertNotNull(a.evaluateBlockCause("c", "c", null, false))
        assertEquals(1, disk.latest.openCredits!!.counters.getValue("c").used)
        assertEquals(1, disk.latest.focus.protection!!.outbox.values.count { it.kind == ProtectionLifecycleKind.ACCESS_ENDED })
    }

    @Test fun PC_32_PC_37_creditNeverPublishesBeforeAckAndNewIndependentCauseBlocksACommittedCreditRetry() = runTest {
        val disk = Disk(initial().copy(openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"))) { clock() }
        val c = coordinator(disk); runCurrent()
        val cause = openLimitBlockCause(disk.latest.opens.single(), clock().wallEpochMs, 0)
        val id = GrantRequestId.issue(clock(), c.screenState("c", null, cause, clock().wallEpochMs).openCreditRequestScope)
        disk.release = CompletableDeferred(); disk.fail = true
        val request = async { c.requestBreak("c", cause, id) }; disk.started.await()
        assertTrue(c.onForegroundChanged(null, "c") is OpenLimitDecision.Blocked)
        assertTrue(disk.latest.openCredits!!.counters.isEmpty())
        disk.release!!.complete(Unit)
        assertTrue(request.await() is GrantResult.Unavailable)
        disk.fail = false; disk.release = null
        assertTrue(c.retryProtectionState()); runCurrent()
        assertEquals(GrantResult.OpenAllowed, c.requestBreak("c", cause, id)); runCurrent()
        val blocker = session.copy(id = "block-c", name = "New focus", targets = BlockTargets(apps = setOf(AppRef("c"))))
        disk.latest = disk.latest.copy(rules = disk.latest.rules + blocker, editor = disk.latest.editor.copy(causes = disk.latest.editor.causes +
            ("rule:block-c" to EditorCause("block-c", null, "UTC"))))
        disk.values.value = StorageState.Ready(disk.latest); runCurrent()
        assertEquals(GrantResult.Denied, c.requestBreak("c", cause, id))
        assertFalse(c.canDismiss("c", cause))
        assertEquals(1, disk.latest.openCredits!!.counters.getValue("c").used)
    }

    @Test fun PC_32_PC_33_websiteCauseArrivingDuringBrowserCreditBlocksEvenWithoutAReadableAddress() = runTest {
        val browser = "com.android.chrome"
        val s = initial().let { it.copy(opens = listOf(OpenLimit(browser, 2)), openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"),
            editor = it.editor.copy(causes = it.editor.causes + ("open:$browser" to it.editor.causes.getValue("open:c")))) }
        val disk = Disk(s) { clock() }; val c = coordinator(disk); runCurrent()
        val cause = openLimitBlockCause(s.opens.single(), clock().wallEpochMs, 0)
        val id = GrantRequestId.issue(clock(), c.screenState(browser, null, cause, clock().wallEpochMs).openCreditRequestScope)
        assertEquals(GrantResult.OpenAllowed, c.requestBreak(browser, cause, id)); runCurrent()
        val website = BlockRule("web", "Website focus", BlockTargets(domains = setOf(DomainRef("example.com"))), breakLevel = BreakLevel.HARDCORE)
        disk.latest = disk.latest.copy(rules = disk.latest.rules + website, editor = disk.latest.editor.copy(causes = disk.latest.editor.causes +
            ("rule:web" to EditorCause("web", null, "UTC"))))
        disk.values.value = StorageState.Ready(disk.latest); runCurrent()
        assertNotNull(c.evaluateBlockCause(browser, browser, null, false, null))
        assertFalse(c.canDismiss(browser, cause))
        assertEquals(GrantResult.Denied, c.requestBreak(browser, cause, id))
        assertEquals(1, disk.latest.openCredits!!.counters.getValue(browser).used)
        assertNull(c.evaluateBlockCause(browser, browser, null, false, "https://elsewhere.example/"))
    }

    @Test fun PC_54_PC_55_openResetDisplayUsesTheSavedElapsedQuotaClockAfterWallAndDeviceZoneChanges() = runTest {
        var shift = 0L
        val disk = Disk(initial()) { clock().let { it.copy(wallEpochMs = it.wallEpochMs + shift) } }
        val c = coordinator(disk); runCurrent()
        val remaining = disk.latest.openCredits!!.reset.remainingToResetMs
        val zone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"))
            shift = 3 * 86_400_000L + 3_600_000L
            val cause = c.evaluateBlockCause(null, "c", null, false)!!
            assertEquals(disk.clock().wallEpochMs + remaining, cause.endsAtEpochMs)
            assertEquals(remaining, c.screenState("c", null, cause, disk.clock().wallEpochMs).remainingMillis)
            assertEquals(remaining, c.currentBlockCause("c", openLimitBlockCause(disk.latest.opens.single(), disk.clock().wallEpochMs, 0))!!.endsAtEpochMs!! - disk.clock().wallEpochMs)
        } finally { java.util.TimeZone.setDefault(zone) }
    }

    @Test fun PC_28_PC_37_exhaustedQuotaUsesTierBreakWithoutAnotherCreditCharge() = runTest {
        val disk = Disk(initial().copy(opens = listOf(OpenLimit("c", 1, BreakLevel.EASY)), openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"))) { clock() }
        val c = coordinator(disk); runCurrent()
        val cause = openLimitBlockCause(disk.latest.opens.single(), clock().wallEpochMs, 0)
        val id = GrantRequestId.issue(clock(), c.screenState("c", null, cause, clock().wallEpochMs).openCreditRequestScope)
        assertEquals(GrantResult.OpenAllowed, c.requestBreak("c", cause, id))
        advanceTimeBy(300_000); runCurrent()
        assertTrue(c.requestBreak("c", cause, GrantRequestId.issue(clock())) is GrantResult.Granted)
        assertEquals(1, disk.latest.openCredits!!.counters.getValue("c").used)
        assertTrue(disk.latest.openCredits!!.active.isEmpty())
        assertEquals(1, disk.latest.focus.protection!!.ordinaryGrants.size)
    }

    @Test fun PC_31_PC_59_PC_65_creditLostAckRestartsAtSameDeadlineAndRebootKeepsItsSpend() = runTest {
        val disk = Disk(initial().copy(openCredits = OpenCreditLedgerPolicy.initial(base, "UTC"))) { clock() }
        val c = coordinator(disk); runCurrent()
        val cause = openLimitBlockCause(disk.latest.opens.single(), clock().wallEpochMs, 0)
        val id = GrantRequestId.issue(clock(), c.screenState("c", null, cause, clock().wallEpochMs).openCreditRequestScope)
        disk.loseAck = true
        try { c.requestBreak("c", cause, id); fail("lost acknowledgement returned") } catch (_: CancellationException) { }
        disk.loseAck = false
        val restored = coordinator(disk); runCurrent()
        assertEquals(GrantResult.OpenAllowed, restored.requestBreak("c", cause, id))
        assertEquals(1, disk.latest.openCredits!!.counters.getValue("c").used)
        val end = disk.latest.openCredits!!.active.getValue("c").grant
        assertEquals(base.elapsedRealtimeMs + 300_000, end.startedAt.elapsedRealtimeMs + end.durationMs)
        val reboot = AccessClockStamp(base.wallEpochMs + 1_000, 0, "other")
        val credits = OpenCreditLedgerPolicy.reconcile(disk.latest.openCredits!!, reboot).next
        val closed = GlobalRecoveryPolicy.checkpoint(disk.latest.copy(observedAt = reboot, openCredits = credits))
        assertTrue(closed.openCredits!!.active.isEmpty()); assertEquals(1, closed.openCredits!!.counters.getValue("c").used)
        assertTrue(closed.focus.protection!!.previousAccessEnded)
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
