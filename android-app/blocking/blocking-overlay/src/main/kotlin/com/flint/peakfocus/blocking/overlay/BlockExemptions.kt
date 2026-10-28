package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.PersistedExemption
import com.flint.peakfocus.core.model.ExemptionKind
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process ledger of temporary enforcement stand-downs: a granted break or a consumed
 * Emergency Pass exempts a package (or everything, via [GLOBAL_PACKAGE]) until an absolute
 * epoch time. Both detection paths consult this before blocking, so a break granted from
 * either surface (accessibility overlay, Path B overlay, or the full-screen BlockActivity)
 * silences the other path too — they run in the same app process.
 *
 * [restoreCommitted] projects a successfully read/committed durable focus ledger. The old
 * in-memory grant entry points remain compatible until the coordinator adopts transactions.
 *
 * Plain JVM (ConcurrentHashMap, no Android imports) so the grant/expiry semantics are
 * unit-tested in `BlockExemptionsTest`.
 */
object BlockExemptions {

    /** Sentinel package meaning "every package is exempt" (Emergency Pass on a session). */
    const val GLOBAL_PACKAGE: String = "*"

    private val exemptUntil = ConcurrentHashMap<String, Long>()
    private val emergencyUntil = ConcurrentHashMap<String, Long>()

    @Volatile private var committed: List<PersistedExemption> = emptyList()

    /** Replace only the durable projection. Call ONLY with a Ready or acknowledged snapshot. */
    fun restoreCommitted(receipts: Collection<PersistedExemption>, nowEpochMs: Long) {
        committed = receipts.filter { it.untilEpochMs > nowEpochMs }.toList()
    }

    private fun durable(packageName: String, nowEpochMs: Long, emergency: Boolean): Boolean = committed.any {
        (it.packageName == packageName || it.packageName == GLOBAL_PACKAGE) && it.untilEpochMs > nowEpochMs &&
            (it.kind == ExemptionKind.EMERGENCY_PASS) == emergency
    }

    /** Exempt [packageName] until [untilEpochMs]. Overlapping grants keep the later end. */
    fun grant(packageName: String, untilEpochMs: Long) {
        exemptUntil.merge(packageName, untilEpochMs) { old, new -> maxOf(old, new) }
    }

    /** Only explicit Emergency Pass consumption may create a Hardcore-capable exemption. */
    fun grantEmergency(packageName: String, untilEpochMs: Long) {
        emergencyUntil.merge(packageName, untilEpochMs) { old, new -> maxOf(old, new) }
    }

    fun hasOrdinaryExemption(packageName: String, nowEpochMs: Long): Boolean =
        durable(packageName, nowEpochMs, false) ||
            active(exemptUntil, packageName, nowEpochMs) || active(exemptUntil, GLOBAL_PACKAGE, nowEpochMs)

    fun hasEmergencyExemption(packageName: String, nowEpochMs: Long): Boolean =
        durable(packageName, nowEpochMs, true) ||
            active(emergencyUntil, packageName, nowEpochMs) || active(emergencyUntil, GLOBAL_PACKAGE, nowEpochMs)

    /** Evaluate the grant against the current restriction, not the tier when it was issued. */
    fun isExempt(packageName: String, nowEpochMs: Long, level: BreakLevel = BreakLevel.EASY): Boolean =
        hasEmergencyExemption(packageName, nowEpochMs) ||
            (level != BreakLevel.HARDCORE && hasOrdinaryExemption(packageName, nowEpochMs))

    /** The exemption end for [packageName] specifically, or null if none/expired. */
    fun exemptUntil(packageName: String, nowEpochMs: Long): Long? {
        return (listOfNotNull(exemptUntil[packageName], emergencyUntil[packageName]) +
            committed.filter { it.packageName == packageName || it.packageName == GLOBAL_PACKAGE }.map { it.untilEpochMs })
            .filter { it > nowEpochMs }.maxOrNull()
    }

    /** Drop everything (service teardown / tests). */
    fun clearAll() {
        exemptUntil.clear()
        emergencyUntil.clear()
        committed = emptyList()
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
