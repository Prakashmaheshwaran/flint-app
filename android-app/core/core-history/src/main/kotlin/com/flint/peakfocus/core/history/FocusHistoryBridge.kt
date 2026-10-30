package com.flint.peakfocus.core.history

import android.content.Context
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.StorageState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Independent IO collector: enforcement never awaits history, disk sync or notifications. */
object FocusHistoryBridge {
    @Volatile private var history: FocusHistoryStore? = null
    @Volatile private var reconciler: HistoryReconciler? = null
    @Volatile private var observedEvents: HistoryObservedQueue? = null
    private val deliveryHealth = HistoryDeliveryHealth()
    private var job: Job? = null

    @Synchronized fun store(context: Context): FocusHistoryStore = history ?: FocusHistoryStore(
        File(context.applicationContext.filesDir, "focus-history"),
    ).also { history = it }

    val activeSessionIds: StateFlow<Set<String>>? get() = reconciler?.activeSessionIds

    fun noteOpenIntercepted(ruleId: String?, at: Long) { observedEvents?.offer(HistoryObservedEvent.Open(ruleId, at)) }
    fun noteBreakGranted(ruleId: String?, until: Long, at: Long) { observedEvents?.offer(HistoryObservedEvent.Break(ruleId, at, until)) }
    fun noteEndRoute(ruleId: String, route: String, at: Long) { observedEvents?.offer(HistoryObservedEvent.End(ruleId, at, route)) }
    fun noteLifecycleEventsDropped() { deliveryHealth.noteEventsDropped() }

    suspend fun clear(context: Context): Boolean {
        val observer = reconciler ?: return false
        if (!observer.ready.value || observer.diagnostic.value != null) return false
        return store(context).clear {
            // Read fresh owner authority under clear's history lock. A lagging observer
            // or a past logical plan must not discard an actually running STARTED.
            val latest = historyRulesSnapshot(ActiveRulesHolder.dataStoreState)
            if (latest !is HistoryRulesSnapshot.Ready) null else latest.rules
                .filter { it.isActiveAt(System.currentTimeMillis()) }.mapTo(mutableSetOf()) { it.id }
        }
    }

    suspend fun retry(context: Context): Boolean = withContext(Dispatchers.IO) {
        val observer = reconciler ?: return@withContext false
        try {
            store(context).load()
            val state = ActiveRulesHolder.dataStoreState
            observer.collectSnapshots(flowOf(historyRulesSnapshot(state)))
            state is StorageState.Ready && observer.ready.value && store(context).snapshots.value.readable
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    /** UI clock ticks re-bucket at midnight and after travel without disk reads on main. */
    fun summaries(context: Context): Flow<FocusSummary?> {
        val observer = reconciler ?: return flowOf(null)
        val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(1_000) } }
        return deliveryHealth.applyTo(combine(store(context).snapshots, observer.activeSessionIds, observer.ready, observer.diagnostic, ticker) {
                snapshot, active, ready, diagnostic, now ->
            when {
                !ready && diagnostic == null -> null
                !ready -> FocusSummary(HistoryAvailability.UNAVAILABLE, emptyList(), emptyList(), 0)
                else -> FocusHistoryAggregator.summarize(
                    snapshot.copy(degraded = snapshot.degraded || diagnostic != null), now, ZoneId.systemDefault(), active)
            }
        }).flowOn(Dispatchers.Default)
    }

    @Synchronized fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val store = store(app)
        val observer = HistoryReconciler(store, authoritativeSnapshot = {
            historyRulesSnapshot(ActiveRulesHolder.dataStoreState)
        }).also { reconciler = it }
        observedEvents = HistoryObservedQueue(scope, HistoryObservedWriter(store)::record)
        FocusCompletionNotifier.start(app, scope, store, observer.ready)
        job = scope.launch(Dispatchers.IO) {
            store.load()
            val rules = historyOwnerSnapshots()
            observer.collectSnapshots(rules.withDeadlineObservations(
                hasTerminal = { id -> store.snapshots.value.events.any { it.session == id && it.knownType?.terminal == true } },
                hasStarted = { id -> store.snapshots.value.events.any { it.session == id && it.knownType == HistoryEventType.STARTED } },
            ))
        }
    }
}
