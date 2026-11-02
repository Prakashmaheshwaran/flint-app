package com.flint.peakfocus.core.datastore

/**
 * Exact pre-saved-zone scope for a decoded v1/v2 editor operation. Never strip substrings
 * from arbitrary scope text: labels and rule names can contain the same TimeLimit spelling.
 *
 * This only reconstructs the old representation. The marker-v3 migration must separately
 * prove current expected records/outcome and matching pending ledger/editor operations in
 * its serialized edit before replacing either scope; reconstruction alone grants no rights.
 */
internal fun legacyTimeLimitEditorScope(change: EditorChange): String {
    val limits = change.limits ?: return change.toString()
    val time = limits.time ?: return change.toString()
    require(time.zoneId == null) { "An explicit saved zone was not part of the legacy scope" }
    val oldTime = "TimeLimit(packageName=${time.packageName}, dailyMinutes=${time.dailyMinutes}, breakLevel=${time.breakLevel})"
    val oldLimits = "EditorLimitsChange(packageName=${limits.packageName}, time=$oldTime, open=${limits.open})"
    return "EditorChange(label=${change.label}, upserts=${change.upserts}, deleteIds=${change.deleteIds}, limits=$oldLimits, timerExit=${change.timerExit})"
}
