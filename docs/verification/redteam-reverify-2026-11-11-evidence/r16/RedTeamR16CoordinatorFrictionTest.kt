package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.DataStoreBlockRulesStore
import com.flint.peakfocus.core.datastore.DataStoreEditorCommitmentStore
import com.flint.peakfocus.core.datastore.DataStoreFocusStateStore
import com.flint.peakfocus.core.datastore.DataStoreLimitsStore
import com.flint.peakfocus.core.datastore.DataStoreProtectionTransactionStore
import com.flint.peakfocus.core.datastore.ProtectionCommit
import com.flint.peakfocus.core.datastore.ProtectionMutation
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/**
 * Red-team R-16 (P1, Android), surface-level leg: the *block coordinator's* own
 * `requestBreak` against a really-migrated legacy DataStore must still show the preserved
 * Harder wait, require the explicit confirmation, and not restart at 60 s on the second break
 * inside the same imported occurrence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RedTeamR16CoordinatorFrictionTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val base = AccessClockStamp(1_793_185_200_000L, 10_000, "boot")
    private val pkg = "com.example.legacy"
    private val rule = BlockRule("legacy-timer", "Imported", BlockTargets(apps = setOf(AppRef(pkg))),
        breakLevel = BreakLevel.HARDER, expiresAtEpochMs = base.wallEpochMs + 4 * 3_600_000L)

    private val host = object : BlockScreenCoordinator.SurfaceHost {
        override fun attach(view: View) = false
        override fun detach(view: View) = Unit
        override fun goHome() = Unit
    }

    @After fun clean() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        BlockExemptions.clearAll()
        ProtectionStatusHub.resetForTests()
    }

    private class Disk(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(values.value).also { next -> if (next != values.value) values.value = next } }
    }

    private fun legacyRuleLine(rule: BlockRule): String = listOf(
        rule.id, rule.name, rule.enabled.toString(), rule.breakLevel.name, "false",
        rule.targets.apps.joinToString(",") { it.packageName + "~" }, "", "", "-1", "-1",
        (rule.expiresAtEpochMs ?: -1L).toString(),
    ).joinToString("|")

    private fun legacyPrefs(): MutablePreferences = mutablePreferencesOf(
        stringPreferencesKey("block_rules_v1") to legacyRuleLine(rule),
        stringPreferencesKey("break_session_v1") to "2",
    )

    @Test fun R16_coordinatorBreakOnMigratedHarderWaits900ThenConfirmsAndEscalates() = runTest {
        val disk = Disk(legacyPrefs())
        var stamp = base
        val editor = DataStoreEditorCommitmentStore(disk, { stamp }) { "UTC" }
        val protection = DataStoreProtectionTransactionStore(disk, { stamp }) { "UTC" }
        editor.reconcile(stamp)
        assertTrue(protection.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule))

        val coordinator = BlockScreenCoordinator(app, backgroundScope, host,
            nowEpochMs = { stamp.wallEpochMs },
            safetyFloorGate = SafetyFloorGate(app.packageName, { emptyMap() }, { stamp.elapsedRealtimeMs },
                requiredRuntimeSources = emptySet()),
            focusStateStore = DataStoreFocusStateStore(disk),
            configuredLimitsStore = DataStoreLimitsStore(disk),
            blockRulesStore = DataStoreBlockRulesStore(disk),
            editorCommitmentStore = editor,
            statusClock = { stamp },
            statusDispatcher = StandardTestDispatcher(testScheduler),
            protectionTransactionStore = protection)
        runCurrent()

        val displayed = coordinator.evaluateBlockCause(null, pkg, ruleBlockCause(rule, stamp.wallEpochMs, 0), false)
        assertNotNull(displayed)
        assertEquals(BreakLevel.HARDER, displayed!!.breakLevel)

        val first = coordinator.requestBreak(pkg, displayed, GrantRequestId.issue(stamp))
        assertTrue("first break on a migrated Harder cause must wait, got $first", first is GrantResult.Pending)
        val pending = coordinator.currentBreakSession().pending
        assertNotNull("the surface must show the durable wait", pending)
        assertEquals(900_000L, pending!!.effectiveAtEpochMs - pending.requestedAtEpochMs)
        assertFalse("a wait alone releases nothing", coordinator.isExempt(pkg, displayed))
        assertNotNull("a wait alone releases nothing", coordinator.currentBlockCause(pkg, displayed))

        stamp = stamp.copy(wallEpochMs = stamp.wallEpochMs + 900_000L, elapsedRealtimeMs = stamp.elapsedRealtimeMs + 900_000L)
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        val confirmed = coordinator.requestBreak(pkg, coordinator.currentBlockCause(pkg, displayed)!!,
            GrantRequestId.issue(stamp))
        assertTrue("confirmed break after the preserved wait must grant, got $confirmed", confirmed is GrantResult.Granted)
        assertTrue(coordinator.isExempt(pkg, displayed))

        assertTrue(coordinator.resumeOrdinaryBreaks())
        runCurrent()
        assertNotNull("resume re-shields the target", coordinator.currentBlockCause(pkg, displayed))

        val second = coordinator.requestBreak(pkg, coordinator.currentBlockCause(pkg, displayed)!!,
            GrantRequestId.issue(stamp))
        assertTrue("second break in the same occurrence must wait, got $second", second is GrantResult.Pending)
        val secondPending = coordinator.currentBreakSession().pending
        assertNotNull(secondPending)
        assertEquals("a repeat break must not drop back to the 60 s rung",
            900_000L, secondPending!!.effectiveAtEpochMs - secondPending.requestedAtEpochMs)
        assertFalse(coordinator.isExempt(pkg, displayed))
        assertNotNull(coordinator.currentBlockCause(pkg, displayed))
    }
}
