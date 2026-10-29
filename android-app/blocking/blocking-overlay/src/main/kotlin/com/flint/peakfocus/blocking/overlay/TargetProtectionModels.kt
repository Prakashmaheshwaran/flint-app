package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.BreakLevel

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
