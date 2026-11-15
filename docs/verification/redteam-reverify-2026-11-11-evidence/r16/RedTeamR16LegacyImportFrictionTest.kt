package com.flint.peakfocus.blocking.overlay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.blocking.engine.OpenCreditLedgerPolicy
import com.flint.peakfocus.core.datastore.DataStoreEditorCommitmentStore
import com.flint.peakfocus.core.datastore.DataStoreProtectionTransactionStore
import com.flint.peakfocus.core.datastore.EditorChange
import com.flint.peakfocus.core.datastore.EditorCommitResult
import com.flint.peakfocus.core.datastore.EditorSafety
import com.flint.peakfocus.core.datastore.ProtectionCommit
import com.flint.peakfocus.core.datastore.ProtectionMutation
import com.flint.peakfocus.core.datastore.ProtectionSnapshot
import com.flint.peakfocus.core.datastore.ProtectionTransactionStore
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.StorageState
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Red-team R-16 (P1, Android): an imported (legacy-migrated) Harder contributor must keep the
 * preserved Harder wait/escalation for ordinary breaks. This drives the *real* DataStore
 * migration, the real atomic protection transaction store and the same
 * `GlobalRecoveryController.requestOrdinary` authority the block coordinator calls — not only
 * the pure policy with hand-built snapshots.
 *
 * Precondition fixture: a shipped legacy install (no editor sidecar / protection schema) with an
 * active Harder cause and the old global `break_session_v1` count (2), per ADR-012 §12
 * "Legacy Harder counts: preserve global `break_session_v1`; conservatively seed each currently
 * active Harder occurrence's `n`".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RedTeamR16LegacyImportFrictionTest {
    // 01:00 UTC on a stable day: inside every "whole morning" schedule window used below.
    private val base = AccessClockStamp(1_793_185_200_000L, 10_000, "boot")
    private val pkg = "com.example.legacy"

    private val RULES = stringPreferencesKey("block_rules_v1")
    private val TIMES = stringPreferencesKey("time_limits_v1")
    private val OPENS = stringPreferencesKey("open_limits_v1")
    private val BREAK_SESSION = stringPreferencesKey("break_session_v1")
    private val OPEN_COUNTS = stringPreferencesKey("open_counts_v1")
    private val OPEN_COUNTS_DAY = longPreferencesKey("open_counts_day_v1")

    // MARK: legacy wire fixtures (shipped 10/11-field encodings, no editor sidecar)

    private fun ruleId(name: String) = "legacy-$name"

    private fun rule(name: String, tier: BreakLevel, endMs: Long = base.wallEpochMs + 4 * 3_600_000L): BlockRule =
        BlockRule(ruleId(name), "Imported $name", BlockTargets(apps = setOf(AppRef(pkg))),
            breakLevel = tier, expiresAtEpochMs = endMs)

    private fun scheduledRule(name: String, tier: BreakLevel): BlockRule {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = base.wallEpochMs }
        val isoDay = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else cal.get(Calendar.DAY_OF_WEEK) - 1
        return BlockRule(ruleId(name), "Scheduled $name", BlockTargets(apps = setOf(AppRef(pkg))),
            schedule = Schedule(startMinuteOfDay = 0, endMinuteOfDay = 600, daysOfWeek = setOf(isoDay)),
            breakLevel = tier)
    }

    /** Exactly [com.flint.peakfocus.core.datastore.PrefsCodec.encodeRule]'s 11-field layout. */
    private fun ruleLine(rule: BlockRule): String = listOf(
        rule.id, rule.name, rule.enabled.toString(), rule.breakLevel.name, rule.targets.allowListMode.toString(),
        rule.targets.apps.joinToString(",") { it.packageName + "~" },
        rule.targets.domains.joinToString(",") { it.domain },
        (rule.schedule?.daysOfWeek ?: emptySet()).joinToString(","),
        (rule.schedule?.startMinuteOfDay ?: -1).toString(),
        (rule.schedule?.endMinuteOfDay ?: -1).toString(),
        (rule.expiresAtEpochMs ?: -1L).toString(),
    ).joinToString("|")

    private fun legacyPrefs(vararg rules: BlockRule, breaksTaken: Int = 2): MutablePreferences =
        mutablePreferencesOf(
            RULES to rules.joinToString("\n") { ruleLine(it) },
            BREAK_SESSION to breaksTaken.toString(),
        )

    // MARK: harness

    private class Disk(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val mutex = Mutex()
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(values.value).also { next -> if (next != values.value) values.value = next } }
    }

    private inner class World(initial: Preferences) {
        val disk = Disk(initial)
        var stamp: AccessClockStamp = base
        val editor = DataStoreEditorCommitmentStore(disk, { stamp }) { "UTC" }
        val protection = DataStoreProtectionTransactionStore(disk, { stamp }) { "UTC" }

        /** One real edit: legacy import (no editor sidecar) + protection initialization. */
        suspend fun migrate() {
            editor.reconcile(stamp)
            assertTrue(protection.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        }

        suspend fun snapshot(): ProtectionSnapshot =
            (protection.state.first { it !is StorageState.Loading } as StorageState.Ready).value

        fun advance(elapsedMs: Long, wallMs: Long = elapsedMs) {
            stamp = stamp.copy(
                wallEpochMs = stamp.wallEpochMs + wallMs,
                elapsedRealtimeMs = stamp.elapsedRealtimeMs + elapsedMs,
            )
        }

        suspend fun addNewRule(rule: BlockRule) {
            disk.edit { prefs ->
                val existing = prefs[RULES].orEmpty()
                prefs[RULES] = if (existing.isBlank()) ruleLine(rule) else existing + "\n" + ruleLine(rule)
            }
            editor.reconcile(stamp)
        }

        /** Exactly the production ordinary-break authority call. */
        suspend fun request(input: OrdinaryProtectionInput, nonce: String = UUID.randomUUID().toString()): GrantResult {
            val snap = snapshot()
            val frozen = OrdinaryProtectionPolicy.frozenScopes(snap, input, emptyList(), stamp)
                ?: return GrantResult.Unavailable()
            val commit = protection.transact {
                OrdinaryProtectionPolicy.request(it, input, "p1-$nonce", stamp, emptyList(), frozen)
            }
            return when (commit) {
                is ProtectionCommit.Committed -> commit.result
                is ProtectionCommit.Unavailable -> GrantResult.Unavailable(commit.reason)
            }
        }

        /** Count for this rule's imported cause, whatever its occurrence identity shape is. */
        suspend fun harderCount(rule: BlockRule): Int? =
            snapshot().harder.counts.filterKeys { it.causeId == "rule:${rule.id}" }.values.singleOrNull()

        /** Same authority call against any store instance (used to simulate process recreation). */
        suspend fun confirmOn(
            store: ProtectionTransactionStore,
            input: OrdinaryProtectionInput,
        ): Pair<ProtectionSnapshot, GrantResult> {
            val snap = (store.state.first { it !is StorageState.Loading } as StorageState.Ready).value
            val frozen = OrdinaryProtectionPolicy.frozenScopes(snap, input, emptyList(), stamp)
                ?: return snap to GrantResult.Unavailable()
            val commit = store.transact {
                OrdinaryProtectionPolicy.request(it, input, "p1-${UUID.randomUUID()}", stamp, emptyList(), frozen)
            }
            return when (commit) {
                is ProtectionCommit.Committed -> commit.snapshot to commit.result
                is ProtectionCommit.Unavailable -> snap to GrantResult.Unavailable(commit.reason)
            }
        }
    }

    private fun pendingMs(result: GrantResult): Long {
        assertTrue("expected a durable Pending wait, got $result", result is GrantResult.Pending)
        val pending = (result as GrantResult.Pending).session.pending
        assertNotNull("a Pending result must carry its durable wait interval", pending)
        return pending!!.effectiveAtEpochMs - pending!!.requestedAtEpochMs
    }

    /** Remaining wall time of the reported wait: proves a mid-wait retry did not release early. */
    private fun pendingRemainingMs(result: GrantResult, nowWall: Long): Long {
        assertTrue("expected a durable Pending wait, got $result", result is GrantResult.Pending)
        return (result as GrantResult.Pending).session.pending!!.effectiveAtEpochMs - nowWall
    }

    private fun ordinaryInput(timeSpent: Boolean = false, openBlocked: Boolean = false) =
        OrdinaryProtectionInput(pkg, "self", timeSpent, openBlocked)

    // MARK: A — the requested end-to-end path (import → request → confirm → resume → repeat)

    @Test fun R16A_importedLegacyHarderOpensAtThePreserved900AndEscalatesAfterResume() = runTest {
        val legacy = rule("timer", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()

        // §12: the old global count is preserved onto the active legacy occurrence.
        assertEquals(2, world.harderCount(legacy))

        // The same authority the block surface uses: real migration, real transaction, real policy.
        val controller = GlobalRecoveryController(world.protection, backgroundScope, { world.stamp },
            { emptyList() }, {}, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        val input = ordinaryInput()
        val first = controller.requestOrdinary(input, GrantRequestId.issue(world.stamp))
        assertEquals("imported Harder occurrence must keep its 900 s rung", 900_000L, pendingMs(first))
        assertTrue(world.snapshot().focus.protection!!.ordinaryGrants.isEmpty())
        assertEquals(2, world.harderCount(legacy))

        // Explicit final confirmation after the preserved wait: one increment, 300 s break.
        world.advance(900_000L)
        val confirmed = controller.requestOrdinary(input, GrantRequestId.issue(world.stamp))
        assertTrue("expected the confirmed break, got $confirmed", confirmed is GrantResult.Granted)
        assertTrue((confirmed as GrantResult.Granted).newlyAccepted)
        assertEquals(3, world.harderCount(legacy))

        // Resume, then a second break inside the same occurrence: still the 900 s rung, no reset.
        assertTrue(controller.resumeOrdinary())
        runCurrent()
        val second = controller.requestOrdinary(input, GrantRequestId.issue(world.stamp))
        assertEquals("a second break in the same legacy occurrence must not restart at 60 s",
            900_000L, pendingMs(second))
        assertEquals("an unconfirmed wait spends nothing", 3, world.harderCount(legacy))
    }

    // MARK: B — other imported cause kinds

    @Test fun R16B_importedLegacyScheduledOlderOccurrenceKeepsItsSeededWait() = runTest {
        val legacy = scheduledRule("schedule", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        assertEquals(2, world.harderCount(legacy))
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput())))
    }

    @Test fun R16C_importedLegacyTimeLimitHarderKeepsItsSeededWait() = runTest {
        val world = World(mutablePreferencesOf(
            TIMES to "$pkg~30~HARDER~UTC",
            BREAK_SESSION to "2",
        ))
        world.migrate()
        val seeded = world.snapshot().harder.counts.filterKeys { it.causeId == "time:$pkg" }
        assertEquals("the active legacy Time occurrence is seeded from the old count", 2, seeded.values.single())
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput(timeSpent = true))))
    }

    @Test fun R16D_importedLegacyExhaustedOpenHarderKeepsItsSeededWait() = runTest {
        val day = OpenCreditLedgerPolicy.epochDay(base.wallEpochMs, "UTC")
        val world = World(mutablePreferencesOf(
            OPENS to "$pkg~1~HARDER",
            OPEN_COUNTS to "$pkg~1",
            OPEN_COUNTS_DAY to day,
            BREAK_SESSION to "2",
        ))
        world.migrate()
        val seeded = world.snapshot().harder.counts.filterKeys { it.causeId == "open:$pkg" }
        assertEquals("the active legacy Open occurrence is seeded from the old count", 2, seeded.values.single())
        // Quota exhausted → the ordinary break path (not a credit) still pays the preserved wait.
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput(openBlocked = true))))
    }

    @Test fun R16E_legacyHardcoreStillDeniesOrdinaryBreakAndSpendsNothing() = runTest {
        val legacy = rule("hardcore", BreakLevel.HARDCORE)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        assertEquals(GrantResult.Denied, world.request(ordinaryInput()))
        assertTrue(world.snapshot().focus.protection!!.ordinaryGrants.isEmpty())
        assertEquals(2, world.harderCount(legacy))
    }

    @Test fun R16F_legacyEasyOverlappingLegacyHarderDoesNotLowerTheWait() = runTest {
        val easy = rule("easy", BreakLevel.EASY)
        val harder = rule("harder", BreakLevel.HARDER)
        val world = World(legacyPrefs(easy, harder))
        world.migrate()
        val result = world.request(ordinaryInput())
        assertEquals(900_000L, pendingMs(result))
        val counts = world.snapshot().harder.counts
        assertEquals(2, counts[HarderWaitPolicy.Occurrence("rule:${harder.id}", harder.id)])
        assertNull("an Easy contributor never consumes the Harder counter",
            counts[HarderWaitPolicy.Occurrence("rule:${easy.id}", easy.id)])
    }

    @Test fun R16G_importedLegacyPlusNewHarderWaitsTheMaxAndIncrementsEachExactlyOnce() = runTest {
        val legacy = rule("legacy", BreakLevel.HARDER)
        val fresh = rule("fresh", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        world.addNewRule(fresh)

        val result = world.request(ordinaryInput())
        assertEquals("max(900 seeded legacy, 60 new) must win", 900_000L, pendingMs(result))
        world.advance(900_000L)
        val confirmed = world.request(ordinaryInput())
        assertTrue(confirmed is GrantResult.Granted)
        val counts = world.snapshot().harder.counts
        assertEquals(3, counts[HarderWaitPolicy.Occurrence("rule:${legacy.id}", legacy.id)])
        assertEquals(1, counts[HarderWaitPolicy.Occurrence("rule:${fresh.id}", fresh.id)])
    }

    // MARK: C — durable state, idempotence, restart and clock

    @Test fun R16H_seededCountSurvivesReconcileAndStoreReopen() = runTest {
        val legacy = rule("timer", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        world.editor.reconcile(world.stamp)
        world.editor.reconcile(world.stamp)
        val reopened = DataStoreProtectionTransactionStore(world.disk, { world.stamp }) { "UTC" }
        assertTrue(reopened.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        val counts = (reopened.state.first { it !is StorageState.Loading } as StorageState.Ready)
            .value.harder.counts
        assertEquals("re-migration/reopen must not lose or multiply the seeded count",
            2, counts[HarderWaitPolicy.Occurrence("rule:${legacy.id}", legacy.id)])
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput())))
    }

    @Test fun R16I_pendingWaitSurvivesProcessRecreationAndMaturesOnlyOnConfirmation() = runTest {
        val legacy = rule("timer", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput())))

        // Process death on the same boot: new store instances over the same durable file.
        val reopened = DataStoreProtectionTransactionStore(world.disk, { world.stamp }) { "UTC" }
        val inputs = ordinaryInput()
        val snap = (reopened.state.first { it !is StorageState.Loading } as StorageState.Ready).value
        assertNotNull("the pending wait is durable", snap.harder.pending)
        world.advance(600_000L)
        val early = world.confirmOn(reopened, inputs)
        assertEquals("600 s into a 900 s wait must not release",
            300_000L, pendingRemainingMs(early.second, world.stamp.wallEpochMs))
        assertEquals(900_000L, pendingMs(early.second))
        assertTrue(early.first.focus.protection!!.ordinaryGrants.isEmpty())

        world.advance(300_000L)
        val confirmed = world.confirmOn(reopened, inputs)
        assertTrue(confirmed.second is GrantResult.Granted)
        assertEquals(3, confirmed.first.harder.counts[HarderWaitPolicy.Occurrence("rule:${legacy.id}", legacy.id)])
    }

    @Test fun R16J_wallClockJumpDuringTheWaitDoesNotMatureItEarly() = runTest {
        val legacy = rule("timer", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput())))
        // Wall clock jumps an hour forward; elapsed time only advances 60 s.
        world.advance(60_000L, wallMs = 3_600_000L)
        val retry = world.request(ordinaryInput())
        assertEquals("elapsed accounting, not the wall estimate, owns the wait",
            840_000L, pendingRemainingMs(retry, world.stamp.wallEpochMs))
        assertEquals(900_000L, pendingMs(retry))
        assertTrue(world.snapshot().focus.protection!!.ordinaryGrants.isEmpty())
    }

    @Test fun R16K_rebootCancelsTheWaitWithoutInheritingOrSpendingTheSeededCount() = runTest {
        val legacy = rule("timer", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput())))
        // Reboot: same wall/elapsed bookmark, new boot identity → the unobserved wait is cancelled.
        world.stamp = world.stamp.copy(bootSessionId = "boot-2")
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput())))
        assertEquals(2, world.harderCount(legacy))
    }

    @Test fun R16L_legacyEditRightsStayAllowedButNeverWaiveTheBreakWait() = runTest {
        val legacy = rule("timer", BreakLevel.HARDER)
        val world = World(legacyPrefs(legacy))
        world.migrate()
        // §12: the imported occurrence keeps its old edit route (no wait) …
        val preview = world.editor.request(
            EditorChange("Rename", upserts = listOf(legacy.copy(name = "Renamed"))), world.stamp, EditorSafety(true))
        assertTrue("legacy edit route is preserved, got $preview", preview is EditorCommitResult.Preview)
        val applied = world.editor.confirm(
            (preview as EditorCommitResult.Preview).pending.id, world.stamp, EditorSafety(true))
        assertEquals(EditorCommitResult.Applied(queued = false), applied)
        // … but that right is not ordinary-break authority: the preserved 900 s rung still applies.
        assertEquals(900_000L, pendingMs(world.request(ordinaryInput())))
        assertTrue(world.snapshot().focus.protection!!.ordinaryGrants.isEmpty())
    }

    /**
     * Variant M: the *oldest* legacy Time cause is the per-app budget in core-data's
     * `flint_limits`/`LimitStore`, which has no `time_limits_v1` template row. The coordinator
     * still produces a Harder Time cause for such a package (default tier), and
     * `OrdinaryProtectionPolicy` models it as the synthetic `legacy-time` occurrence. That
     * occurrence never appears in the editor causes the migration seeds, so the preserved
     * `break_session_v1` count is not attached → the migrated user restarts at the 60 s rung.
     */
    @Test fun R16M_legacyBudgetOnlyHarderCauseMustNotRestartAtTheFirstRung() = runTest {
        val world = World(mutablePreferencesOf(
            stringPreferencesKey("break_session_v1") to "2",
            stringPreferencesKey("default_break_level_v1") to BreakLevel.HARDER.name,
        ))
        world.migrate()
        val input = OrdinaryProtectionInput(pkg, "self", timeSpent = true, openBlocked = false,
            url = null, legacyTimeEndEpochMs = base.wallEpochMs + 3_600_000L, timePeriodId = 47L)
        assertEquals("the legacy budget occurrence must inherit the preserved 900 s rung",
            900_000L, pendingMs(world.request(input)))
    }
}
