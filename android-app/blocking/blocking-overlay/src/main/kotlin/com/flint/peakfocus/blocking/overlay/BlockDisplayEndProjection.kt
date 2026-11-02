package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.blocking.engine.BlockDecisionEngine
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.SafetyFloor
import com.flint.peakfocus.core.model.Verdict
import java.time.Instant
import java.time.ZoneOffset

/** Read-only render projection. No clock reads, store access, role resolution or mutations. */
internal object BlockDisplayEndProjection {
    private val engine = BlockDecisionEngine()

    fun forTarget(
        packageName: String,
        foregroundUrl: String?,
        rules: List<BlockRule>,
        selfPackage: String,
        selected: BlockCause,
        now: Long,
        utcOffsetMs: Long,
    ): BlockCause {
        // Enforcement checks the runtime floor before mounting; retain the independent
        // static floor here too, without any role/binder work on the shield's render thread.
        if (SafetyFloor.isProtected(packageName, selfPackage)) return selected.copy(displayEnd = BlockDisplayEnd(null))
        val local = Instant.ofEpochMilli(now).atOffset(ZoneOffset.ofTotalSeconds((utcOffsetMs / 1_000).toInt()))
        val minute = local.hour * 60 + local.minute
        val contributors = rules.mapNotNull { rule ->
            if (engine.decide(packageName, foregroundUrl, listOf(rule), selfPackage, minute,
                    local.dayOfWeek.value, now) is Verdict.Block) ruleBlockCause(rule, now, utcOffsetMs) else null
        }
        return combine(selected, listOf(selected) + contributors, now)
    }

    fun combine(selected: BlockCause, contributors: List<BlockCause>, now: Long): BlockCause {
        val rows = contributors.mapIndexed { index, cause ->
            val end = if (cause.displayEnd != null) cause.displayEnd.endsAtEpochMs else cause.endsAtEpochMs
            CoveringCause(cause.ruleId, "", CauseKind.SESSION, cause.breakLevel, true, end,
                false, end, true, "block:$index", null)
        }
        // Reuse the reader's all-contributor/unknown-end reduction, independently of the
        // authority chosen by BlockOverlapPolicy. Rule factories use RuleWindowEnd (R-18).
        val end = TargetProtectionReduction.resolve(Readiness.Ready, null, rows, now).blockingUntilWall
        return selected.copy(displayEnd = BlockDisplayEnd(end).takeIf { end != selected.endsAtEpochMs })
    }
}
