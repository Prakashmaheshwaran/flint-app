package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.blocking.engine.TimeLimitDayAccounting
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Red team for the R-18b follow-ups at the enforcement surface: does any consumer read a Time
 * accounting Unavailable (or a markerless capture) as "this target has no Time Limit" and draw
 * no shield for an exhausted or zero-minute limit?
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimeLimitUnavailableReleaseRedTeamTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val pkg = "com.example.limited"
    private val stamp = AccessClockStamp(1_800_000_000_000L, 5_000, "boot")
    private val limit = TimeLimit(pkg, 30, BreakLevel.HARDER, "UTC")

    @Before fun before() { BlockExemptions.clearAll(); OpenVisits.process.leave() }
    @After fun after() {
        scope.cancel()
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        BlockExemptions.clearAll()
        OpenVisits.process.leave()
    }

    private fun coordinator(limits: LimitsStore) = BlockScreenCoordinator(app, scope,
        object : BlockScreenCoordinator.SurfaceHost {
            override fun attach(view: View) = false
            override fun detach(view: View) = Unit
            override fun goHome() = Unit
        }, nowEpochMs = { stamp.wallEpochMs }, statusClock = { stamp },
        safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet()),
        focusStateStore = InMemoryFocusStateStore(), configuredLimitsStore = limits,
        blockRulesStore = InMemoryBlockRulesStore())

    private fun spentBudget() = TrustedTimeLimitBudget(pkg, 30,
        TimeLimitDayAccounting.initial(stamp, "UTC"), 0L, TrustedTimeLimitBudget.Measurement.OBSERVED)

    /**
     * A Time accounting fault must never be re-read as "no Time Limit": the last known limit and
     * its tier survive the Unavailable emission, and an exhausted budget still draws the shield.
     */
    @Test fun redTeam_TimeAccountingFaultNeverErasesAConfiguredLimitOrItsShield() {
        val states = MutableStateFlow<StorageState<List<TimeLimit>>>(StorageState.Ready(listOf(limit)))
        val limits = object : LimitsStore by InMemoryLimitsStore() {
            override val timeLimitsState: Flow<StorageState<List<TimeLimit>>> = states
        }
        val coordinator = coordinator(limits)
        assertEquals(30, coordinator.timeLimitMinutes(pkg))

        for (fault in listOf(StorageFailure.IO, StorageFailure.CORRUPT)) {
            states.value = StorageState.Unavailable(fault)
            assertEquals(fault.name, 30, coordinator.timeLimitMinutes(pkg))
            assertEquals(fault.name, BreakLevel.HARDCORE, coordinator.currentTimeLimitBreakLevel(pkg))
            val cause = coordinator.evaluateBlockCause(null, pkg, null, false, timeBudget = spentBudget())
            assertEquals(fault.name, BlockScreenReason.TIME_LIMIT, cause?.reason)
            assertEquals(fault.name, BreakLevel.HARDCORE, cause?.breakLevel)
            assertFalse(fault.name, TimeLimitDomainReadiness.permitsNewAccess(pkg,
                StorageState.Ready(listOf(limit)), StorageState.Unavailable(fault), spentBudget(), true, stamp))
        }
        // And it recovers without re-reading: the retained limit is still the same target.
        states.value = StorageState.Ready(listOf(limit))
        assertEquals(30, coordinator.timeLimitMinutes(pkg))
        assertEquals(BreakLevel.HARDER, coordinator.currentTimeLimitBreakLevel(pkg))
    }

    /**
     * A ready Time domain with a zero-minute limit must shield with no sample at all, and an
     * Unavailable emission must not turn that into an allowed open.
     */
    @Test fun redTeam_ZeroMinuteLimitShieldsWithoutAnyMeasurementAcrossAnUnavailableEmission() {
        val zero = TimeLimit(pkg, 0, BreakLevel.HARDCORE, "UTC")
        val states = MutableStateFlow<StorageState<List<TimeLimit>>>(StorageState.Ready(listOf(zero)))
        val limits = object : LimitsStore by InMemoryLimitsStore() {
            override val timeLimitsState: Flow<StorageState<List<TimeLimit>>> = states
        }
        val coordinator = coordinator(limits)
        val budget = TrustedTimeLimitBudget(pkg, 0, null, 0L, TrustedTimeLimitBudget.Measurement.NOT_NEEDED)
        assertEquals(BlockScreenReason.TIME_LIMIT, coordinator.evaluateBlockCause(null, pkg, null, false, timeBudget = budget)?.reason)
        states.value = StorageState.Unavailable(StorageFailure.IO)
        assertEquals(0, coordinator.timeLimitMinutes(pkg))
        assertEquals(BlockScreenReason.TIME_LIMIT, coordinator.evaluateBlockCause(null, pkg, null, false, timeBudget = budget)?.reason)
    }
}
