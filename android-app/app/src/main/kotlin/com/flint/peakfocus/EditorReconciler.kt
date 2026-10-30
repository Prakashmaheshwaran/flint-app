package com.flint.peakfocus

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Boot/clock hooks publish reconciliation success or failure before refreshing the legacy source. */
internal object EditorReconciler {
    fun rearm(context: Context, scope: CoroutineScope) {
        scope.launch {
            ActiveRulesBridge.reconcileAndPublish(context)
            ActiveRulesBridge.republish(context)
        }
    }
}
