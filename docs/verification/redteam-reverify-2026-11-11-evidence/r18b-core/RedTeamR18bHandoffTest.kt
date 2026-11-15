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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * R-18b end-to-end through the real reader/coordinator for BOTH detector handoffs: an exhausted
 * Hardcore/Harder daily Time Limit must keep blocking until its trusted reset, even when the wall
 * clock crosses local midnight with little elapsed time, the OS reports zero for the new raw day,
 * the process is recreated, the device reboots or the device travels (ADR-012 §9, PC-54/55/56/58/60).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RedTeamR18bHandoffTest {
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<Preferences> = values
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(values.value).also { values.value = it } }
    }

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "com.example.time-limit"
    private val hour = 3_600_000L
    private val day = 86_400_000L
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private var now = AccessClockStamp(base, 10_000, "boot")
    private var zone = "UTC"
    private var osTotal: Long? = 0L

    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }
    private suspend fun seeded(): Disk {
        val disk = Disk()
        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC"))
        return disk
    }
    private fun store(disk: Disk) = DataStoreTimeLimitUsageStore(disk, { now }, { zone })
    private fun reader(disk: Disk): TrustedTimeLimitReader = store(disk).let { store ->
        TrustedTimeLimitReader({ name, stamp -> store.measurementContext(name, stamp) },
            store::record, { _, _, _ -> osTotal }, { now })
    }
    private suspend fun TestScope.coordinator() = BlockScreenCoordinator(app, backgroundScope, host,
        nowEpochMs = { now.wallEpochMs }, statusClock = { now },
        safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { 0 }, requiredRuntimeSources = emptySet()),
        focusStateStore = InMemoryFocusStateStore(),
        configuredLimitsStore = InMemoryLimitsStore().also {
            it.setTimeLimit(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC"))
        },
        blockRulesStore = InMemoryBlockRulesStore()).also { runCurrent() }

    private fun handoff(c: BlockScreenCoordinator, pathB: Boolean, budget: TrustedTimeLimitBudget): BlockCause? =
        if (pathB) {
            PathBBlockHandoff.onForegroundPolled(app, pkg, Verdict.Allow, rules = emptyList(), timeBudget = budget)
            c.currentBlockCause(pkg)
        } else c.evaluateBlockCause(null, pkg, null, false, timeBudget = budget)

    private fun install(c: BlockScreenCoordinator?) {
        PathBBlockHandoff::class.java.getDeclaredField("coordinator").apply { isAccessible = true }.set(null, c)
    }

    @After fun clean() { BlockExemptions.clearAll(); OpenVisits.process.leave() }

    /** (a)+(b)+(c)+(i) through both handoffs: forged midnight, recreation, reboot, travel, zero OS. */
    @Test fun R18b_bothHandoffsStayBlockedThroughForgedMidnightRecreationRebootAndTravel() = runTest {
        for (pathB in listOf(false, true)) for (change in listOf("process", "reboot", "travel")) {
            now = AccessClockStamp(base, 10_000, "boot")
            zone = "UTC"
            osTotal = 60_000L
            val disk = seeded()
            val c = coordinator()
            if (pathB) install(c)
            try {
                // Exhaust a one-minute Hardcore limit through the real reader.
                val first = reader(disk).read(pkg, 1)
                assertTrue("$change/$pathB exhausted", first.spent)
                assertEquals(BlockScreenReason.TIME_LIMIT, handoff(c, pathB, first)!!.reason)
                val period = first.day!!.periodId
                assertEquals("UTC", first.day!!.zoneId)

                // The clock crosses midnight, the OS reports nothing for the new raw day, and the
                // process/device identity changes.
                osTotal = 0L
                when (change) {
                    "process" -> now = AccessClockStamp(base + 48 * hour, 11_000, "boot")
                    "reboot" -> now = AccessClockStamp(base + 7 * day, 5, "boot-2")
                    "travel" -> { zone = "Pacific/Auckland"; now = AccessClockStamp(base + 1_000, 11_000, "boot") }
                }
                val after = reader(disk).read(pkg, 1)
                assertEquals("$change/$pathB period", period, after.day!!.periodId)
                assertEquals("$change/$pathB saved zone", "UTC", after.day!!.zoneId)
                assertTrue("$change/$pathB still spent", after.spent)
                assertEquals(60_000L, after.day!!.usedMs)
                val cause = handoff(c, pathB, after)
                assertEquals("$change/$pathB cause", BlockScreenReason.TIME_LIMIT, cause!!.reason)
                assertEquals(BreakLevel.HARDCORE, cause.breakLevel)
                // The shield is bounded by the trusted remainder, not by the acute wall clock only.
                assertNotNull(cause.endsAtEpochMs)

                // Repeated zero measurements from an ever-new raw day still cannot refund the floor.
                for (step in 1..3) {
                    now = now.copy(wallEpochMs = now.wallEpochMs + day,
                        elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
                    val repeat = reader(disk).read(pkg, 1)
                    assertTrue("$change/$pathB repeat $step", repeat.spent)
                    assertEquals(period, repeat.day!!.periodId)
                    assertEquals(BlockScreenReason.TIME_LIMIT, handoff(c, pathB, repeat)!!.reason)
                }
            } finally {
                c.hideIfShown()
                if (pathB) install(null)
            }
        }
    }

    /** (g) Two detectors with *different* OS answers must not disagree about exhaustion. */
    @Test fun R18b_twoDetectorsWithDifferentOsAnswersAgreeOnExhaustion() = runTest {
        for (pathB in listOf(false, true)) {
            now = AccessClockStamp(base, 10_000, "boot")
            zone = "UTC"
            val disk = seeded()
            val c = coordinator()
            if (pathB) install(c)
            try {
                osTotal = 60_000L
                val pathA = reader(disk).read(pkg, 1)
                assertTrue(pathA.spent)
                // A second, independently constructed detector measures zero during the same day.
                osTotal = 0L
                val pathBRead = reader(disk).read(pkg, 1)
                assertTrue("shared durable floor", pathBRead.spent)
                assertEquals(pathA.day!!.periodId, pathBRead.day!!.periodId)
                assertEquals(BlockScreenReason.TIME_LIMIT, handoff(c, pathB, pathBRead)!!.reason)
            } finally {
                c.hideIfShown()
                if (pathB) install(null)
            }
        }
    }

    /** The only release is the trusted reset, and it happens once. */
    @Test fun R18b_onlyTheTrustedResetReleasesTheShield() = runTest {
        for (pathB in listOf(false, true)) {
            now = AccessClockStamp(base, 10_000, "boot")
            zone = "UTC"
            osTotal = 60_000L
            val disk = seeded()
            val c = coordinator()
            if (pathB) install(c)
            try {
                val spent = reader(disk).read(pkg, 1)
                assertTrue(spent.spent)
                var cause = handoff(c, pathB, spent)
                assertNotNull(cause)
                // Wall midnights and travel do not release it.
                osTotal = 0L
                var elapsed = 10_000L
                for (delta in listOf(day, 2 * day, -3 * day, 5 * day)) {
                    elapsed += 1_000
                    now = AccessClockStamp(base + delta, elapsed, "boot")
                    cause = handoff(c, pathB, reader(disk).read(pkg, 1))
                    assertEquals("wall $delta", BlockScreenReason.TIME_LIMIT, cause!!.reason)
                }
                // Observed time from the trusted checkpoint does release it — exactly once.
                now = AccessClockStamp(base + 5 * day, 10_000 + hour, "boot")
                val reset = reader(disk).read(pkg, 1)
                assertFalse(reset.spent)
                assertEquals(spent.day!!.periodId + 1, reset.day!!.periodId)
                assertNull(handoff(c, pathB, reset))
                now = now.copy(wallEpochMs = now.wallEpochMs + 3 * day,
                    elapsedRealtimeMs = now.elapsedRealtimeMs + 1_000)
                val stillNew = reader(disk).read(pkg, 1)
                assertEquals(reset.day!!.periodId, stillNew.day!!.periodId)
                assertFalse(stillNew.spent)
            } finally {
                c.hideIfShown()
                if (pathB) install(null)
            }
        }
    }
}
