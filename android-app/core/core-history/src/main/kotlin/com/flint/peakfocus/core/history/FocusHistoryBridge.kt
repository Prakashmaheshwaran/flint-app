package com.flint.peakfocus.core.history

import android.content.Context
import com.flint.peakfocus.core.datastore.FlintPreferences
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Independent IO collector: enforcement never awaits history, disk sync or notifications. */
object FocusHistoryBridge {
    @Volatile private var history: FocusHistoryStore? = null
    @Volatile private var reconciler: HistoryReconciler? = null
    private var job: Job? = null

    @Synchronized fun store(context: Context): FocusHistoryStore = history ?: FocusHistoryStore(
        File(context.applicationContext.filesDir, "focus-history"),
    ).also { history = it }

    val activeSessionIds: StateFlow<Set<String>>? get() = reconciler?.activeSessionIds

    @Synchronized fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val store = store(app)
        val observer = HistoryReconciler(store).also { reconciler = it }
        job = scope.launch(Dispatchers.IO) {
            store.load()
            observer.collect(FlintPreferences.blockRules(app).rulesOrError.map { rules ->
                rules.filter { it.id.startsWith("session-") && it.enabled && it.schedule == null }.map {
                    HistoryRule(it.id, it.name, it.breakLevel.name.lowercase(Locale.ROOT), it.expiresAtEpochMs)
                }
            })
        }
    }
}
