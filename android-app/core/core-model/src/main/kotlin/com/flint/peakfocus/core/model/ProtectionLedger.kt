package com.flint.peakfocus.core.model

/** ADR-012 pass and lifecycle desired state. Open credits retain their separate shared ledger. */
data class ProtectionLedger(
    val initializedAt: AccessClockStamp,
    val checkpoint: AccessClockStamp,
    /** Explicit absence means validated unused/elapsed state, never a missing storage key. */
    val cooldown: ProtectionCooldown? = null,
    val recovery: ProtectionRecovery? = null,
    val operations: Map<String, ProtectionPassReceipt> = emptyMap(),
    /** At-least-once delivery until a durable consumer acknowledges each stable event ID. */
    val outbox: Map<String, ProtectionLifecycleEvent> = emptyMap(),
    /** Explicit evidence that bounded retention discarded undelivered history, never silent. */
    val droppedEvents: Long = 0,
    val previousAccessEnded: Boolean = false,
    /** Retained after cooldown expiry so required legacy accounting cannot disappear silently. */
    val lastUsedAtEpochMs: Long? = null,
    val ordinaryGrants: Map<String, ProtectionOrdinaryGrant> = emptyMap(),
    /** Event bookkeeping only. The shared Open ledger alone authorizes credit access. */
    val pendingCreditEnds: Map<String, ProtectionCreditEventReceipt> = emptyMap(),
)

data class ProtectionCreditEventReceipt(val operationId: String, val target: String, val lifetime: AccessDuration)

/** Frozen target and contributing occurrences. New/changed contributors never inherit access. */
data class ProtectionOrdinaryGrant(
    val operationId: String,
    val target: AccessTarget,
    val lifetime: AccessDuration,
    val scopes: Set<AccessCauseScope>,
    val affectedRuleIds: Set<String>,
)

data class ProtectionCooldown(
    val operationId: String,
    val usedAtWallEpochMs: Long,
    val checkpoint: AccessClockStamp,
    val remainingAtCheckpointMs: Long,
    val wallUncertain: Boolean = false,
)

data class ProtectionRecovery(
    val operationId: String,
    val startedAt: AccessClockStamp,
    val affectedCauseKeys: Set<String>,
    val affectedRuleIds: Set<String>,
    val endedTransientIds: Set<String>,
    val durationMs: Long,
)

data class ProtectionPassReceipt(
    val operationId: String,
    val issuedAt: AccessClockStamp,
    val acceptedAt: AccessClockStamp,
    val scopeIdentity: String,
)

enum class ProtectionLifecycleKind { PASS_COMMITTED, BREAK_STARTED, BREAK_ENDED, ACCESS_STARTED, ACCESS_ENDED }
enum class ProtectionBreakEndReason { EXPIRED, RESUMED, SUPERSEDED, RECOVERY }

/**
 * No package/domain/target identifiers cross the history boundary. Consumers deliberately
 * acknowledge unhandled additive kinds as a no-op; they never reinterpret access as a break.
 */
data class ProtectionLifecycleEvent(
    val id: String,
    val operationId: String,
    val kind: ProtectionLifecycleKind,
    val affectedRuleIds: Set<String>,
    val at: AccessClockStamp,
    val grantId: String? = null,
    val untilEpochMs: Long? = null,
    val breakEndReason: ProtectionBreakEndReason? = null,
    /** Only these transient native session identities ended early; templates merely suspend. */
    val endedTransientIds: Set<String> = emptySet(),
    val revision: Long = 0,
    val timeUncertain: Boolean = false,
)
