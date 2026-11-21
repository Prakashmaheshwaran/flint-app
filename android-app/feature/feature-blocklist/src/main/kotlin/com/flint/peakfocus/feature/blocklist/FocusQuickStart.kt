package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.blocking.overlay.ProtectionStatus

/**
 * An in-app request to open Today's Start focus sheet (3.2 quick-start surfaces).
 *
 * It carries identifiers only: never package names, durations or break levels. [groupId]
 * names one of the user's own saved App Groups; Flint resolves it against its local store
 * and pre-fills the draft. Nothing is saved or started here: Begin in the sheet stays the
 * only commit, through the same editor transaction and refusal checks as a manual start.
 */
data class FocusQuickStart(
    /** Unique per request so a repeated tap is seen as a new request. */
    val id: Long,
    val groupId: String? = null,
)

/** A saved group's name as one short display line; user text, but never multi-line. */
internal fun quickStartGroupName(raw: String): String =
    ProtectionStatus.sanitizeDisplayName(raw).take(40).ifBlank { "Saved group" }
