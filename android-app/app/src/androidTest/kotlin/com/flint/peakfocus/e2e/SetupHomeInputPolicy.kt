package com.flint.peakfocus.e2e

/** Setup-only Home input budget; callers supply fresh readiness and monotonic time. */
internal class SetupHomeInputPolicy {
    var attempts = 0
        private set
    private var accepted = false
    private var nextAttemptAt = 0L

    fun shouldAttempt(now: Long, deadline: Long, launcherResponsive: Boolean): Boolean =
        !launcherResponsive && !accepted && attempts < 3 && now < deadline && now >= nextAttemptAt

    /** Returns the backoff after a rejected input; the third attempt exhausts the budget. */
    fun recordResult(accepted: Boolean, now: Long): Long {
        check(attempts < 3) { "Setup Home input budget exhausted" }
        attempts++
        this.accepted = accepted
        val backoff = if (!accepted && attempts < 3) attempts * 1_000L else 0L
        nextAttemptAt = now + backoff
        return backoff
    }

    fun afterAnr() {
        accepted = false // An interrupted launcher may need Home again, within the same total budget.
    }
}
