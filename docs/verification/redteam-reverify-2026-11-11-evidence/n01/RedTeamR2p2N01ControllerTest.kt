package com.flint.peakfocus.blocking.overlay

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Red-team controller-level evidence for residual R2p2-N01 on origin/main b888084.
 *
 * These tests use the real serialized [DataStoreProtectionTransactionStore] and the real
 * durable Time-limit measurement store, driven through [GlobalRecoveryController] — the same
 * commit boundary the block surface uses (`requestCredit`/`requestOrdinary`) — instead of the
 * pure reducers alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RedTeamR2p2N01ControllerTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "com.example.redteam"

    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        override val data: Flow<Preferences> = values
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }

    @After fun clean() {
        BlockExemptions.clearAll()
        ProtectionStatusHub.resetForTests()
    }

    private fun start() = AccessClockStamp(Instant.parse("2026-10-29T12:00:00Z").toEpochMilli(), 10_000, "boot")

    private suspend fun initialized(disk: Disk, timeMinutes: Int): Pair<DataStoreProtectionTransactionStore, AccessClockStamp> {
        val at = start()
        val limits = DataStoreLimitsStore(disk)
        limits.setOpenLimit(OpenLimit(pkg, 3, BreakLevel.HARDCORE))
        limits.setTimeLimit(TimeLimit(pkg, timeMinutes, BreakLevel.HARDCORE, "UTC"))
        val transactions = DataStoreProtectionTransactionStore(disk, { at }) { "UTC" }
        assertTrue(transactions.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        return transactions to at
    }

    @Test fun REOPENS_R2p2N01_controllerChargesACreditWhenTheCommittedLimitIsExceededByDurableUsage() = runTest {
        val disk = Disk()
        val (transactions, at) = initialized(disk, timeMinutes = 30)
        val controller = GlobalRecoveryController(transactions, backgroundScope, { at }, { emptyList() }, {},
            StandardTestDispatcher(testScheduler))
        repeat(3) { runCurrent() }

        // Real production evidence: 20 minutes of measured usage inside this trusted period.
        val usage = DataStoreTimeLimitUsageStore(disk, { at })
        val reader = TrustedTimeLimitReader(
            contextFor = { name, now -> usage.measurementContext(name, now) },
            record = usage::record,
            query = { _, _, _ -> 20 * 60_000L },
            clock = { at },
        )
        val reviewBudget = reader.read(pkg, 30)
        assertFalse("the reviewed 30-minute evidence is not exhausted", reviewBudget.spent)

        val input = OrdinaryProtectionInput(pkg, app.packageName, timeSpent = reviewBudget.spent, openBlocked = true,
            legacyTimeEndEpochMs = null, timePeriodId = reviewBudget.day?.periodId)
        val review = controller.creditView(input, at)!!
        assertEquals(3, review.remainingCredits)
        assertEquals("the reviewed credit has no other blocking contributor", emptyList<String>(), review.blockingContributors)
        val reviewed = GrantRequestId.issue(at, review.requestScope)

        // A strengthening edit lowers the cap to 1 minute; durable usage (20 min) already exceeds it.
        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit(pkg, 1, BreakLevel.HARDCORE, "UTC"))
        repeat(3) { runCurrent() }
        assertTrue(transactions.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        repeat(3) { runCurrent() }
        val commitBudget = reader.read(pkg, 1)
        assertTrue("the committed 1-minute limit is exhausted by durable usage", commitBudget.spent)
        assertEquals(1, controller.lastGood!!.times.single().dailyMinutes)
        assertNotNull("the committed Time cause is materialized", controller.lastGood!!.editor.causes[timeCauseKey(pkg)])

        val committedView = controller.creditView(input, at)!!
        assertEquals("the reviewed Open identity is still current", reviewed.creditScope, committedView.requestScope)

        val result = controller.requestCredit(input, reviewed) { false }
        assertEquals("a credit must not be charged while the committed Time Limit still blocks",
            GrantResult.Denied, result)
        val committed = (transactions.state.first { it !is StorageState.Loading } as StorageState.Ready).value
        assertTrue("quota must not be spent", committed.openCredits!!.counters.isEmpty())
        assertTrue(committed.openCredits!!.active.isEmpty())
        assertTrue(committed.focus.protection!!.pendingCreditEnds.isEmpty())
    }

    @Test fun CLOSED_R2p2N01_controllerRefusesAZeroMinuteLimitCommittedAfterReview() = runTest {
        val disk = Disk()
        val (transactions, at) = initialized(disk, timeMinutes = 30)
        val controller = GlobalRecoveryController(transactions, backgroundScope, { at }, { emptyList() }, {},
            StandardTestDispatcher(testScheduler))
        repeat(3) { runCurrent() }

        val input = OrdinaryProtectionInput(pkg, app.packageName, timeSpent = false, openBlocked = true)
        val review = controller.creditView(input, at)!!
        assertEquals(3, review.remainingCredits)
        assertEquals(emptyList<String>(), review.blockingContributors)
        val reviewed = GrantRequestId.issue(at, review.requestScope)

        DataStoreLimitsStore(disk).setTimeLimit(TimeLimit(pkg, 0, BreakLevel.HARDCORE, "UTC"))
        repeat(3) { runCurrent() }
        assertTrue(transactions.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        repeat(3) { runCurrent() }
        assertEquals(0, controller.lastGood!!.times.single().dailyMinutes)
        assertNotNull("the committed zero-minute cause is materialized",
            controller.lastGood!!.editor.causes[timeCauseKey(pkg)])

        val result = controller.requestCredit(input, reviewed) { false }
        assertEquals(GrantResult.Denied, result)
        val committed = (transactions.state.first { it !is StorageState.Loading } as StorageState.Ready).value
        assertTrue(committed.openCredits!!.counters.isEmpty())
        assertTrue(committed.openCredits!!.active.isEmpty())
        assertTrue(committed.focus.protection!!.pendingCreditEnds.isEmpty())
    }
}
