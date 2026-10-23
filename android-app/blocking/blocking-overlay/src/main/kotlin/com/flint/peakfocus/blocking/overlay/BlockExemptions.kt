package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel
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
    private val emergencyUntil = ConcurrentHashMap<String, Long>()

    /** Exempt [packageName] until [untilEpochMs]. Overlapping grants keep the later end. */
    fun grant(packageName: String, untilEpochMs: Long) {
        exemptUntil.merge(packageName, untilEpochMs) { old, new -> maxOf(old, new) }
    }

    /** Only explicit Emergency Pass consumption may create a Hardcore-capable exemption. */
    fun grantEmergency(packageName: String, untilEpochMs: Long) {
        emergencyUntil.merge(packageName, untilEpochMs) { old, new -> maxOf(old, new) }
    }

    fun hasOrdinaryExemption(packageName: String, nowEpochMs: Long): Boolean =
        active(exemptUntil, packageName, nowEpochMs) || active(exemptUntil, GLOBAL_PACKAGE, nowEpochMs)

    fun hasEmergencyExemption(packageName: String, nowEpochMs: Long): Boolean =
        active(emergencyUntil, packageName, nowEpochMs) || active(emergencyUntil, GLOBAL_PACKAGE, nowEpochMs)

    /** Evaluate the grant against the current restriction, not the tier when it was issued. */
    fun isExempt(packageName: String, nowEpochMs: Long, level: BreakLevel = BreakLevel.EASY): Boolean =
        hasEmergencyExemption(packageName, nowEpochMs) ||
            (level != BreakLevel.HARDCORE && hasOrdinaryExemption(packageName, nowEpochMs))

    /** The exemption end for [packageName] specifically, or null if none/expired. */
    fun exemptUntil(packageName: String, nowEpochMs: Long): Long? {
        return listOfNotNull(exemptUntil[packageName], emergencyUntil[packageName])
            .filter { it > nowEpochMs }.maxOrNull()
    }

    /** Drop everything (service teardown / tests). */
    fun clearAll() {
        exemptUntil.clear()
        emergencyUntil.clear()
    }

    private fun active(ledger: ConcurrentHashMap<String, Long>, key: String, nowEpochMs: Long): Boolean {
        val until = ledger[key] ?: return false
        if (until <= nowEpochMs) {
            // Prune lazily; remove(key, value) only clears if nobody re-granted meanwhile.
            ledger.remove(key, until)
            return false
        }
        return true
    }
}
