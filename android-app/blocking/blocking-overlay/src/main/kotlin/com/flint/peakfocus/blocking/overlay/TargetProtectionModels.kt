package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel
import kotlinx.coroutines.flow.Flow

/**
 * Display-only target queries. Collecting never migrates/checkpoints storage, changes a rule,
 * accepts access, or debits a counter. A null blocking decision is unknown, not permission.
 * Hosts share one reader in their scope and use the supplied summary instead of reducing rows.
 */
interface TargetProtectionReader {
    fun observe(target: TargetRef): Flow<TargetProtection>
}

/** Facts about the requested identity only; no inventory of other apps or domains. */
data class TargetProtection(
    val readiness: Readiness,
    /** Independently resolved; an unavailable policy never hides a known safety identity. */
    val floor: FloorIdentity? = null,
    val coveringCauses: List<CoveringCause> = emptyList(),
    val openCredit: OpenCreditView? = null,
    val timeLimit: TimeLimitView? = null,
    val nextChangeAtWall: Long? = null,
    val blockingNow: Boolean? = null,
    /** Latest known enforcing end, distinct from the strongest-tier headline's own end. */
    val blockingUntilWall: Long? = null,
    /** True only for known open-ended enforcement, never merely because an end is unknown. */
    val blockingOngoing: Boolean = false,
    val recoveryEndsAtWall: Long? = null,
    val headline: CoveringCause? = null,
    val websiteCoverage: TargetWebsiteCoverage? = null,
)

/** Target-scoped interpretation of the actual supported-browser health observation. */
enum class TargetWebsiteCoverage {
    AVAILABLE, UNCONFIRMED, DEGRADED, UNSUPPORTED, NEEDS_ACCESSIBILITY, AWAITING_BROWSER,
}

/** An explicit read request; a target observation never includes another target's inventory. */
sealed interface TargetRef {
    data class App(val packageName: String) : TargetRef
    data class Site(val domain: String) : TargetRef
}

sealed interface Readiness {
    data object Loading : Readiness
    data object Ready : Readiness
    /** Stable machine-readable reason; no raw storage exceptions or sensitive payloads. */
    data class Unavailable(val reason: String) : Readiness
}

data class FloorIdentity(
    val kind: Kind,
    /** Last successful resolution of this source; elapsed time in the current process/boot. */
    val resolvedAtElapsedMs: Long? = null,
    val degraded: Boolean = false,
) {
    enum class Kind { STATIC_SYSTEM, SELF, RUNTIME_HOME, RUNTIME_DIALER, RUNTIME_UNINSTALLER }
}

enum class CauseKind { SESSION, TIMER, SCHEDULE, SLEEP, TIME_LIMIT, OPEN_LIMIT }

data class CoveringCause(
    val ruleId: String?,
    val name: String,
    val kind: CauseKind,
    val tier: BreakLevel,
    /** Commitment activity is distinct from a grant temporarily suspending enforcement. */
    val active: Boolean,
    val endsAtWall: Long?,
    val timeUncertain: Boolean,
    val nextChangeAtWall: Long?,
    /** Null is unknown, never an inferred allow. */
    val enforcingNow: Boolean?,
    val causeKey: String,
    val occurrenceId: String?,
)

data class OpenCreditView(
    val used: Int?,
    val cap: Int?,
    val readable: Boolean,
    /** Readable accounting does not prove that the runtime can honor a timed credit. */
    val available: Boolean,
    val unavailableReason: String?,
    val activeCreditEndsAtWall: Long?,
    val periodEndsAtWall: Long?,
)

data class TimeLimitView(
    val quotaMs: Long,
    val usedMs: Long?,
    val tier: BreakLevel,
    val measurable: Boolean,
)
