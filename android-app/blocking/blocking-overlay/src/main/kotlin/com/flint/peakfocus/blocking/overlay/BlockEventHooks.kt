package com.flint.peakfocus.blocking.overlay

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Outbound, observe-only enforcement events for local history (lane F's focus-session store),
 * registered by the app shell — the same "register a hook" seam as `BootReceiver`, so this
 * module never depends on the history module. Listeners only observe: nothing they do can grant,
 * deny or delay enforcement.
 *
 * Contract:
 *  - [Listener.onOpenIntercepted] fires once per NEW block surface mount for a foreground app (a
 *    different package, or the first mount after the surface was hidden) — never per one-second
 *    re-render or cause refresh.
 *  - [Listener.onBreakGranted] / [Listener.onEmergencyPassUsed] fire only for accepted grants,
 *    after credit consumption and its exemption receipt are durably committed together.
 *    Idempotent retries do not emit another accepted-grant event.
 *  - `ruleId` is the rule behind the cause when the cause is a rule verdict; null for daily Time
 *    Limit, Open Limit and uninstall-guard causes.
 *  - Called synchronously on the caller's thread (often main): listeners must enqueue and return.
 *    A throwing listener is isolated — it never breaks enforcement or other listeners.
 */
object BlockEventHooks {

    interface Listener {
        fun onOpenIntercepted(ruleId: String?, atEpochMs: Long) = Unit
        fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) = Unit
        fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) = Unit
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun register(listener: Listener) { listeners += listener }

    fun unregister(listener: Listener) { listeners -= listener }

    internal fun openIntercepted(ruleId: String?, atEpochMs: Long) =
        each { it.onOpenIntercepted(ruleId, atEpochMs) }

    internal fun breakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) =
        each { it.onBreakGranted(ruleId, untilEpochMs, atEpochMs) }

    internal fun emergencyPassUsed(ruleId: String?, atEpochMs: Long) =
        each { it.onEmergencyPassUsed(ruleId, atEpochMs) }

    private inline fun each(call: (Listener) -> Unit) {
        for (listener in listeners) runCatching { call(listener) }
    }
}
