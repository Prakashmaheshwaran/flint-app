package com.flint.peakfocus.blocking.overlay

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference
import com.flint.peakfocus.core.model.ProtectionLifecycleEvent

/**
 * Outbound, observe-only enforcement events for local history (lane F's focus-session store),
 * registered by the app shell — the same "register a hook" seam as `BootReceiver`, so this
 * module never depends on the history module. Listeners only observe: nothing they do can grant,
 * deny or delay enforcement.
 *
 * Contract:
 *  - [Listener.onOpenIntercepted] fires once per NEW block surface mount for a foreground app (a
 *    different package, or the first mount after the surface was hidden) — never per one-second
 *    re-render or cause refresh. Provisional holds for an unacknowledged normal-open
 *    counter write never report an interception.
 *  - [Listener.onBreakGranted] / [Listener.onEmergencyPassUsed] fire only for accepted grants,
 *    after credit consumption and its exemption receipt are durably committed together.
 *    Idempotent retries do not emit another accepted-grant event.
 *  - `ruleId` is the rule behind the cause when the cause is a rule verdict; null for daily Time
 *    Limit, Open Limit and uninstall-guard causes.
 *  - Called synchronously on the caller's thread (often main): listeners must enqueue and return.
 *    A throwing listener is isolated — it never breaks enforcement or other listeners.
 */
object BlockEventHooks {

    /** Return true only after a durable, event.id-deduplicated local history write. */
    fun interface DurableConsumer {
        suspend fun deliver(event: ProtectionLifecycleEvent): Boolean
    }
    private val durableConsumer = AtomicReference<DurableConsumer?>(null)
    private val durableInvalidations = CopyOnWriteArraySet<() -> Unit>()

    /** One local-history owner; registration also retries previously retained events. */
    fun registerDurableConsumer(consumer: DurableConsumer): () -> Unit {
        check(durableConsumer.compareAndSet(null, consumer)) { "A durable lifecycle consumer is already registered" }
        durableInvalidations.forEach { runCatching(it) }
        return { durableConsumer.compareAndSet(consumer, null); Unit }
    }

    internal fun currentDurableConsumer(): DurableConsumer? = durableConsumer.get()
    internal fun subscribeDurableConsumer(listener: () -> Unit): () -> Unit {
        durableInvalidations += listener
        return { durableInvalidations -= listener }
    }

    enum class BreakEndReason(val wireValue: String) {
        EXPIRED("expired"), RESUMED("resumed"), SUPERSEDED("superseded"), RECOVERY("recovery"),
    }

    interface Listener {
        fun onOpenIntercepted(ruleId: String?, atEpochMs: Long) = Unit
        @Deprecated("Use the overload with grantId to pair start and end of each durable grant")
        fun onBreakGranted(ruleId: String?, untilEpochMs: Long, atEpochMs: Long) = Unit

        /** Stable id from the committed receipt; legacy observers receive the old hook once. */
        @Suppress("DEPRECATION")
        fun onBreakGranted(ruleId: String?, grantId: String, untilEpochMs: Long, atEpochMs: Long) {
            onBreakGranted(ruleId, untilEpochMs, atEpochMs)
        }
        @Deprecated("Use onEmergencyPassCommitted for global recovery scope and operation identity")
        fun onEmergencyPassUsed(ruleId: String?, atEpochMs: Long) = Unit

        /**
         * One committed global recovery operation. A legacy observer receives its old hook
         * through this default only; an observer overriding this method gets no duplicate
         * compatibility callback. Names/targets never travel through this observe-only seam.
         */
        @Suppress("DEPRECATION")
        fun onEmergencyPassCommitted(operationId: String, affectedRuleIds: Set<String>, recoveryEndEpochMs: Long, atEpochMs: Long) {
            onEmergencyPassUsed(affectedRuleIds.singleOrNull(), atEpochMs)
        }

        /** One durably accepted end of an ordinary/credit interval; grantId is its stable id. */
        fun onBreakEnded(ruleId: String?, grantId: String, reason: BreakEndReason, atEpochMs: Long) = Unit
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun register(listener: Listener) { listeners += listener }

    fun unregister(listener: Listener) { listeners -= listener }

    internal fun openIntercepted(ruleId: String?, atEpochMs: Long) =
        each { it.onOpenIntercepted(ruleId, atEpochMs) }

    internal fun breakGranted(ruleId: String?, grantId: String, untilEpochMs: Long, atEpochMs: Long) =
        each { it.onBreakGranted(ruleId, grantId, untilEpochMs, atEpochMs) }

    @Suppress("DEPRECATION")
    internal fun emergencyPassUsed(ruleId: String?, atEpochMs: Long) =
        each { it.onEmergencyPassUsed(ruleId, atEpochMs) }

    /** Observe-only compatibility hook, called after an acknowledged new acceptance. */
    internal fun emergencyPassCommitted(operationId: String, affectedRuleIds: Set<String>, recoveryEndEpochMs: Long, atEpochMs: Long) {
        val frozen = affectedRuleIds.toSet()
        each { it.onEmergencyPassCommitted(operationId, frozen, recoveryEndEpochMs, atEpochMs) }
    }

    /** Observe-only compatibility hook; durable history uses [DurableConsumer]. */
    internal fun breakEnded(ruleId: String?, grantId: String, reason: BreakEndReason, atEpochMs: Long) =
        each { it.onBreakEnded(ruleId, grantId, reason, atEpochMs) }

    private inline fun each(call: (Listener) -> Unit) {
        for (listener in listeners) runCatching { call(listener) }
    }
}
