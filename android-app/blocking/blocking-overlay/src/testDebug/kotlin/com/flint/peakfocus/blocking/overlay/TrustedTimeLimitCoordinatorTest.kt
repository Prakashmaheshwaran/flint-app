package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrustedTimeLimitCoordinatorTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "com.example.time-limit"
    private val base = AccessClockStamp(Instant.parse("2026-10-28T23:00:00Z").toEpochMilli(), 1_000, "boot")
    private var now = base
    private val day = TimeLimitDayAccounting.initial(base, "UTC").copy(usedMs = 60_000)
    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }
    @After fun clean() { BlockExemptions.clearAll(); OpenVisits.process.leave() }
    private suspend fun TestScope.coordinator() = BlockScreenCoordinator(app, backgroundScope, host,
        nowEpochMs = { now.wallEpochMs }, statusClock = { now },
        safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet()),
        focusStateStore = InMemoryFocusStateStore(),
        configuredLimitsStore = InMemoryLimitsStore().also { it.setTimeLimit(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC")) },
        blockRulesStore = InMemoryBlockRulesStore()).also { runCurrent() }
    private fun budget(quality: TrustedTimeLimitBudget.Measurement, remaining: Long?) =
        TrustedTimeLimitBudget(pkg, 1, day, remaining, quality)

    @Test fun PC_54_PC_56_PC_60_bothDetectorHandoffsRetainExhaustionThroughFailuresAndClockJumpsWithinFiniteBounds() = runTest {
        for (viaPathB in listOf(false, true)) {
            val c = coordinator()
            if (viaPathB) PathBBlockHandoff::class.java.getDeclaredField("coordinator")
                .apply { isAccessible = true }.set(null, c)
            fun handoff(budget: TrustedTimeLimitBudget) = if (viaPathB) {
                PathBBlockHandoff.onForegroundPolled(app, pkg, Verdict.Allow, rules = emptyList(), timeBudget = budget)
                c.currentBlockCause(pkg)
            } else c.evaluateBlockCause(null, pkg, null, false, timeBudget = budget)
            try {
                now = base
                val first = handoff(budget(TrustedTimeLimitBudget.Measurement.OBSERVED, 0))!!
                assertEquals(BlockScreenReason.TIME_LIMIT, first.reason)
                now = AccessClockStamp(base.wallEpochMs + 172_800_000, base.elapsedRealtimeMs + 1_000, "boot")
                val failed = handoff(budget(TrustedTimeLimitBudget.Measurement.QUERY_FAILED, 0))!!
                assertEquals(now.wallEpochMs + 3_599_000, failed.endsAtEpochMs)
                now = now.copy(wallEpochMs = base.wallEpochMs - 86_400_000, elapsedRealtimeMs = base.elapsedRealtimeMs + 2_000)
                val missing = handoff(budget(TrustedTimeLimitBudget.Measurement.ACCESS_MISSING, 0))!!
                assertEquals(now.wallEpochMs + 3_598_000, missing.endsAtEpochMs)
                now = now.copy(elapsedRealtimeMs = base.elapsedRealtimeMs + 3_600_000)
                assertNull(c.currentBlockCause(pkg, missing)) // Storage failure cannot extend finite protection.
            } finally {
                c.hideIfShown()
                if (viaPathB) PathBBlockHandoff::class.java.getDeclaredField("coordinator")
                    .apply { isAccessible = true }.set(null, null)
            }
        }
    }

    @Test fun PC_60_unprovenPositiveBudgetDoesNotInventATimeCauseOrRetainAnOldDisplay() = runTest {
        val c = coordinator()
        val old = c.evaluateBlockCause(null, pkg, null, false,
            timeBudget = budget(TrustedTimeLimitBudget.Measurement.OBSERVED, 0))!!
        assertNull(c.evaluateBlockCause(null, pkg, null, false,
            timeBudget = budget(TrustedTimeLimitBudget.Measurement.QUERY_FAILED, null)))
        assertNull(c.currentBlockCause(pkg, old))
    }
}
