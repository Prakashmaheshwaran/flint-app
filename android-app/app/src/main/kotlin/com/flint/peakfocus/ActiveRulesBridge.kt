package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.ActiveRulesHolder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    /** Begin observing DataStore rules (idempotent). Call from [FlintApplication.onCreate]. */
    fun start(context: Context, scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            val store = FlintPreferences.blockRules(appContext)
            store.rules.collect { rules ->
                ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, rules)
                // Lazy sweep of spent one-shot sessions. Enforcement never depends on this —
                // the engine checks expiry itself — it just keeps dead rows out of the store.
                // Each delete re-emits, so the sweep converges in one extra pass.
                val now = System.currentTimeMillis()
                rules.filter { it.isExpired(now) }.forEach { store.delete(it.id) }
            }
        }
    }

    /**
     * Re-sync the legacy projection from its persisted prefs (cheap, synchronous). The
     * DataStore contribution needs no refresh here — the [start] collector re-publishes it on
     * every emission. Kept for the existing call sites (activity ON_RESUME, Home-tab
     * mutations) as a belt-and-braces freshness pass; it is no longer load-bearing for
     * correctness the way it was under the clobber-window regime.
     */
    fun republish(context: Context) {
        BlocklistStore(context).load()
    }
}
