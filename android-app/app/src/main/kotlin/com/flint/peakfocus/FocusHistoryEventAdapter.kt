package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.blocking.overlay.BlockEventHooks
import com.flint.peakfocus.core.datastore.EditorTerminalDeliveryHealth
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** App shell owns both dependencies; enforcement modules remain independent of history. */
internal object FocusHistoryEventAdapter {
    private var listener: FocusHistoryHookListener? = null
    @Synchronized fun start(context: Context, scope: CoroutineScope) {
        FocusHistoryBridge.start(context, scope)
        if (listener != null) return
        ProtectionHistoryEventAdapter.start(context, scope)
        listener = FocusHistoryHookListener().also(BlockEventHooks::register)
        FocusHistoryTerminalAdapter.start(context, scope)
        scope.launch {
            reportEditorHistoryLoss(FlintPreferences.editorCommitments(context.applicationContext).terminalDeliveryHealth)
        }
    }
}

/** Retained pending/overflow evidence is not loss; only the owner's public flag is authoritative. */
internal suspend fun reportEditorHistoryLoss(
    health: Flow<StorageState<EditorTerminalDeliveryHealth>>,
    reportMissing: () -> Unit = FocusHistoryBridge::noteLifecycleEventsDropped,
) {
    health.collect { state ->
        if (state is StorageState.Ready && state.value.incompleteHistory) reportMissing()
    }
}

internal class FocusHistoryHookListener(
    private val open: (String?, Long) -> Unit = FocusHistoryBridge::noteOpenIntercepted,
) : BlockEventHooks.Listener {
    override fun onOpenIntercepted(ruleId: String?, atEpochMs: Long) {
        if (ruleId?.startsWith("session-") == true) open(ruleId, atEpochMs)
    }
    // Pass and break records belong solely to the durable consumer. Compatibility Unit
    // callbacks cannot acknowledge a disk write and would duplicate its break records.
}
