package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BlockRule

/**
 * Exact pre-v6 rule text for a later checked pending-wait migration. This renders data,
 * never replaces substrings in user labels and never itself proves a pending scope current.
 * The limits renderer must match the final merged v5 model when that lease opens.
 */
internal fun preOneShotEditorScope(change: EditorChange): String =
    "EditorChange(label=${change.label}, upserts=${change.upserts.joinToString(prefix = "[", postfix = "]", transform = ::preOneShotRuleText)}, " +
        "deleteIds=${change.deleteIds}, limits=${change.limits}, timerExit=${change.timerExit})"

private fun preOneShotRuleText(rule: BlockRule): String =
    "BlockRule(id=${rule.id}, name=${rule.name}, targets=${rule.targets}, schedule=${rule.schedule}, " +
        "breakLevel=${rule.breakLevel}, enabled=${rule.enabled}, expiresAtEpochMs=${rule.expiresAtEpochMs}, runtime=${rule.runtime})"
