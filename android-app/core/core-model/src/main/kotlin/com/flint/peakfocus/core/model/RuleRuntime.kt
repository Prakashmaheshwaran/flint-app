package com.flint.peakfocus.core.model

/**
 * Runtime-only view of the durable editor occurrence, published with its raw rule template.
 * Codecs deliberately do not persist this field: the editor sidecar owns these anchors.
 * A null remaining duration means an open-ended occurrence; "inactive" is a tombstone.
 */
data class RuleRuntime(
    val occurrenceId: String,
    val anchor: AccessClockStamp,
    val remainingAtAnchorMs: Long?,
    val wallEndEpochMs: Long?,
    val zoneId: String,
    val timeline: AccessClockStamp,
    /** A queued weakening must commit before this template can enter another occurrence. */
    val stopAtOccurrenceEnd: Boolean = false,
    val evaluation: RuleEvaluation? = null,
)

/** One clock sample shared by engine decisions, boundary timers and display-end readers. */
data class RuleEvaluation(
    val active: Boolean,
    val endsAtEpochMs: Long?,
    val nextBoundaryEpochMs: Long?,
)
