package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.AccessClockStamp

/**
 * ADR-012 §4 Harder friction: each cause **occurrence** counts its committed weakening
 * operations `n` (break, stop, edit-weaken, off, delete); the required wait is
 * `[60, 300, 900][min(n, 2)]` seconds. A multi-cause operation waits the maximum and increments
 * every affected cause once on commit. Pure: callers persist the returned [Ledger] durably (a
 * pending wait survives process death) and require an explicit final confirmation — a wait
 * maturing never releases anything by itself.
 *
 * Counts are keyed by occurrence, so a new natural occurrence starts at zero; nothing here resets
 * them on a view change, toggle or rename. Cancelling spends nothing and clears the pending
 * request; requesting again restarts the full wait. A reboot cancels a pending wait without
 * incrementing `n` (§9: an unobserved wait is never inferred complete).
 */
class HarderWaitPolicy {

    data class Occurrence(val causeId: String, val occurrenceId: String)

    enum class OperationKind { BREAK, STOP, EDIT_WEAKEN, TOGGLE_OFF, DELETE }

    /**
     * One weakening operation: kind, the exact cause revisions it was previewed against (opaque
     * revision identities, as `AccessCauseScope.definitionRevision`), and target scope. Include
     * only the affected **Harder** causes: Easy grants never consume `n`, Hardcore denies.
     */
    data class Operation(
        val kind: OperationKind,
        val causes: Map<Occurrence, String>,
        val targetScope: Set<String> = emptySet(),
    ) {
        init {
            require(causes.isNotEmpty()) { "a Harder weakening operation names at least one Harder cause" }
        }
    }

    data class PendingWait(val operation: Operation, val startedAt: AccessClockStamp, val requiredMs: Long)

    data class Ledger(val counts: Map<Occurrence, Int> = emptyMap(), val pending: PendingWait? = null)

    sealed interface Request {
        data class Waiting(val ledger: Ledger, val pending: PendingWait, val remainingMs: Long) : Request
    }

    sealed interface Commit {
        data class Committed(val ledger: Ledger) : Commit
        data class NotYet(val remainingMs: Long) : Commit

        /** No matching pending wait (cancelled, rebooted, different operation or revision). */
        data object Stale : Commit
    }

    fun requiredWaitMs(ledger: Ledger, operation: Operation): Long =
        operation.causes.keys.maxOf { cause ->
            val n = ledger.counts[cause] ?: 0
            ProtectionContract.HARDER_WAITS_MS[n.coerceIn(0, ProtectionContract.HARDER_WAITS_MS.lastIndex)]
        }

    /** Remaining wait for [pending], or null when it cannot be measured (reboot → cancelled). */
    fun remainingMs(pending: PendingWait, now: AccessClockStamp): Long? {
        val elapsed = trustedElapsedMs(pending.startedAt, now) ?: return null
        return (pending.requiredMs - elapsed).coerceAtLeast(0L)
    }

    /**
     * Start (or keep) the wait for [operation]. The same operation keeps its running wait; a
     * different operation or revision replaces it with a full new wait (spending nothing).
     */
    fun request(ledger: Ledger, operation: Operation, now: AccessClockStamp): Request.Waiting {
        val live = normalized(ledger, now)
        live.pending?.takeIf { it.operation == operation }?.let { pending ->
            return Request.Waiting(live, pending, remainingMs(pending, now) ?: pending.requiredMs)
        }
        val pending = PendingWait(operation, now, requiredWaitMs(live, operation))
        val next = live.copy(pending = pending)
        return Request.Waiting(next, pending, pending.requiredMs)
    }

    /** Final confirmation: only a matured wait for exactly [operation] commits, once. */
    fun commit(ledger: Ledger, operation: Operation, now: AccessClockStamp): Commit {
        val live = normalized(ledger, now)
        val pending = live.pending?.takeIf { it.operation == operation } ?: return Commit.Stale
        val remaining = remainingMs(pending, now) ?: return Commit.Stale
        if (remaining > 0L) return Commit.NotYet(remaining)
        val counts = live.counts.toMutableMap()
        for (cause in operation.causes.keys) counts[cause] = (counts[cause] ?: 0) + 1
        return Commit.Committed(Ledger(counts = counts, pending = null))
    }

    /** Cancel: nothing consumed, `n` preserved. */
    fun cancel(ledger: Ledger): Ledger = ledger.copy(pending = null)

    /**
     * Forget occurrences **proven terminal** (natural end, completed deletion, or a pass-authorized
     * end) — never ones merely not rematerialized yet or suspended during pass recovery, whose
     * count must survive (§4: counts reset only at a new natural occurrence). A new occurrence has
     * its own key and therefore starts at zero.
     */
    fun withoutTerminated(ledger: Ledger, terminated: Set<Occurrence>): Ledger = ledger.copy(
        counts = ledger.counts.filterKeys { it !in terminated },
        pending = ledger.pending?.takeIf { p -> p.operation.causes.keys.none { it in terminated } },
    )

    /** A pending wait whose start cannot be measured on this clock (reboot) is cancelled. */
    private fun normalized(ledger: Ledger, now: AccessClockStamp): Ledger =
        ledger.pending?.takeIf { remainingMs(it, now) == null }?.let { ledger.copy(pending = null) } ?: ledger
}
