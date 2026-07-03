package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.core.data.BlocklistStore
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.Schedule
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app-side bridge that `BlockRulesStore`'s KDoc defers to ("a later integration task
 * bridges the two"): keeps [ActiveRulesHolder] — the in-memory rule list both detection
 * paths hand the engine — fed from BOTH rule sources:
 *
 *  - the legacy quick blocklist (core-data [BlocklistStore]: Home tab toggles, debug
 *    receiver, a11y-service connect), projected as one synthetic "blocklist" rule; and
 *  - the full rules the Blocklist tab persists through core-datastore (schedules,
 *    allow-list mode, websites, per-rule break levels), observed as a Flow.
 *
 * Known clobber window (documented, not hidden): the legacy writers — [BlocklistStore.load]
 * on service connect / boot warm-up and every legacy setter — still overwrite the holder
 * with the legacy projection alone. Each DataStore emission and each [republish] call
 * (Home-tab mutations, activity ON_RESUME) restores the combined list, so a clobber lasts
 * at most until the next rule change or app resume. The clean fix — teaching
 * core-data/blocking-accessibility about the DataStore rules — belongs to those modules
 * (hot files outside A-VERIFY's scope); tracked in android-app/README.md.
 */
object ActiveRulesBridge {

    private val started = AtomicBoolean(false)

    @Volatile
    private var dataStoreRules: List<BlockRule> = emptyList()

    /** Begin observing DataStore rules (idempotent). Call from [FlintApplication.onCreate]. */
    fun start(context: Context, scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        scope.launch {
            FlintPreferences.blockRules(appContext).rules.collect { rules ->
                dataStoreRules = rules
                republish(appContext)
            }
        }
    }

    /**
     * Recompute and publish legacy + DataStore rules into [ActiveRulesHolder]. Call after
     * any legacy-store mutation (legacy setters clobber the holder) and on activity resume.
     */
    fun republish(context: Context) {
        ActiveRulesHolder.rules = legacyRules(context) + dataStoreRules
    }

    /**
     * The legacy quick blocklist as one synthetic rule — mirrors core-data
     * `BlocklistStore.syncToHolder` (same "blocklist" id so both writers agree on the rule's
     * identity). Duplicated here because that projection is private to core-data, and
     * core-data is outside this task's scope.
     */
    private fun legacyRules(context: Context): List<BlockRule> {
        val store = BlocklistStore(context)
        val packages = store.blockedPackages
        if (packages.isEmpty()) return emptyList()
        val schedule = if (store.scheduleEnabled) {
            Schedule(
                daysOfWeek = emptySet(),
                startMinuteOfDay = store.scheduleStartMin,
                endMinuteOfDay = store.scheduleEndMin,
            )
        } else {
            null
        }
        return listOf(
            BlockRule(
                id = "blocklist",
                name = "Blocklist",
                targets = BlockTargets(apps = packages.map { AppRef(it) }.toSet()),
                schedule = schedule,
            ),
        )
    }
}
