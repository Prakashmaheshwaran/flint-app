package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BreakLevel
import java.time.ZoneId

/** Frozen migration input/output, independent of additions to the current TimeLimit model. */
internal data class AdoptedTimeLimit(
    val packageName: String,
    val dailyMinutes: Int,
    val breakLevel: BreakLevel,
    val zoneId: String,
)

internal data class LegacyTimeLimitAdoption(
    val limits: List<AdoptedTimeLimit>,
    val newTargets: Set<String>,
    /** Includes a duplicate whose effective values already agree; the archive records its provenance. */
    val mergedTargets: Set<String>,
    val defaultTierNeedsReview: Boolean,
)

/**
 * Editor-v5 ADOPT-then-RETIRE plan. Call only with a validated legacy snapshot, after v3/v4
 * preparation. The caller archives the original values and applies this plan in the SAME
 * commit as v5; this pure function does not publish that marker or retire any reader.
 *
 * The old runtime enforced min(canonical, legacy). Preserve that budget and the stricter
 * tier, with no new default-zone lookup. Every output will be enabled on first adoption.
 * Existing usage, reset periods and current-occurrence rights are separate retained state:
 * this plan grants no authority to reset or replace any of them.
 */
internal fun planLegacyTimeLimitAdoption(
    canonical: List<AdoptedTimeLimit>,
    legacyMinutes: Map<String, Int>,
    rawDefaultTier: String?,
    capturedZones: Map<String, String>,
): LegacyTimeLimitAdoption {
    fun validate(row: AdoptedTimeLimit) {
        wireRequire(row.packageName.isNotBlank() && row.packageName.length <= 262_144 && row.dailyMinutes >= 0)
        wireRequire(runCatching { ZoneId.of(row.zoneId) }.isSuccess)
    }
    canonical.forEach(::validate)
    wireRequire(canonical.map { it.packageName }.distinct().size == canonical.size)
    val existing = canonical.associateBy { it.packageName }
    val addedTargets = legacyMinutes.keys - existing.keys
    wireRequire(existing.size + addedTargets.size <= MAX_EDITOR_CAUSES)
    if (legacyMinutes.isEmpty()) return LegacyTimeLimitAdoption(canonical, emptySet(), emptySet(), false)

    // Main's documented absent default was Easy. Only absence gets that fallback; an
    // unrecognized value cannot turn a stricter legacy policy into Easy.
    val tier = if (rawDefaultTier == null) BreakLevel.EASY
        else BreakLevel.entries.singleOrNull { it.name == rawDefaultTier } ?: wireCorrupt()
    val adopted = legacyMinutes.mapValues { (pkg, minutes) ->
        val before = existing[pkg]
        val zone = before?.zoneId ?: capturedZones[pkg] ?: wireCorrupt()
        AdoptedTimeLimit(pkg, if (before == null) minutes else minOf(before.dailyMinutes, minutes),
            if (before == null || tier.ordinal > before.breakLevel.ordinal) tier else before.breakLevel, zone)
            .also { wireRequire(minutes >= 0); validate(it) }
    }
    val result = canonical.map { adopted[it.packageName] ?: it } + addedTargets.sorted().map(adopted::getValue)
    return LegacyTimeLimitAdoption(result, addedTargets, existing.keys.intersect(legacyMinutes.keys), rawDefaultTier == null)
}
