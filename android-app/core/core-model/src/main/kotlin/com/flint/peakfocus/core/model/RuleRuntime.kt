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
    /** Whether this rule's *own* restrictions enforce now. False during a planned rest. */
    val active: Boolean,
    /** Commitment end (a Pomodoro parent's chain end), or null when there is none. */
    val endsAtEpochMs: Long?,
    val nextBoundaryEpochMs: Long?,
    /**
     * F01: the Pomodoro phase this read falls in, or null once the chain finished. A non-null
     * phase with `active == false` is a planned rest: the parent is still an active commitment,
     * so liveness/terminal readers must not treat it as an end.
     */
    val phase: PomodoroPhase? = null,
)
