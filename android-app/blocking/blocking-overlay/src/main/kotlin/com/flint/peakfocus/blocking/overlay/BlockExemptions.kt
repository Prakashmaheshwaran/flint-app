package com.flint.peakfocus.blocking.overlay

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process ledger of temporary enforcement stand-downs: a granted break or a consumed
 * Emergency Pass exempts a package (or everything, via [GLOBAL_PACKAGE]) until an absolute
 * epoch time. Both detection paths consult this before blocking, so a break granted from
 * either surface (accessibility overlay, Path B overlay, or the full-screen BlockActivity)
 * silences the other path too — they run in the same app process.
 *
 * Deliberately **not persisted**: if Flint's process dies mid-break, the break is lost and
 * the shield comes back on the next detection — enforcement fails *strict*, never open.
 * (The durable pieces — breaks-taken count, pending HARDER friction request, spent pass,
 * open counts — all live in core-datastore via the policies; only the short-lived
 * "currently on a break" window lives here.)
 *
 * Plain JVM (ConcurrentHashMap, no Android imports) so the grant/expiry semantics are
 * unit-tested in `BlockExemptionsTest`.
 */
object BlockExemptions {

    /** Sentinel package meaning "every package is exempt" (Emergency Pass on a session). */
    const val GLOBAL_PACKAGE: String = "*"

    private val exemptUntil = ConcurrentHashMap<String, Long>()

    /** Exempt [packageName] until [untilEpochMs]. Overlapping grants keep the later end. */
    fun grant(packageName: String, untilEpochMs: Long) {
        exemptUntil.merge(packageName, untilEpochMs) { old, new -> maxOf(old, new) }
    }

    /** True while [packageName] (or [GLOBAL_PACKAGE]) holds an unexpired exemption. */
    fun isExempt(packageName: String, nowEpochMs: Long): Boolean =
        active(packageName, nowEpochMs) || active(GLOBAL_PACKAGE, nowEpochMs)

    /** The exemption end for [packageName] specifically, or null if none/expired. */
    fun exemptUntil(packageName: String, nowEpochMs: Long): Long? {
        val until = exemptUntil[packageName] ?: return null
        return if (until > nowEpochMs) until else null
    }

    /** Drop everything (service teardown / tests). */
    fun clearAll() = exemptUntil.clear()

    private fun active(key: String, nowEpochMs: Long): Boolean {
        val until = exemptUntil[key] ?: return false
        if (until <= nowEpochMs) {
            // Prune lazily; remove(key, value) only clears if nobody re-granted meanwhile.
            exemptUntil.remove(key, until)
            return false
        }
        return true
    }
}
