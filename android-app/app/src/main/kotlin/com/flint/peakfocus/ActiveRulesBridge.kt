package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.blocking.engine.CachedRuleClockProjection
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.EditorCommitmentStore
import com.flint.peakfocus.core.datastore.editorPublicationFailure
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.StorageState
import com.flint.peakfocus.core.model.AccessClockStamp
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.flint.peakfocus.core.datastore.ProtectionClock

/**
 * The app-side bridge that `BlockRulesStore`'s KDoc defers to ("a later integration task
 * bridges the two"): streams the full rules the Blocklist tab persists through core-datastore
 * (schedules, allow-list mode, websites, per-rule break levels) into [ActiveRulesHolder] —
 * the in-memory rule list both detection paths hand the engine.
 *
 * The holder is source-keyed, so this bridge owns only the [ActiveRulesHolder.SOURCE_DATASTORE]
 * contribution; the legacy quick blocklist (core-data [BlocklistStore]: Home-tab toggles, debug
 * receiver, a11y-service connect) publishes its own projection under its own key on every
 * mutation and on `load()`. The old clobber window — legacy writers overwriting the combined
 * list and dropping DataStore rules from enforcement until the next republish — is gone by
 * construction.
 */
object ActiveRulesBridge {

    private val started = AtomicBoolean(false)
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val publication = Mutex()

    /** Begin observing DataStore rules (idempotent). Call from [FlintApplication.onCreate]. */
    fun start(context: Context, scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val projection = CachedRuleClockProjection(ProtectionClock::now)
        ActiveRulesHolder.configureProjection(projection::project)
        val appContext = context.applicationContext
        scope.launch {
            val store = FlintPreferences.editorCommitments(appContext)
            runEditorRulePublication(merge(store.changes, invalidations), { refresh(appContext) }, ::unavailable)
        }
    }

    /** Reconcile and publish its acknowledged result before returning, then replace the timer. */
    suspend fun reconcileAndPublish(context: Context) {
        try { refresh(context.applicationContext) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Throwable) { unavailable(cause) }
        finally { invalidations.tryEmit(Unit) }
    }

    private suspend fun refresh(context: Context): Long? =
        reconcileAndPublish(FlintPreferences.editorCommitments(context), ProtectionClock.now())

    /** Shared production entry: storage faults publish Unavailable while the holder retains its finite plan. */
    internal suspend fun reconcileAndPublish(store: EditorCommitmentStore, now: AccessClockStamp): Long? = publication.withLock {
        try {
            // One edit reconciles queued changes before publishing an adjacent occurrence.
            val rules = store.reconcileRules(now)
            val state = store.state.first { it !is StorageState.Loading }
            when (state) {
                is StorageState.Ready -> {
                    val nextDelay = nextEditorReconciliationDelay(rules, state.value, now)
                    ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, StorageState.Ready(rules))
                    nextDelay
                }
                is StorageState.Unavailable -> {
                    ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, state)
                    EDITOR_RETRY_MS
                }
                StorageState.Loading -> error("No completed editor snapshot")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Throwable) { unavailable(cause); EDITOR_RETRY_MS }
    }

    private fun unavailable(cause: Throwable) {
        ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, StorageState.Unavailable(editorPublicationFailure(cause)))
    }

    /**
     * Re-sync the legacy projection from its persisted prefs (cheap, synchronous). The
     * DataStore contribution has its own change/boundary driver in [start]. Existing activity
     * and Home-tab call sites use this only to refresh the independent legacy source.
     */
    fun republish(context: Context) {
        BlocklistStore(context).load()
    }
}
