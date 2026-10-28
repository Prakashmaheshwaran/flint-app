package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.datastore.*
import com.flint.peakfocus.core.model.*
import kotlinx.coroutines.flow.*

/** Fault-injection adapter for the preexisting write/gesture tests. Policy integration uses
 * DataStoreEditorCommitmentStore in PCEditorViewModelTest and EditorCommitmentStoreTest. */
internal class EditorTestStore(private val rules: BlockRulesStore, private val limits: LimitsStore = InMemoryLimitsStore(),
    private val now: () -> Long = { 1_000_000L }) : EditorCommitmentStore {
    private val values = MutableStateFlow<StorageState<EditorCommitmentState>>(StorageState.Ready(EditorCommitmentState()))
    override val state = values.asStateFlow()
    private var pending: Pair<EditorChange, BlockRule>? = null
    override suspend fun request(change: EditorChange, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult {
        if (change.timerExit) {
            val expected = rules.rulesOrError.first().single { it.id in change.deleteIds }
            pending = change to expected
            return EditorCommitResult.Preview(PendingEditorChange(1, change, EditorExpected(listOf(expected), null, null, emptyMap()), CommitmentPolicy.Outcome.ALLOW))
        }
        if (change.limits != null) change.limits!!.let { limits.setLimits(it.packageName, it.time, it.open) }
        else if (change.upserts.singleOrNull()?.id?.startsWith("sleep-") == true || change.upserts.size > 1) rules.updateRules(change.upserts, change.deleteIds)
        else if (change.upserts.isNotEmpty()) rules.upsert(change.upserts.single())
        else change.deleteIds.forEach { rules.delete(it) }
        return EditorCommitResult.Applied()
    }
    override suspend fun confirm(id: Long, now: AccessClockStamp, safety: EditorSafety): EditorCommitResult =
        if (pending?.second?.let { rules.endTimerSession(it, this.now()) } == true) EditorCommitResult.Applied()
        else EditorCommitResult.Rejected("This timer changed or finished. Review its current state.")
    override suspend fun cancel(id: Long) { pending = null }
    override suspend fun dismissMigrationNotice() {}
    override suspend fun reconcile(now: AccessClockStamp) {}
    override suspend fun reconcileRules(now: AccessClockStamp) = rules.rulesOrError.first()
    override suspend fun startTimer(rule: BlockRule, now: AccessClockStamp, safety: EditorSafety) = rules.startSessionIfIdle(rule, this.now())
}

internal class TestHarderWaitStore : HarderWaitStore {
    private val values = MutableStateFlow<StorageState<HarderWaitPolicy.Ledger>>(StorageState.Ready(HarderWaitPolicy.Ledger()))
    override val state = values.asStateFlow()
    override suspend fun <R> transact(transform: (HarderWaitPolicy.Ledger) -> HarderWaitMutation<R>): R {
        val result = transform((values.value as StorageState.Ready).value)
        values.value = StorageState.Ready(result.next)
        return result.result
    }
}
