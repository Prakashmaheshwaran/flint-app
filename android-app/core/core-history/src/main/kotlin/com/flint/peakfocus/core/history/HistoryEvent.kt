package com.flint.peakfocus.core.history

import java.util.UUID

/** The only mapping between lifecycle names and wire names. Unknown names remain in type. */
enum class HistoryEventType(val wireName: String, val terminal: Boolean = false) {
    STARTED("started"), RESCHEDULED("rescheduled"), COMPLETED("completed", true), ENDED_EARLY("ended_early", true),
    SUPERSEDED("superseded", true), BREAK_STARTED("break_started"), BREAK_ENDED("break_ended"),
    PASS_USED("pass_used"), FAILED_TO_ARM("failed_to_arm"), OPEN_INTERCEPTED("open_intercepted"),
    PROTECTION_DEGRADED("protection_degraded"), PROTECTION_RESTORED("protection_restored"),
    ACCESS_STARTED("access_started"), ACCESS_ENDED("access_ended");

    companion object {
        fun fromWire(name: String): HistoryEventType? = entries.firstOrNull { it.wireName == name }
    }
}

/** Confidence in effective logical time, independent of observation/delivery time. */
sealed interface HistoryEventTimeConfidence {
    data object Certain : HistoryEventTimeConfidence
    data class Uncertain(val reason: String) : HistoryEventTimeConfidence
}

data class HistoryEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val session: String,
    val at: Long,
    val tz: String,
    val v: Int = 1,
    val inferred: Boolean = false,
    val plannedEnd: Long? = null,
    val tier: String? = null,
    val source: String? = null,
    val name: String? = null,
    val route: String? = null,
    val by: String? = null,
    val breakEnd: Long? = null,
    val reason: String? = null,
    val originRoute: String? = null,
    val observedAt: Long? = null,
    val kind: String? = null,
    val grant: String? = null,
    val sessions: List<String>? = null,
    val recoveryEnd: Long? = null,
    /** Original line retains future fields byte-for-byte through compaction. */
    val originalLine: String? = null,
    /** A resource-bounded envelope read preserves unsupported payloads without aggregating them. */
    val opaque: Boolean = false,
    /** Absent in legacy records; inferred alone does not imply uncertain time. */
    val timeConfidence: HistoryEventTimeConfidence? = null,
) {
    val knownType: HistoryEventType? get() = if (v == 1 && !opaque) HistoryEventType.fromWire(type) else null

    /** A disappearing rule is provisional evidence, below an explicit owner/app end. */
    internal val observationTerminal: Boolean
        get() = knownType == HistoryEventType.ENDED_EARLY && route == "unknown"
}

data class HistorySnapshot(
    val events: List<HistoryEvent> = emptyList(),
    val historyStart: Long = 0,
    val readable: Boolean = false,
    val degraded: Boolean = false,
    val diagnostic: String? = null,
    /** Existing persisted clear cutoff; durable consumers inspect it under update's lock. */
    val clearedAt: Long? = null,
)
