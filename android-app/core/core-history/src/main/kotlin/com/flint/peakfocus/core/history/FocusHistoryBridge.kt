package com.flint.peakfocus.core.history

import android.content.Context
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.BlockRule
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Independent IO collector: enforcement never awaits history, disk sync or notifications. */
object FocusHistoryBridge {
    @Volatile private var history: FocusHistoryStore? = null
    @Volatile private var reconciler: HistoryReconciler? = null
    private var job: Job? = null

    @Synchronized fun store(context: Context): FocusHistoryStore = history ?: FocusHistoryStore(
        File(context.applicationContext.filesDir, "focus-history"),
    ).also { history = it }

    val activeSessionIds: StateFlow<Set<String>>? get() = reconciler?.activeSessionIds

    suspend fun clear(context: Context): Boolean {
        val observer = reconciler ?: return false
        if (!observer.ready.value) return false
        return store(context).clear(observer.activeSessionIds.value)
    }

    suspend fun retry(context: Context): Boolean = withContext(Dispatchers.IO) {
        val observer = reconciler ?: return@withContext false
        try {
            store(context).load()
            observer.observe(project(FlintPreferences.blockRules(context).rulesOrError.first()))
            observer.ready.value && store(context).snapshots.value.readable
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    private fun project(rules: List<BlockRule>): List<HistoryRule> = rules.filter {
        it.id.startsWith("session-") && it.enabled && it.schedule == null
    }.map { HistoryRule(it.id, it.name, it.breakLevel.name.lowercase(Locale.ROOT), it.expiresAtEpochMs) }

    /** UI clock ticks re-bucket at midnight and after travel without disk reads on main. */
    fun summaries(context: Context): Flow<FocusSummary?> {
        val observer = reconciler ?: return flowOf(null)
        val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
        return combine(store(context).snapshots, observer.activeSessionIds, observer.ready, observer.diagnostic, ticker) {
                snapshot, active, ready, diagnostic, now ->
            when {
                !ready && diagnostic == null -> null
                !ready -> FocusSummary(HistoryAvailability.UNAVAILABLE, emptyList(), emptyList(), 0)
                else -> FocusHistoryAggregator.summarize(snapshot, now, ZoneId.systemDefault(), active)
            }
        }.flowOn(Dispatchers.Default)
    }

    @Synchronized fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val store = store(app)
        val observer = HistoryReconciler(store).also { reconciler = it }
        job = scope.launch(Dispatchers.IO) {
            store.load()
            observer.collectSnapshots(FlintPreferences.blockRules(app).rulesState.map(::historyRulesSnapshot).withDeadlineObservations(
                hasTerminal = { id -> store.snapshots.value.events.any { it.session == id && it.knownType?.terminal == true } },
            ))
        }
    }
}
