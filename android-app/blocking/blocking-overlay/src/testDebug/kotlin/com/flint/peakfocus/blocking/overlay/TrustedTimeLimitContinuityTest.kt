package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.view.View
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TrustedTimeLimitContinuityTest {
    private class Disk : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "com.example.time-limit"
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private var now = AccessClockStamp(base, 1_000, "boot")
    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }
    private suspend fun TestScope.coordinator() = BlockScreenCoordinator(app, backgroundScope, host,
        nowEpochMs = { now.wallEpochMs }, statusClock = { now },
        safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet()),
        focusStateStore = InMemoryFocusStateStore(),
        configuredLimitsStore = InMemoryLimitsStore().also { it.setTimeLimit(TimeLimit(pkg, 30, BreakLevel.HARDCORE, "UTC")) },
        blockRulesStore = InMemoryBlockRulesStore()).also { runCurrent() }
    private fun handoff(c: BlockScreenCoordinator, pathB: Boolean, budget: TrustedTimeLimitBudget): BlockCause? = if (pathB) {
        PathBBlockHandoff.onForegroundPolled(app, pkg, Verdict.Allow, rules = emptyList(), timeBudget = budget)
        c.currentBlockCause(pkg)
    } else c.evaluateBlockCause(null, pkg, null, false, timeBudget = budget)
    private fun install(c: BlockScreenCoordinator?) {
        PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, c)
    }

    @Test fun PC_54_PC_55_PC_56_PC_60_bothRealHandoffsKeepFiveMinutesAndBlockAfterFiveObservedMinutes() = runTest {
        for (pathB in listOf(false, true)) for (change in listOf("clock", "reboot", "unknown-boot", "query-loss", "travel")) {
            now = AccessClockStamp(base, 1_000, "boot")
            val disk = Disk()
            var zone = "UTC"
            var total: Long? = 25 * 60_000
            var store = DataStoreTimeLimitUsageStore(disk, { now }, { zone })
            fun newReader() = TrustedTimeLimitReader({ name, stamp -> store.measurementContext(name, stamp) },
                { ticket, sample, stamp -> store.record(ticket, sample, stamp) }, { _, _, _ -> total }, { now })
            var reader = newReader()
            var owner = if (pathB) "B" else "A"
            fun observation(previous: String? = "visit") = TimeLimitForegroundObservation(owner, previous, "visit", pkg, now)
            val c = coordinator()
            if (pathB) install(c)
            try {
                assertEquals(300_000L, reader.read(pkg, 30).remainingMs)
                store.observeForeground(observation(null))
                when (change) {
                    "clock" -> {
                        now = now.copy(wallEpochMs = base - 23 * 3_600_000)
                        total = 0
                        assertEquals(300_000L, reader.read(pkg, 30).remainingMs)
                        now = now.copy(wallEpochMs = base + 2 * 3_600_000)
                    }
                    "reboot", "unknown-boot" -> {
                        now = AccessClockStamp(base + 7 * 86_400_000L, 5, if (change == "reboot") "boot-2" else "process-unknown")
                        owner += "-restarted"
                        store = DataStoreTimeLimitUsageStore(disk, { now }, { zone })
                        reader = newReader()
                        store.observeForeground(observation(null))
                    }
                    "query-loss" -> total = null
                    "travel" -> zone = "Pacific/Auckland"
                }
                val retained = reader.read(pkg, 30)
                assertEquals(change, 300_000L, retained.remainingMs)
                assertEquals("UTC", retained.day!!.zoneId)
                assertNull(change, handoff(c, pathB, retained))
                val start = now
                for (elapsed in 15_000L..300_000L step 15_000L) {
                    now = start.copy(wallEpochMs = start.wallEpochMs + elapsed, elapsedRealtimeMs = start.elapsedRealtimeMs + elapsed)
                    store.observeForeground(observation())
                    val budget = reader.read(pkg, 30)
                    assertEquals(change, 300_000L - elapsed, budget.remainingMs)
                    val cause = handoff(c, pathB, budget)
                    if (elapsed < 300_000) assertNull(cause) else {
                        assertEquals(BlockScreenReason.TIME_LIMIT, cause!!.reason)
                        assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
                    }
                }
            } finally { c.hideIfShown(); if (pathB) install(null); BlockExemptions.clearAll(); OpenVisits.process.leave() }
        }
    }

    @Test fun PC_54_legacyStricterMinimumUsesTheSameRetainedUsageFloorThroughBothHandoffs() = runTest {
        for (pathB in listOf(false, true)) {
            now = AccessClockStamp(base, 1_000, "boot")
            val disk = Disk()
            val store = DataStoreTimeLimitUsageStore(disk, { now }, { "UTC" })
            val reader = TrustedTimeLimitReader({ name, stamp -> store.measurementContext(name, stamp) }, store::record,
                { _, _, _ -> 25 * 60_000 }, { now })
            val c = coordinator()
            if (pathB) install(c)
            try {
                assertFalse(reader.read(pkg, 30).spent)
                val effectiveMinutes = listOfNotNull(25, c.timeLimitMinutes(pkg)).minOrNull()!!
                val effective = reader.read(pkg, effectiveMinutes)
                assertTrue(effective.spent)
                assertEquals(25 * 60_000L, effective.day!!.usedMs)
                assertEquals(BlockScreenReason.TIME_LIMIT, handoff(c, pathB, effective)!!.reason)
            } finally { c.hideIfShown(); if (pathB) install(null); BlockExemptions.clearAll(); OpenVisits.process.leave() }
        }
    }
}
