package com.flint.peakfocus.core.model

enum class ExemptionKind { BREAK, EMERGENCY_PASS, OPEN_LIMIT }

/** A durable receipt also makes retries after a lost acknowledgement idempotent. */
data class PersistedExemption(
    val requestId: String,
    val packageName: String,
    val causeKey: String,
    val kind: ExemptionKind,
    val untilEpochMs: Long,
)

/**
 * Current v1 policy state plus additive durable receipts. "*" is a global package scope.
 * Keep usedAtEpochMs independently of the ISO-week key for future rolling-window policy.
 * Per-cause waits are representable without changing today's global wait semantics.
 */
data class FocusSnapshot(
    val defaultBreakLevel: BreakLevel = BreakLevel.EASY,
    val emergencyPass: EmergencyPassState = EmergencyPassState(),
    val breakSession: BreakSessionState = BreakSessionState(),
    val openCounts: OpenCountState = OpenCountState(),
    val exemptions: Map<String, PersistedExemption> = emptyMap(),
    val breaksByCause: Map<String, BreakSessionState> = emptyMap(),
)

data class FocusMutation<R>(val next: FocusSnapshot, val result: R)
