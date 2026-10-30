package com.flint.peakfocus.feature.blocklist

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PCSleepEditorContractTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val base = Instant.parse("2026-10-28T23:00:00Z").toEpochMilli()
    private val rulesKey = stringPreferencesKey("block_rules_v1")
    private class Disk(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val values = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            transform(values.value).also { values.value = it }
        }
    }
    private data class Harness(val vm: BlocklistViewModel, val editor: EditorCommitmentStore, val rules: BlockRulesStore)
    private fun harness(disk: Disk, clock: () -> AccessClockStamp): Harness {
        val rules = DataStoreBlockRulesStore(disk)
        val editor = DataStoreEditorCommitmentStore(disk, clockAtCommit = clock, defaultZone = { "UTC" })
        val vm = BlocklistViewModel(rules, DataStoreLimitsStore(disk), DataStoreFocusStateStore(disk), InMemoryGroupsStore(),
            { emptyList() }, editor, DataStoreHarderWaitStore(disk), ioDispatcher = dispatcher,
            clock = clock, safety = { EditorSafety(true) })
        owners += ViewModelStore().also { it.put("sleep", vm) }
        return Harness(vm, editor, rules)
    }
    private suspend fun Harness.state() = (editor.state.first { it is StorageState.Ready } as StorageState.Ready).value

    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { owners.forEach { it.clear() }; Dispatchers.resetMain() }

    /** Literal origin/main format: 10 columns, or11 after its optional session expiry field. */
    private fun mainRows(tier: BreakLevel, expiryField: Boolean = true, morningTier: BreakLevel = tier): String {
        val tail = if (expiryField) "|-1" else ""
        return "sleep-window|Sleep|true|${tier.name}|true|com.example.reader~Reader||1,2,3,4,5|1350|420$tail\n" +
            "sleep-morning|Morning assist|true|${morningTier.name}|true|com.example.reader~Reader||2,3,4,5,6|420|480$tail"
    }

    @Test fun PC_82_literalMainRowsPreserveEveryTierAcrossUpgradeReopenAndConfirmedEdit() = runTest(dispatcher) {
        val stamp = AccessClockStamp(base, 10_000, "boot")
        for (tier in BreakLevel.entries) for (expiry in listOf(false, true)) {
            val h = harness(Disk(preferencesOf(rulesKey to mainRows(tier, expiry))), { stamp })
            runCurrent()
            val initial = sleepConfigFrom(h.rules.rules.first())!!
            assertEquals(tier, initial.breakLevel)
            assertEquals(tier, initial.morningBreakLevel)
            assertEquals(when (tier) {
                BreakLevel.EASY -> SleepAssist.WIND_DOWN
                BreakLevel.HARDER -> SleepAssist.FULL_ASSIST
                BreakLevel.HARDCORE -> null
            }, initial.assist)
            var accepted = false
            h.vm.saveSleep(initial.copy(bedMinute = 22 * 60)) { accepted = true }
            runCurrent()
            assertFalse(accepted)
            val pending = checkNotNull(h.state().pending)
            h.vm.confirmChange(pending.id); runCurrent()
            assertTrue(accepted)
            val upgraded = sleepConfigFrom(h.rules.rules.first())!!
            assertEquals(tier, upgraded.breakLevel)
            assertEquals(tier, upgraded.morningBreakLevel)
            assertEquals(22 * 60, upgraded.bedMinute)
            assertEquals(initial.allowedApps, upgraded.allowedApps)
            assertEquals(initial.nights, upgraded.nights)
            owners.last().clear()
        }
    }

    @Test fun PC_82_literalMixedSavedTiersSurviveAnUnrelatedConfirmedSleepEdit() = runTest(dispatcher) {
        val stamp = AccessClockStamp(base, 10_000, "boot")
        val h = harness(Disk(preferencesOf(rulesKey to mainRows(BreakLevel.EASY, morningTier = BreakLevel.HARDCORE))), { stamp })
        runCurrent()
        val config = sleepConfigFrom(h.rules.rules.first())!!
        h.vm.saveSleep(config.copy(morningEndMinute = 9 * 60)); runCurrent()
        h.vm.confirmChange(checkNotNull(h.state().pending).id); runCurrent()
        val after = sleepConfigFrom(h.rules.rules.first())!!
        assertEquals(BreakLevel.EASY, after.breakLevel)
        assertEquals(BreakLevel.HARDCORE, after.morningBreakLevel)
        assertEquals(9 * 60, after.morningEndMinute)
    }

    @Test fun PC_11_PC_14_activeSleepPresetDowngradeUsesRealWaitOrFutureQueueAndHardcoreNeedsDisclosure() = runTest(dispatcher) {
        for (tier in listOf(BreakLevel.HARDER, BreakLevel.HARDCORE)) {
            fun clock() = AccessClockStamp(base + testScheduler.currentTime, 10_000 + testScheduler.currentTime, "boot")
            val h = harness(Disk(), ::clock)
            runCurrent()
            val original = SleepConfig(enabled = true, nights = emptySet(), breakLevel = tier)
            var accepted = 0
            h.vm.saveSleep(original) { accepted++ }; runCurrent()
            assertTrue(h.rules.rules.first().isEmpty())
            val first = checkNotNull(h.state().pending)
            if (tier == BreakLevel.HARDCORE) assertEquals(CommitmentPolicy.Outcome.ALLOW_AFTER_DISCLOSURE, first.outcome)
            h.vm.confirmChange(first.id); runCurrent()
            assertEquals(1, accepted)
            val occurrence = h.state().causes.getValue("rule:$SLEEP_WINDOW_RULE_ID")
            assertFalse(occurrence.legacyRights)

            h.vm.saveSleep(original.withAssist(SleepAssist.WIND_DOWN)) { accepted++ }; runCurrent()
            val pending = checkNotNull(h.state().pending)
            assertEquals(if (tier == BreakLevel.HARDCORE) CommitmentPolicy.Outcome.DEFER else CommitmentPolicy.Outcome.WAIT,
                pending.outcome)
            h.vm.confirmChange(pending.id); runCurrent()
            assertEquals(tier, h.rules.rules.first().single().breakLevel)
            if (tier == BreakLevel.HARDCORE) {
                assertEquals(2, accepted)
                assertEquals(BreakLevel.EASY, h.state().queued.single().change.upserts.single().breakLevel)
                assertEquals(occurrence.occurrenceId, h.state().causes.getValue("rule:$SLEEP_WINDOW_RULE_ID").occurrenceId)
            } else {
                assertEquals(1, accepted)
                advanceTimeBy(60_000); runCurrent()
                h.vm.confirmChange(pending.id); runCurrent()
                assertEquals(2, accepted)
                assertEquals(BreakLevel.EASY, h.rules.rules.first().single().breakLevel)
            }
            owners.last().clear()
        }
    }
}
