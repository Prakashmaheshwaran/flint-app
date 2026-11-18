package com.flint.peakfocus.core.model

/**
 * What one phase of a Pomodoro chain is (§2 of `docs/architecture/pomodoro-chain.md`).
 *
 * A rest belongs to the focus round it follows: rest `Rk` runs after focus round `k`.
 */
enum class PomodoroPhaseKind { FOCUS, REST, LONG_REST }

/**
 * One phase of a frozen [PomodoroPlan], as a half-open interval `[startMs, endMs)` of the
 * chain's trusted logical *elapsed* time.
 */
data class PomodoroPhase(
    /** Zero-based position in the chain, ordered `F1, R1, F2, …, F(rounds)`. */
    val index: Int,
    val kind: PomodoroPhaseKind,
    /** 1-based focus round; for a rest, the round it follows. */
    val round: Int,
    val startMs: Long,
    val endMs: Long,
) {
    val isFocus: Boolean get() = kind == PomodoroPhaseKind.FOCUS
    val durationMs: Long get() = endMs - startMs
}

/**
 * The frozen phase plan of one Pomodoro parent commitment (ADR-012 §1/§4, F01).
 *
 * The plan is accepted once, with the parent, and never edited afterwards: there is no skip,
 * extend or shorten operation. The current phase is a pure function of the parent's trusted
 * logical elapsed time ([phaseAt]), never of wall time, so a same-boot clock edit cannot move a
 * boundary and a reboot-bounded re-anchor can only move the chain forward (§9).
 *
 * Equality is value equality over the four/five numbers below, which is what the freeze
 * comparison in `CommitmentPolicy.ProtectionShape` relies on.
 */
data class PomodoroPlan(
    /** 5–120, in steps of 5. */
    val focusMinutes: Int,
    /** 1–60. */
    val restMinutes: Int,
    /** 2–8. A single round is an ordinary Timer. */
    val rounds: Int,
    /** Optional long rest length, 5–60 and at least [restMinutes]; set with [longRestEvery] or neither. */
    val longRestMinutes: Int? = null,
    /** Rest after every [longRestEvery]-th focus round; 2 … [rounds] − 1. */
    val longRestEvery: Int? = null,
) {
    init {
        require(nameForError() == null) { nameForError()!! }
    }

    /** Why this plan is not acceptable, or null when it is. Never throws on user input. */
    private fun nameForError(): String? = when {
        focusMinutes !in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES -> "Choose a focus length of 5–120 minutes."
        focusMinutes % FOCUS_STEP_MINUTES != 0 -> "Choose a focus length in steps of 5 minutes."
        restMinutes !in MIN_REST_MINUTES..MAX_REST_MINUTES -> "Choose a rest of 1–60 minutes."
        rounds !in MIN_ROUNDS..MAX_ROUNDS -> "Choose 2–8 rounds."
        (longRestMinutes == null) != (longRestEvery == null) -> "Set both long-rest options or neither."
        longRestMinutes != null && longRestMinutes !in MIN_LONG_REST_MINUTES..MAX_LONG_REST_MINUTES ->
            "Choose a long rest of 5–60 minutes."
        longRestMinutes != null && longRestMinutes < restMinutes -> "A long rest must be at least as long as a rest."
        longRestEvery != null && longRestEvery !in 2 until rounds ->
            "Choose a long rest every 2–${rounds - 1} rounds."
        else -> null
    }

    /** The ordered phases: `F1, R1, F2, R2, …, F(rounds)`. */
    val phases: List<PomodoroPhase> by lazy {
        val result = ArrayList<PomodoroPhase>(2 * rounds - 1)
        var cursor = 0L
        for (round in 1..rounds) {
            val focusEnd = cursor + focusMinutes * 60_000L
            result += PomodoroPhase(result.size, PomodoroPhaseKind.FOCUS, round, cursor, focusEnd)
            cursor = focusEnd
            if (round == rounds) break
            val long = longRestEvery?.let { round % it == 0 } == true
            val restEnd = cursor + (if (long) longRestMinutes!! else restMinutes) * 60_000L
            result += PomodoroPhase(result.size,
                if (long) PomodoroPhaseKind.LONG_REST else PomodoroPhaseKind.REST, round, cursor, restEnd)
            cursor = restEnd
        }
        result
    }

    /** Whole committed duration, from the first focus round to the end of the last one. */
    val totalMs: Long get() = phases.last().endMs

    /** Number of planned rests; one after every round except the last. */
    val rests: Int get() = rounds - 1

    /**
     * The phase containing [elapsedMs] of trusted logical elapsed time, or null once the chain
     * has finished (`elapsed >= totalMs`). Negative input is clamped to 0.
     */
    fun phaseAt(elapsedMs: Long): PomodoroPhase? {
        val elapsed = elapsedMs.coerceAtLeast(0L)
        if (elapsed >= totalMs) return null
        return phases.firstOrNull { elapsed >= it.startMs && elapsed < it.endMs }
    }

    /** The planned rest after [round] (1-based), or null when that round has no rest. */
    fun restAfterRound(round: Int): PomodoroPhase? = phases.firstOrNull {
        !it.isFocus && it.round == round
    }

    companion object {
        const val MIN_FOCUS_MINUTES = 5
        const val MAX_FOCUS_MINUTES = 120
        const val FOCUS_STEP_MINUTES = 5
        const val MIN_REST_MINUTES = 1
        const val MAX_REST_MINUTES = 60
        const val MIN_ROUNDS = 2
        const val MAX_ROUNDS = 8
        const val MIN_LONG_REST_MINUTES = 5
        const val MAX_LONG_REST_MINUTES = 60

        /** Design default: 25/5 × 4. */
        val DEFAULT = PomodoroPlan(focusMinutes = 25, restMinutes = 5, rounds = 4)

        /** Why [plan] would be refused, or null when it is a valid plan. Never throws. */
        fun validationError(plan: PomodoroPlan): String? = plan.nameForError()

        /** All focus-length choices in minutes, in steps of 5. */
        val focusChoices: List<Int> =
            (MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES step FOCUS_STEP_MINUTES).toList()

        /** All round-count choices. */
        val roundChoices: List<Int> = (MIN_ROUNDS..MAX_ROUNDS).toList()
    }
}
