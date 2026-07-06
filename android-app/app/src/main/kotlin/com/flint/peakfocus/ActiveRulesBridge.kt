package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.ActiveRulesHolder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app-side bridge that `BlockRulesStore`'s KDoc defers to ("a later integration task
 * bridges the two"): observes the full rules the Blocklist tab persists through core-datastore
 * (schedules, allow-list mode, websites, per-rule break levels) and publishes each emission
 * into [ActiveRulesHolder]'s DataStore lane.
 *
 * The legacy quick blocklist (core-data `BlocklistStore`: Home tab toggles, debug receiver,
 * a11y-service connect) publishes its own lane directly — the holder merges the lanes, so
 * neither writer can drop the other's rules out of enforcement.
 */
object ActiveRulesBridge {

    private val started = AtomicBoolean(false)

    /** Begin observing DataStore rules (idempotent). Call from [FlintApplication.onCreate]. */
    fun start(context: Context, scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            FlintPreferences.blockRules(appContext).rules.collect { rules ->
                ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, rules)
            }
        }
    }
}
