package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.flint.peakfocus.blocking.engine.*
import com.flint.peakfocus.core.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class EditorDomainUpgradeTest {
    private val now = AccessClockStamp(1_793_181_600_000L, 10_000, "boot")
    private class Disk(initial: Preferences) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        private val mutex = Mutex()
        var beforeCommit: (Preferences) -> Unit = {}
        var loseAck = false
        var changedWrites = 0
        override val data = values.asStateFlow()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(values.value)
            beforeCommit(next)
            if (next != values.value) changedWrites++
            values.value = next
            if (loseAck) throw CancellationException("simulated exit after durable upgrade")
            next
        }
    }

    private fun literalV1() = preferencesOf(
        EDITOR_VERSION_KEY to 1L,
        EDITOR_COMMITMENTS_KEY to "E|1\nN|1|false\nK|${now.wallEpochMs}|10000|boot",
        HARDER_WAIT_KEY to "H|1",
        PrefsKeys.TIME_LIMITS to "", // Always written by the old editor, even when unused.
        stringPreferencesKey("fictional_pin_fixture") to "retained fictional bytes",
        stringPreferencesKey("fictional_history_fixture") to "retained fictional history",
    )
    private fun editor(disk: Disk) = DataStoreEditorCommitmentStore(disk, defaultZone = { "UTC" })
    private suspend fun c3V1(initial: Preferences = emptyPreferences()): Disk {
        val disk = Disk(initial)
        val result = DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }
            .transact { ProtectionMutation(it.focus, Unit) }
        assertTrue(result is ProtectionCommit.Committed)
        assertEquals(ProtectionLedger(now, now), readFocus(disk.values.value).protection)
        // Recreate C3's prior editor-v1 wire: E|1 is unchanged, groups were optional,
        // and an unused Open domain may have no source list. Literal P|1 remains supported.
        val clock = "${now.wallEpochMs}%7C${now.elapsedRealtimeMs}%7Cboot"
        disk.values.value = disk.values.value.toMutablePreferences().apply {
            this[EDITOR_VERSION_KEY] = 1L
            this[PrefsKeys.PROTECTION_LEDGER] = "P|1\nM|$clock|$clock|0|false|0|0||0\nC\nR"
            remove(PrefsKeys.APP_GROUPS); remove(PrefsKeys.OPEN_LIMITS)
        }
        return disk
    }
    private suspend fun unavailable(disk: Disk, reason: StorageFailure = StorageFailure.CORRUPT) {
        val before = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        val store = editor(disk)
        assertEquals(StorageState.Unavailable(reason), store.state.first { it !is StorageState.Loading })
        try { store.reconcile(now); fail("Missing referenced domain accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(reason, failure.reason) }
        assertEquals(before, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
    }

    @Test fun `R2-N03 PC-67 PC-70 main rules-only active Timer upgrades unused domains and remains editable`() =
        mainUpgrade(withUsageTallies = false)

    @Test fun `R2-N03 PC-67 PC-69 PC-70 main generic usage without Open policy preserves counts and remains editable`() =
        mainUpgrade(withUsageTallies = true)

    @Test fun `R2-N03 PC-67 PC-69 PC-70 protection first main usage and tierless Time Limit stays Ready`() =
        mainUpgrade(withUsageTallies = true, protectionFirst = true)

    private fun mainUpgrade(withUsageTallies: Boolean, protectionFirst: Boolean = false) = runBlocking {
        // Literal 11-field bytes emitted by shipped main b34422e: its session writer
        // touches only block_rules_v1, never empty groups, limits, defaults or sidecars.
        val originalStart = now.wallEpochMs - 120_000
        val id = "session-timer-$originalStart"
        val end = originalStart + 300_000
        val raw = "$id|Main Timer|true|HARDCORE|false|com.target~|||-1|-1|$end"
        val recurring = BlockRule("work", "Work", BlockTargets(apps = setOf(AppRef("com.work"))),
            Schedule(startMinuteOfDay = 540, endMinuteOfDay = 660), BreakLevel.HARDCORE)
        val rawRules = if (withUsageTallies) raw + "\n" + PrefsCodec.encodeRules(listOf(recurring)) else raw
        val usage = "com.instagram.android~7\ncom.other~2"
        val day = OpenCreditLedgerPolicy.epochDay(now.wallEpochMs, "UTC")
        val usedAt = now.wallEpochMs - 86_400_000
        val disk = Disk(mutablePreferencesOf(PrefsKeys.BLOCK_RULES to rawRules).apply {
            if (withUsageTallies) {
                this[PrefsKeys.TIME_LIMITS] = "com.limited~30"
                this[PrefsKeys.OPEN_COUNTS] = usage; this[PrefsKeys.OPEN_COUNTS_DAY] = day
                this[PrefsKeys.BREAK_SESSION] = "2"
                this[PrefsKeys.EMERGENCY_PASS_WEEK_START] = day
                this[PrefsKeys.EMERGENCY_PASS_USED_AT] = usedAt
            }
        })
        assertEquals(if (withUsageTallies) setOf(PrefsKeys.BLOCK_RULES, PrefsKeys.OPEN_COUNTS, PrefsKeys.OPEN_COUNTS_DAY,
            PrefsKeys.TIME_LIMITS, PrefsKeys.BREAK_SESSION, PrefsKeys.EMERGENCY_PASS_WEEK_START, PrefsKeys.EMERGENCY_PASS_USED_AT)
            else setOf(PrefsKeys.BLOCK_RULES), disk.values.value.asMap().keys)
        if (protectionFirst) assertTrue(DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }
            .transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        val store = editor(disk)
        repeat(2) { store.reconcile(now) }
        val ready = store.state.first { it !is StorageState.Loading } as StorageState.Ready
        val cause = ready.value.causes.getValue(ruleCauseKey(id))
        assertTrue(cause.legacyRights)
        assertEquals(id, cause.occurrenceId)
        assertEquals(180_000L, cause.interval!!.remainingMs(now))
        assertEquals(end, cause.interval.wallEndEpochMs)
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        assertEquals(rawRules, disk.values.value[stringPreferencesKey("editor_migration_rules_v1")])
        for (key in listOf(PrefsKeys.APP_GROUPS, PrefsKeys.OPEN_LIMITS))
            assertEquals("", disk.values.value[key])
        assertEquals(if (withUsageTallies) "com.limited~30~EASY" else "", disk.values.value[PrefsKeys.TIME_LIMITS])
        assertEquals("EASY", disk.values.value[PrefsKeys.DEFAULT_BREAK_LEVEL])
        val rule = DataStoreBlockRulesStore(disk).rules.first().single { it.id == id }
        assertEquals(id, rule.id); assertEquals(end, rule.expiresAtEpochMs)
        assertEquals(BreakLevel.HARDCORE, rule.breakLevel)
        val preview = store.request(EditorChange("Rename", upserts = listOf(rule.copy(name = "Saved Timer"))),
            now, EditorSafety(true)) as EditorCommitResult.Preview
        assertEquals(EditorCommitResult.Applied(), store.confirm(preview.pending.id, now, EditorSafety(true)))
        repeat(2) { editor(disk).reconcile(now) }
        assertEquals(rule.copy(name = "Saved Timer"), DataStoreBlockRulesStore(disk).rules.first().single { it.id == id })
        assertEquals(if (withUsageTallies) 2 else 1, DataStoreBlockRulesStore(disk).rules.first().size)
        assertEquals(cause, (store.state.first { it !is StorageState.Loading } as StorageState.Ready)
            .value.causes.getValue(ruleCauseKey(id)))
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
        assertTrue(store.readTerminalEvents().isEmpty())
        if (withUsageTallies) {
            assertEquals(usage, disk.values.value[PrefsKeys.OPEN_COUNTS])
            assertEquals(day, disk.values.value[PrefsKeys.OPEN_COUNTS_DAY])
            assertEquals("2", disk.values.value[PrefsKeys.BREAK_SESSION])
            assertEquals(usedAt, disk.values.value[PrefsKeys.EMERGENCY_PASS_USED_AT])
            assertTrue(DataStoreFocusStateStore(disk).state.first { it !is StorageState.Loading } is StorageState.Ready)
            val committed = DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }
                .transact { ProtectionMutation(it.focus, Unit) }
            assertTrue(committed is ProtectionCommit.Committed)
            val snapshot = (committed as ProtectionCommit.Committed).snapshot
            assertTrue(snapshot.opens.isEmpty())
            assertEquals(recurring, snapshot.rules.single { it.id == recurring.id })
            assertTrue(snapshot.editor.causes.getValue(ruleCauseKey(recurring.id)).legacyRights)
            assertEquals(TimeLimit("com.limited", 30, BreakLevel.EASY), snapshot.times.single())
            assertTrue(snapshot.editor.causes.getValue(timeCauseKey("com.limited")).legacyRights)
            assertEquals(mapOf("com.instagram.android" to 7, "com.other" to 2),
                snapshot.openCredits!!.counters.mapValues { it.value.used })
            assertEquals(usage, OpenCreditCodec.readArchive(disk.values.value[OPEN_CREDIT_ARCHIVE]!!).first)
            assertEquals(usedAt, snapshot.focus.protection!!.lastUsedAtEpochMs)
            assertNotNull(snapshot.focus.protection!!.cooldown)
            assertEquals(usage, disk.values.value[PrefsKeys.OPEN_COUNTS])
            assertEquals(day, disk.values.value[PrefsKeys.OPEN_COUNTS_DAY])
            assertEquals(cause, snapshot.editor.causes.getValue(ruleCauseKey(id)))
            assertEquals("", disk.values.value[PrefsKeys.OPEN_LIMITS])
        }
    }

    @Test fun `R2-N03 PC-70 literal unreferenced v1 absences normalize once without changing prior bytes or rights`() = runBlocking {
        val disk = Disk(literalV1()); val before = disk.values.value.asMap().toMap()
        val store = editor(disk)
        store.reconcile(now)
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        for (key in listOf(PrefsKeys.BLOCK_RULES, PrefsKeys.APP_GROUPS, PrefsKeys.TIME_LIMITS, PrefsKeys.OPEN_LIMITS))
            assertEquals("", disk.values.value[key])
        assertEquals("EASY", disk.values.value[PrefsKeys.DEFAULT_BREAK_LEVEL])
        for ((key, value) in before) if (key != EDITOR_VERSION_KEY) assertEquals(value, disk.values.value.asMap()[key])
        val after = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        repeat(2) { editor(disk).reconcile(now) }
        assertEquals(after, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
        assertTrue(readEditorState(disk.values.value).causes.isEmpty())
        assertTrue(readHarderWait(disk.values.value).counts.isEmpty())
        assertNull(disk.values.value[OPEN_CREDIT_SCHEMA])
        assertTrue(store.readTerminalEvents().isEmpty())
    }

    @Test fun `R2-N03 PC-70 C3 before editor upgrade keeps unused groups and Open available through real transactions`() = runBlocking {
        val id = "session-timer-${now.wallEpochMs - 120_000}"
        val end = now.wallEpochMs + 180_000
        val raw = "$id|Main Timer|true|HARDCORE|false|com.target~|||-1|-1|$end"
        val disk = c3V1(preferencesOf(PrefsKeys.BLOCK_RULES to raw))
        val original = disk.values.value
        val before = readEditorState(original).causes.getValue(ruleCauseKey(id))
        val protection = DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }
        assertTrue(protection.state.first { it !is StorageState.Loading } is StorageState.Ready)
        assertTrue(protection.transact { ProtectionMutation(it.focus, Unit) } is ProtectionCommit.Committed)
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        assertEquals("", disk.values.value[PrefsKeys.APP_GROUPS])
        assertEquals("", disk.values.value[PrefsKeys.OPEN_LIMITS])
        assertEquals(original[PrefsKeys.PROTECTION_LEDGER], disk.values.value[PrefsKeys.PROTECTION_LEDGER])
        assertEquals(original[PrefsKeys.PROTECTION_ARCHIVE], disk.values.value[PrefsKeys.PROTECTION_ARCHIVE])
        assertEquals(before, readEditorState(disk.values.value).causes.getValue(ruleCauseKey(id)))
        val store = editor(disk)
        val rule = DataStoreBlockRulesStore(disk).rules.first().single()
        val preview = store.request(EditorChange("Rename", upserts = listOf(rule.copy(name = "Reviewed"))), now,
            EditorSafety(true)) as EditorCommitResult.Preview
        assertEquals(EditorCommitResult.Applied(), store.confirm(preview.pending.id, now, EditorSafety(true)))
        assertEquals(id, DataStoreBlockRulesStore(disk).rules.first().single().id)
        assertEquals(end, DataStoreBlockRulesStore(disk).rules.first().single().expiresAtEpochMs)
        assertEquals(before, readEditorState(disk.values.value).causes.getValue(ruleCauseKey(id)))
        assertTrue(protection.state.first { it !is StorageState.Loading } is StorageState.Ready)
    }

    @Test fun `R2-N03 PC-71 typed C3 recovery grant credit and native end owners forbid missing domains`() = runBlocking {
        for (kind in listOf("recovery", "grant", "credit", "native-end")) {
            val disk = c3V1()
            var ledger = ProtectionLedgerCodec.decode(disk.values.value[PrefsKeys.PROTECTION_LEDGER]!!)
            when (kind) {
                "recovery" -> {
                    ledger = ledger.copy(cooldown = ProtectionCooldown("pass", now.wallEpochMs, now, 604_800_000),
                        recovery = ProtectionRecovery("pass", now, setOf("rule:work"), setOf("work"), emptySet(), 3_600_000),
                        operations = mapOf("pass" to ProtectionPassReceipt("pass", now, now, "scope")), lastUsedAtEpochMs = now.wallEpochMs)
                }
                "grant" -> {
                    val scope = AccessCauseScope(AccessCounterKey(AccessRuleKey(RuleAuthority.Personal, "rule", "work"), "old"), "revision")
                    ledger = ledger.copy(ordinaryGrants = mapOf("break" to ProtectionOrdinaryGrant("break", AccessTarget.App("com.target"),
                        AccessDuration(now, 60_000), setOf(scope), setOf("work"))),
                        operations = mapOf("break" to ProtectionPassReceipt("break", now, now, "scope")))
                }
                "credit" -> ledger = ledger.copy(pendingCreditEnds = mapOf("credit" to
                    ProtectionCreditEventReceipt("credit", "com.target", AccessDuration(now, 60_000))))
                else -> ledger = ledger.copy(outbox = mapOf("pass-event" to ProtectionLifecycleEvent("pass-event", "pass",
                    ProtectionLifecycleKind.PASS_COMMITTED, setOf("session-timer-old"), now,
                    untilEpochMs = now.wallEpochMs + 3_600_000, endedTransientIds = setOf("session-timer-old"))))
            }
            disk.values.value = disk.values.value.toMutablePreferences().apply {
                this[PrefsKeys.PROTECTION_LEDGER] = ProtectionLedgerCodec.encode(ledger)
                ledger.lastUsedAtEpochMs?.let { this[PrefsKeys.EMERGENCY_PASS_USED_AT] = it }
                if (kind in listOf("recovery", "grant", "native-end")) remove(PrefsKeys.BLOCK_RULES)
            }
            unavailable(disk)
            disk.values.value = disk.values.value.toMutablePreferences().apply {
                remove(EDITOR_COMMITMENTS_KEY); remove(EDITOR_VERSION_KEY)
            }
            unavailable(disk)
        }
    }

    @Test fun `R2-N03 PC-70 foreign recovery and history IDs do not own an unused native rules domain`() = runBlocking {
        val disk = c3V1()
        val recovery = ProtectionRecovery("foreign-pass", now, setOf("foreign:foreign-rule"), setOf("foreign-rule"), emptySet(), 60_000)
        val ledger = ProtectionLedger(now, now, cooldown = ProtectionCooldown("foreign-pass", now.wallEpochMs, now, 604_800_000),
            recovery = recovery, operations = mapOf("foreign-pass" to ProtectionPassReceipt("foreign-pass", now, now, "scope")),
            outbox = mapOf("foreign-history" to ProtectionLifecycleEvent("foreign-history", "foreign-pass",
                ProtectionLifecycleKind.PASS_COMMITTED, setOf("foreign-rule"), now, untilEpochMs = now.wallEpochMs + 60_000)),
            lastUsedAtEpochMs = now.wallEpochMs)
        disk.values.value = disk.values.value.toMutablePreferences().apply {
            remove(PrefsKeys.BLOCK_RULES)
            this[PrefsKeys.PROTECTION_LEDGER] = ProtectionLedgerCodec.encode(ledger)
            this[PrefsKeys.EMERGENCY_PASS_USED_AT] = now.wallEpochMs
        }
        editor(disk).reconcile(now)
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        assertEquals("", disk.values.value[PrefsKeys.BLOCK_RULES])
        assertEquals(ledger, readFocus(disk.values.value).protection)
    }

    @Test fun `R2-N03 PC-71 own editor archive and pending terminal preserve a missing rules domain`() = runBlocking {
        val id = "session-timer-old"
        val raw = "$id|Old|true|HARDCORE|false|com.target~|||-1|-1|${now.wallEpochMs}"
        unavailable(Disk(literalV1().toMutablePreferences().apply {
            this[stringPreferencesKey("editor_migration_rules_v1")] = raw
        }))
        val event = EditorTerminalEvent("editor:completed:$id", id, id, EditorTerminalKind.COMPLETED, now, now, 1, false)
        unavailable(Disk(literalV1().toMutablePreferences().apply {
            this[EDITOR_TERMINAL_SCHEMA] = 2L
            this[EDITOR_TERMINALS] = EditorTerminalCodec.encode(EditorTerminalLedger(revision = 1, pending = mapOf(event.id to event)))
        }))
    }

    @Test fun `R2-N03 PC-71 legacy Open archive remains ownership even when current counters are empty`() = runBlocking {
        val disk = Disk(literalV1().toMutablePreferences().apply {
            this[PrefsKeys.OPEN_COUNTS] = "com.target~2"
            this[PrefsKeys.OPEN_COUNTS_DAY] = OpenCreditLedgerPolicy.epochDay(now.wallEpochMs, "UTC")
        })
        disk.edit { prepareOpenCredits(it, now, "UTC") }
        disk.values.value = disk.values.value.toMutablePreferences().apply {
            val credits = readOpenCredits(this)
            putOpenCredits(credits.copy(counters = emptyMap()))
            remove(PrefsKeys.OPEN_COUNTS); remove(PrefsKeys.OPEN_LIMITS)
        }
        unavailable(disk)
    }

    @Test fun `R2-N03 PC-71 C3 legacy policy and written default copies cannot become fresh empty or Easy`() = runBlocking {
        for (key in listOf(PrefsKeys.OPEN_LIMITS, PrefsKeys.DEFAULT_BREAK_LEVEL)) {
            val initial = if (key == PrefsKeys.OPEN_LIMITS) preferencesOf(key to "com.target~3~EASY")
                else preferencesOf(key to "HARDCORE")
            val disk = c3V1(initial)
            disk.values.value = disk.values.value.toMutablePreferences().apply {
                val saved = EditorCommitmentCodec.decode(this[EDITOR_COMMITMENTS_KEY])
                remove(key)
                this[EDITOR_COMMITMENTS_KEY] = EditorCommitmentCodec.encode(saved.copy(causes = emptyMap()))
            }
            unavailable(disk)
        }
    }

    @Test fun `R2-N03 PC-71 every v2 domain deletion remains unavailable in the initialized C3 backend`() = runBlocking {
        for (key in listOf(PrefsKeys.BLOCK_RULES, PrefsKeys.APP_GROUPS, PrefsKeys.TIME_LIMITS, PrefsKeys.OPEN_LIMITS, PrefsKeys.DEFAULT_BREAK_LEVEL)) {
            val disk = c3V1()
            editor(disk).reconcile(now)
            disk.values.value = disk.values.value.toMutablePreferences().apply { remove(key) }
            unavailable(disk)
            assertEquals(ProtectionCommit.Unavailable(StorageFailure.CORRUPT),
                DataStoreProtectionTransactionStore(disk, { now }) { "UTC" }.transact { ProtectionMutation(it.focus, Unit) })
        }
    }

    @Test fun `R2-N03 PC-71 malformed incomplete duplicate or future C3 archive never proves unused domains`() = runBlocking {
        for (kind in listOf("duplicate", "missing", "presence", "unknown-key", "future")) {
            val disk = c3V1()
            disk.values.value = disk.values.value.toMutablePreferences().apply {
                val original = this[PrefsKeys.PROTECTION_ARCHIVE]!!
                this[PrefsKeys.PROTECTION_ARCHIVE] = when (kind) {
                    "duplicate" -> original + "\n" + original.lines()[1]
                    "missing" -> original.lines().dropLast(1).joinToString("\n")
                    "presence" -> original.replaceFirst("|false|", "|unknown|")
                    "unknown-key" -> original.replace(PrefsKeys.BLOCK_RULES.name, "stray_corrupt_key")
                    else -> original.replaceFirst("A|1", "A|2")
                }
            }
            unavailable(disk, if (kind == "future") StorageFailure.UNSUPPORTED_VERSION else StorageFailure.CORRUPT)
        }
    }

    @Test fun `R2-N03 PC-37 PC-71 markerless surviving domain owners cannot become fresh empty policy`() = runBlocking {
        for (key in listOf("rule:old", "time:com.target", "open:com.target")) {
            val occurrence = HarderWaitPolicy.Occurrence(key, "old")
            val wait = HarderWaitPolicy.PendingWait(HarderWaitPolicy.Operation(HarderWaitPolicy.OperationKind.DELETE,
                mapOf(occurrence to "revision")), now, 60_000)
            for (ledger in listOf(HarderWaitPolicy.Ledger(counts = mapOf(occurrence to 2)),
                HarderWaitPolicy.Ledger(pending = wait))) {
                unavailable(Disk(preferencesOf(HARDER_WAIT_KEY to HarderWaitCodec.encode(ledger))))
            }
            unavailable(Disk(preferencesOf(PrefsKeys.FOCUS_LEDGER to FocusLedgerCodec.encode(
                FocusLedgerCodec.Ledger(breaksByCause = mapOf(key to BreakSessionState(breaksTaken = 1)))))))
        }
        val receipt = PersistedExemption("old", "com.target", "legacy-open", ExemptionKind.OPEN_LIMIT,
            now.wallEpochMs + 60_000, "boot")
        unavailable(Disk(preferencesOf(PrefsKeys.FOCUS_LEDGER to FocusLedgerCodec.encode(
            FocusLedgerCodec.Ledger(exemptions = mapOf("old" to receipt))))))
        val credits = Disk(preferencesOf(PrefsKeys.OPEN_COUNTS to "com.target~2",
            PrefsKeys.OPEN_COUNTS_DAY to OpenCreditLedgerPolicy.epochDay(now.wallEpochMs, "UTC")))
        credits.edit { prepareOpenCredits(it, now, "UTC") }
        credits.values.value = credits.values.value.toMutablePreferences().apply {
            remove(PrefsKeys.OPEN_COUNTS); remove(PrefsKeys.OPEN_COUNTS_DAY)
            remove(PrefsKeys.OPEN_LIMITS)
        }
        unavailable(credits)
        for (key in listOf(PrefsKeys.OPEN_LIMITS, PrefsKeys.DEFAULT_BREAK_LEVEL)) {
            val initial = if (key == PrefsKeys.OPEN_LIMITS) preferencesOf(key to "com.target~3~EASY")
                else preferencesOf(key to "HARDCORE")
            val disk = c3V1(initial)
            disk.values.value = disk.values.value.toMutablePreferences().apply {
                remove(EDITOR_COMMITMENTS_KEY); remove(EDITOR_VERSION_KEY); remove(key)
            }
            unavailable(disk)
        }
    }

    @Test fun `R2-N03 PC-37 PC-71 missing v1 Time Limits is corrupt even with no references`() = runBlocking {
        unavailable(Disk(literalV1().toMutablePreferences().apply { remove(PrefsKeys.TIME_LIMITS) }))
    }

    @Test fun `R2-N03 PC-11 PC-71 active inactive and tombstone rule or Open causes forbid absence before upgrade`() = runBlocking {
        for (key in listOf("rule:work", "open:com.target")) {
            for (cause in listOf(EditorCause("inactive", AnchoredInterval(now, 0, now.wallEpochMs), "UTC"),
                EditorCause("old", AnchoredInterval(now, 60_000, now.wallEpochMs + 60_000), "UTC"),
                EditorCause("old", AnchoredInterval(now, 0, now.wallEpochMs), "UTC"))) {
                val initial = literalV1().toMutablePreferences().apply {
                    this[EDITOR_COMMITMENTS_KEY] = EditorCommitmentCodec.encode(EditorCommitmentState(
                        causes = mapOf(key to cause), timeline = now))
                }
                unavailable(Disk(initial))
            }
        }
    }

    @Test fun `R2-N03 PC-71 pending and queued editor ownership is checked before applying or pruning`() = runBlocking {
        val change = EditorChange("Delete", deleteIds = setOf("work"))
        val expected = EditorExpected(emptyList(), null, null, mapOf("rule:work" to "old"))
        val pending = PendingEditorChange(1, change, expected, CommitmentPolicy.Outcome.ALLOW)
        val queued = QueuedEditorChange(1, change, expected, mapOf("rule:work" to AnchoredInterval(now, 0, now.wallEpochMs)))
        for (state in listOf(EditorCommitmentState(nextId = 2, pending = pending, timeline = now),
            EditorCommitmentState(nextId = 2, queued = listOf(queued), timeline = now))) {
            unavailable(Disk(literalV1().toMutablePreferences().apply {
                this[EDITOR_COMMITMENTS_KEY] = EditorCommitmentCodec.encode(state)
            }))
        }
    }

    @Test fun `R2-N03 PC-18 PC-71 Harder count or pending wait keeps its referenced domain unavailable`() = runBlocking {
        for (key in listOf("rule:work", "open:com.target")) {
            val occurrence = HarderWaitPolicy.Occurrence(key, "old")
            val wait = HarderWaitPolicy.PendingWait(HarderWaitPolicy.Operation(HarderWaitPolicy.OperationKind.DELETE,
                mapOf(occurrence to "revision")), now, 60_000)
            for (ledger in listOf(HarderWaitPolicy.Ledger(counts = mapOf(occurrence to 3)),
                HarderWaitPolicy.Ledger(pending = wait))) {
                unavailable(Disk(literalV1().toMutablePreferences().apply { this[HARDER_WAIT_KEY] = HarderWaitCodec.encode(ledger) }))
            }
        }
    }

    @Test fun `R2-N03 PC-37 PC-71 Open receipts and credit rows own policy while generic usage does not`() = runBlocking {
        val receipt = PersistedExemption("old", "com.target", "legacy-open", ExemptionKind.OPEN_LIMIT,
            now.wallEpochMs + 60_000, "boot")
        unavailable(Disk(literalV1().toMutablePreferences().apply {
            this[PrefsKeys.FOCUS_LEDGER] = FocusLedgerCodec.encode(FocusLedgerCodec.Ledger(exemptions = mapOf("old" to receipt)))
        }))
        val usage = Disk(literalV1().toMutablePreferences().apply {
            this[PrefsKeys.OPEN_COUNTS] = "com.target~2"
            this[PrefsKeys.OPEN_COUNTS_DAY] = OpenCreditLedgerPolicy.epochDay(now.wallEpochMs, "UTC")
        })
        editor(usage).reconcile(now)
        assertEquals(2L, usage.values.value[EDITOR_VERSION_KEY])
        assertEquals("", usage.values.value[PrefsKeys.OPEN_LIMITS])
        assertEquals("com.target~2", usage.values.value[PrefsKeys.OPEN_COUNTS])
        val disk = Disk(literalV1().toMutablePreferences().apply {
            this[PrefsKeys.OPEN_LIMITS] = ""
            this[PrefsKeys.OPEN_COUNTS] = "com.target~2"
            this[PrefsKeys.OPEN_COUNTS_DAY] = OpenCreditLedgerPolicy.epochDay(now.wallEpochMs, "UTC")
        })
        disk.edit { prepareOpenCredits(it, now, "UTC") }
        disk.values.value = disk.values.value.toMutablePreferences().apply {
            remove(PrefsKeys.OPEN_LIMITS); remove(PrefsKeys.OPEN_COUNTS)
        }
        unavailable(disk)
    }

    @Test fun `R2-N03 PC-71 opaque surviving recovery cannot prove optional domains unused`() = runBlocking {
        unavailable(Disk(literalV1().toMutablePreferences().apply {
            this[stringPreferencesKey("protection_pass_outbox_v1")] = "opaque surviving recovery bytes"
        }))
    }

    @Test fun `R2-N03 PC-70 crash or write failure after upgrade transformation keeps all original bytes and marker`() = runBlocking {
        for (crash in listOf(false, true)) {
            val disk = Disk(literalV1())
            val before = disk.values.value.asMap().toMap()
            disk.beforeCommit = { next ->
                assertEquals(2L, next[EDITOR_VERSION_KEY])
                assertEquals("", next[PrefsKeys.APP_GROUPS])
                if (crash) throw CancellationException("simulated exit before durable upgrade")
                else throw IOException("simulated disk failure before durable upgrade")
            }
            try { editor(disk).reconcile(now); fail("Failure not injected") }
            catch (failure: Exception) { assertTrue(failure is IOException || failure is CancellationException) }
            assertEquals(before, disk.values.value.asMap()); assertEquals(0, disk.changedWrites)
            disk.beforeCommit = {}
            repeat(2) { editor(disk).reconcile(now) }
            assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY]); assertEquals(1, disk.changedWrites)
        }
    }

    @Test fun `R2-N03 PC-70 lost acknowledgement reopens the same complete upgrade without extra writes`() = runBlocking {
        val disk = Disk(literalV1()).apply { loseAck = true }
        try { editor(disk).reconcile(now); fail("Lost acknowledgement not injected") }
        catch (_: CancellationException) { }
        val committed = disk.values.value.asMap().toMap(); val writes = disk.changedWrites
        disk.loseAck = false
        repeat(2) { editor(disk).reconcile(now) }
        assertEquals(2L, disk.values.value[EDITOR_VERSION_KEY])
        assertEquals(committed, disk.values.value.asMap()); assertEquals(writes, disk.changedWrites)
    }
}
