package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.ProtectionClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Broadcast hooks supplement the live bridge's one-second, persisted boundary reconciliation. */
internal object EditorReconciler {
    fun rearm(context: Context, scope: CoroutineScope) {
        scope.launch {
            runCatching { FlintPreferences.editorCommitments(context).reconcile(ProtectionClock.now()) }
            // The bridge publishes unavailable on read/write failure; no success is fabricated here.
            ActiveRulesBridge.republish(context)
        }
    }
}
