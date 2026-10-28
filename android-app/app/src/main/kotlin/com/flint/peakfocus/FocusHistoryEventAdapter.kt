package com.flint.peakfocus

import android.content.Context
import com.flint.peakfocus.blocking.overlay.BlockEventHooks
import com.flint.peakfocus.core.history.FocusHistoryBridge
import com.flint.peakfocus.core.history.HistoryEvent
import kotlinx.coroutines.CoroutineScope

/** App shell owns both dependencies; enforcement modules remain independent of history. */
internal object FocusHistoryEventAdapter {
    private var listener: FocusHistoryHookListener? = null
    @Synchronized fun start(context: Context, scope: CoroutineScope) {
        FocusHistoryBridge.start(context, scope)
        if (listener != null) return
        listener = FocusHistoryHookListener().also(BlockEventHooks::register)
    }
}

internal class FocusHistoryHookListener(
    private val open: (String?, Long) -> Unit = FocusHistoryBridge::noteOpenIntercepted,
    private val ordinaryBreak: (String?, Long, Long) -> Unit = FocusHistoryBridge::noteBreakGranted,
) : BlockEventHooks.Listener {
    override fun onOpenIntercepted(ruleId: String?, atEpochMs: Long) {
        if (ruleId?.startsWith("session-") == true) open(ruleId, atEpochMs)
    }
    override fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) {
        if (ruleId?.startsWith("session-") == true && untilEpochMs > atEpochMs) ordinaryBreak(ruleId, untilEpochMs, atEpochMs)
    }
    // The legacy pass callback has no operation id/affected ids/recovery end. Omit it.
}

/** Owner intent remains pending until its idempotent history append has committed. */
internal suspend fun projectHistoryTerminal(
    event: HistoryEvent,
    append: suspend (HistoryEvent) -> Boolean,
    acknowledge: suspend () -> Unit,
): Boolean {
    if (!append(event)) return false
    acknowledge()
    return true
}
