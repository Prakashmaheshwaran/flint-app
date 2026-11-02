package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.datastore.NATIVE_TIMER_ID_PREFIX
import com.flint.peakfocus.core.datastore.CONTRACT_TIMER_ID_PREFIX
import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.ruleCauseKey
import com.flint.peakfocus.core.datastore.isActiveEditorSession
import com.flint.peakfocus.core.model.AccessClockStamp
import com.flint.peakfocus.blocking.engine.isValidNewSessionDuration

/** Finite duration choices; a zero-duration/infinite session is deliberately unsupported. */
internal val timerDurationChoices: List<Int> = (5..120 step 5).toList() + (3..24).map { it * 60 }

internal data class TimerPreset(
    val id: String,
    val name: String,
    val minutes: Int,
    val detox: Boolean = false,
)

/** Presets set only duration and title. They never select or classify apps for the user. */
internal val timerPresets = listOf(
    TimerPreset("deep-study", "Deep study", 90),
    TimerPreset("commute", "Commute", 30),
    TimerPreset("workout", "Workout", 60),
    TimerPreset("reading", "Reading", 30),
    TimerPreset("weekend-zen", "Weekend zen", 60),
    TimerPreset("touch-grass", "Touch Grass", 480, detox = true),
    TimerPreset("quiet-day", "Quiet Day", 1440, detox = true),
    TimerPreset("digital-sabbath", "Digital Sabbath", 1440, detox = true),
    TimerPreset("retreat", "Retreat", 1440, detox = true),
)

internal data class TimerDraft(
    val apps: Set<AppRef> = emptySet(),
    val durationMinutes: Int = 30,
    val presetId: String? = null,
    val breakLevel: BreakLevel = BreakLevel.EASY,
)

internal data class TimerUiState(
    val draft: TimerDraft = TimerDraft(),
    val appsState: InstalledAppsUiState = InstalledAppsUiState.Loading,
    val appsError: String? = null,
    val rulesLoaded: Boolean = false,
    val rulesError: String? = null,
    val sessions: List<BlockRule> = emptyList(),
    val nowEpochMs: Long = 0,
    val busy: Boolean = false,
    val error: String? = null,
    /** Health refusals follow fresh checks; a failed save remains visible until retried. */
    val startErrorIsPrerequisite: Boolean = false,
    val message: String? = null,
    val exit: TimerExitConfirmation? = null,
    val arming: TimerArmingState = TimerArmingState.Idle,
    /** A start check is not an Armed claim; the transaction and detector still decide that. */
    val startPrerequisite: String? = "Checking protection…",
) {
    val canEdit: Boolean get() = rulesLoaded && rulesError == null && !busy && sessions.isEmpty() && exit == null
    val canStart: Boolean get() = canEdit && validTimerDraft(draft)
}

/** Presentation only: the serialized backend remains the authority for recovery rejection. */
internal fun timerStartFailureText(reason: String): String = if (reason == "Resume protection first.")
    "Protection recovery is active. Try again after recovery ends." else reason

internal fun validTimerDraft(draft: TimerDraft): Boolean =
    draft.apps.isNotEmpty() && draft.apps.all { it.packageName.isNotBlank() } &&
        isValidNewSessionDuration(draft.durationMinutes * 60_000L) &&
        (draft.durationMinutes in timerDurationChoices ||
            timerPresets.any { it.detox && it.minutes == draft.durationMinutes })

/** Snapshot app targets at start; editing a later draft can never alter a running session. */
internal fun timerRule(draft: TimerDraft, nowEpochMs: Long, uniqueId: String): BlockRule {
    require(validTimerDraft(draft)) { "Choose apps and a finite timer duration" }
    require(nowEpochMs >= 0 && nowEpochMs <= Long.MAX_VALUE - draft.durationMinutes * 60_000L)
    require(uniqueId.isNotBlank())
    val preset = timerPresets.firstOrNull {
        it.id == draft.presetId && it.minutes == draft.durationMinutes
    }
    return BlockRule(
        // The ID's start and deadline use the same instant; the UUID supplies no clock.
        id = CONTRACT_TIMER_ID_PREFIX + "$nowEpochMs-$uniqueId",
        name = preset?.name ?: "Focus timer",
        targets = BlockTargets(apps = draft.apps.distinctBy { it.packageName }.toSet()),
        breakLevel = draft.breakLevel,
        expiresAtEpochMs = nowEpochMs + draft.durationMinutes * 60_000L,
    )
}

/** Includes older Home sessions and malformed sessions with no deadline so none are hidden. */
internal fun activeTimerSessions(rules: List<BlockRule>, nowEpochMs: Long): List<BlockRule> =
    rules.filter {
        it.id.startsWith(BlockRule.SESSION_ID_PREFIX) && it.enabled && !it.isExpired(nowEpochMs)
    }.sortedWith(compareBy<BlockRule> { it.expiresAtEpochMs ?: Long.MAX_VALUE }.thenBy { it.id })

/** Stored anchors keep a wall-clock edit from hiding a still-active Timer or extending it. */
internal fun anchoredTimerSessions(rules: List<BlockRule>, editor: EditorCommitmentState?,
                                   now: AccessClockStamp, wall: Long): List<BlockRule> =
    rules.filter { isActiveEditorSession(it, editor?.causes?.get(ruleCauseKey(it.id)), now) }.map { rule ->
        val cause = editor?.causes?.get(ruleCauseKey(rule.id))
        val interval = cause?.interval
        if (interval != null) interval.remainingMs(now).let { remaining ->
            rule.copy(expiresAtEpochMs =
                if (wall > Long.MAX_VALUE - remaining) Long.MAX_VALUE else wall + remaining)
        } else rule
    }.sortedWith(compareBy<BlockRule> { it.expiresAtEpochMs ?: Long.MAX_VALUE }.thenBy { it.id })

/**
 * When a native timer started, read back from its id ("session-timer-<epochMs>-<uuid>", as the
 * production factory mints them). Older ids carry no instant and return null.
 */
internal fun timerStartEpochMs(rule: BlockRule): Long? {
    if (!rule.id.startsWith(NATIVE_TIMER_ID_PREFIX)) return null
    val start = rule.id.removePrefix(if (rule.id.startsWith(CONTRACT_TIMER_ID_PREFIX)) CONTRACT_TIMER_ID_PREFIX else NATIVE_TIMER_ID_PREFIX)
        .substringBefore('-').toLongOrNull() ?: return null
    val deadline = rule.expiresAtEpochMs ?: return null
    return start.takeIf { it > EARLIEST_TIMER_EPOCH_MS && it < deadline }
}

/** 2017-07-14 — anything earlier is a UUID fragment that happened to be all digits. */
private const val EARLIEST_TIMER_EPOCH_MS = 1_500_000_000_000L

internal fun canCancelTimer(rule: BlockRule): Boolean =
    rule.id.startsWith(BlockRule.SESSION_ID_PREFIX) &&
        !rule.id.startsWith(NATIVE_TIMER_ID_PREFIX) && rule.breakLevel == BreakLevel.EASY

internal fun canEndNativeTimer(rule: BlockRule): Boolean =
    rule.id.startsWith(NATIVE_TIMER_ID_PREFIX) && rule.id.length > NATIVE_TIMER_ID_PREFIX.length &&
        rule.enabled && rule.schedule == null &&
        (!rule.id.startsWith(CONTRACT_TIMER_ID_PREFIX) || rule.breakLevel != BreakLevel.HARDCORE)

internal enum class TimerExitPhase { WAITING, IDLE, HOLDING, FINISHING, SETTLING, SAVING }

internal data class TimerExitConfirmation(
    val expected: BlockRule,
    val phase: TimerExitPhase = TimerExitPhase.IDLE,
    val progress: Float = 0f,
    val operationId: Long? = null,
    val waitRemainingMs: Long? = null,
) {
    val canDismiss: Boolean get() = phase in setOf(TimerExitPhase.WAITING, TimerExitPhase.IDLE, TimerExitPhase.HOLDING)
}

internal const val TIMER_EXIT_HOLD_MS = 2_000L
internal const val TIMER_EXIT_COLLAPSE_MS = 300
internal const val TIMER_EXIT_FINISH_MS = 400L
internal const val TIMER_EXIT_CALLBACK_MS = 400L

internal fun timerDurationLabel(minutes: Int): String = when {
    minutes % 1440 == 0 -> "${minutes / 1440}d"
    minutes % 60 == 0 -> "${minutes / 60}h"
    minutes < 60 -> "${minutes}m"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

/** Round up partial seconds so a live session never displays 00:00 before its deadline. */
internal fun timerCountdown(remainingMs: Long): String {
    val positive = remainingMs.coerceAtLeast(0)
    val seconds = positive / 1000 + if (positive % 1000 == 0L) 0 else 1
    val days = seconds / 86_400
    val hours = seconds / 3600 % 24
    val minutes = seconds / 60 % 60
    val tail = "${minutes.toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    return when {
        days > 0 -> "${days}d ${hours.toString().padStart(2, '0')}:$tail"
        hours > 0 -> "${hours.toString().padStart(2, '0')}:$tail"
        else -> tail
    }
}
