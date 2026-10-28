package com.flint.peakfocus.blocking.overlay

/** Both detectors share one real foreground visit; a system/Flint bounce preserves it. */
internal class OpenVisits {
    data class Visit(val packageName: String, val id: GrantRequestId, val charge: Boolean, @Volatile var paid: Boolean = false)
    private var current: Visit? = null

    @Synchronized fun observe(packageName: String, countable: Boolean, charge: Boolean, now: Long): Visit? {
        if (!countable) return current
        val old = current
        if (old == null || old.packageName != packageName || (!old.paid && now - old.id.issuedAtEpochMs >= DurableGrantController.RETRY_HORIZON_MS)) {
            current = Visit(packageName, GrantRequestId.issue(now), charge)
        }
        return current
    }

    @Synchronized fun current(packageName: String): Visit? = current?.takeIf { it.packageName == packageName }
    @Synchronized fun acknowledge(visit: Visit) { if (current === visit) visit.paid = true }
    @Synchronized fun leave() { current = null }
    companion object { val process = OpenVisits() }
}
