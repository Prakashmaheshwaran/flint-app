package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakDecision
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.EmergencyPassState
import com.flint.peakfocus.core.model.TimeLimit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The coordinator's outbound hooks: [BlockEventHooks] (lane F history) fire once per new block /
 * accepted grant, never per re-render; `onEnforcementChanged` fires on limit/tier snapshot loads
 * and changes so an event-driven detector can re-evaluate a stationary app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoordinatorHooksTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var attachOk = true
    private val enforcementChanges = AtomicInteger()
    private val events = mutableListOf<String>()
    private val listener = object : BlockEventHooks.Listener {
        override fun onOpenIntercepted(ruleId: String?, atEpochMs: Long) { events += "open:$ruleId" }
        override fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) {
            events += "break:$ruleId:${untilEpochMs - atEpochMs}"
        }
        override fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) { events += "pass:$ruleId" }
    }
    private lateinit var coordinator: BlockScreenCoordinator

    private val rule = BlockRule(id = "session-timer-7", name = "t", targets = BlockTargets(), breakLevel = BreakLevel.EASY)

    @Before fun setUp() {
        BlockExemptions.clearAll()
        BlockEventHooks.register(listener)
        coordinator = BlockScreenCoordinator(
            context = app,
            scope = scope,
            surfaceHost = object : BlockScreenCoordinator.SurfaceHost {
                override fun attach(view: View): Boolean = attachOk
                override fun detach(view: View) = Unit
                override fun goHome() = Unit
            },
            onEnforcementChanged = { enforcementChanges.incrementAndGet() },
            safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { 0L }),
        )
    }

    @After fun tearDown() {
        BlockEventHooks.unregister(listener)
        BlockExemptions.clearAll()
        runBlocking {
            FlintPreferences.limits(app).clearTimeLimit(APP_A)
            FlintPreferences.focusState(app).setEmergencyPass(EmergencyPassState())
            FlintPreferences.focusState(app).setBreakSession(BreakSessionState())
        }
        scope.cancel()
    }

    @Test fun `an intercepted open is reported once per new block, not per re-render`() {
        val cause = ruleBlockCause(rule, System.currentTimeMillis(), 0L)
        show(APP_A, cause)
        show(APP_A, cause) // detector re-evaluation / tick refresh of the same surface
        show(APP_A, cause.copy(endsAtEpochMs = cause.endsAtEpochMs))
        assertEquals(listOf("open:session-timer-7"), events)
        show(APP_B, timeLimitBlockCause(BreakLevel.EASY, System.currentTimeMillis(), 0L)) // swap
        coordinator.hideIfShown(); idle()
        show(APP_B, timeLimitBlockCause(BreakLevel.EASY, System.currentTimeMillis(), 0L)) // came back
        assertEquals(listOf("open:session-timer-7", "open:null", "open:null"), events)
    }

    @Test fun `the Path B fallback re-launch on every tick is one interception`() {
        attachOk = false // overlay unavailable → BlockActivity fallback each tick
        val cause = ruleBlockCause(rule, System.currentTimeMillis(), 0L)
        repeat(4) { show(APP_A, cause) }
        assertEquals(listOf("open:session-timer-7"), events)
        coordinator.hideIfShown(); idle() // the user left the app
        show(APP_A, cause)
        assertEquals(2, events.size)
    }

    @Test fun `accepted break and pass grants are reported with the rule id`() {
        val cause = ruleBlockCause(rule, System.currentTimeMillis(), 0L)
        assertTrue(coordinator.requestBreak(APP_A, cause) is BreakDecision.Granted)
        assertTrue(coordinator.requestEmergencyPass(APP_B, cause))
        assertEquals(
            listOf("break:session-timer-7:${BlockScreenCoordinator.DEFAULT_BREAK_DURATION_MS}", "pass:session-timer-7"),
            events,
        )
    }

    @Test fun `a denied Hardcore break reports nothing`() {
        val hardcore = ruleBlockCause(rule.copy(breakLevel = BreakLevel.HARDCORE), System.currentTimeMillis(), 0L)
        assertEquals(BreakDecision.DeniedHardcore, coordinator.requestBreak(APP_A, hardcore))
        assertEquals(emptyList<String>(), events)
    }

    @Test fun `limit snapshot loads and changes notify the detector`() {
        awaitTrue("initial snapshots") { enforcementChanges.get() >= 3 } // tier, open and time limits
        val before = enforcementChanges.get()
        runBlocking { FlintPreferences.limits(app).setTimeLimit(TimeLimit(APP_A, dailyMinutes = 1)) }
        awaitTrue("time limit change") { enforcementChanges.get() > before && coordinator.timeLimitMinutes(APP_A) == 1 }
    }

    private fun show(pkg: String, cause: BlockCause) {
        coordinator.showBlocked(pkg, null, cause)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val APP_A = "com.example.hooks.a"
        const val APP_B = "com.example.hooks.b"
    }
}
