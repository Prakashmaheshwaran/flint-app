package com.flint.peakfocus.core.datastore

/**
 * Exact editor-v3/v4 scope, before Time/Open models gained enabled. The zone is supplied
 * from the decoded pre-v5 Time draft, including its legitimate null/uncommitted value.
 * Never remove text from the complete scope: labels and rule names can imitate model text.
 *
 * Rendering alone grants no authority. The serialized v5 migration must prove the old
 * expected records, decision and both pending operations agree before rebinding them;
 * otherwise cancel only the unprovable request and retain spent Harder counts.
 */
internal fun preEnabledLimitEditorScope(change: EditorChange, timeZoneId: String?): String {
    val limits = change.limits
    require(limits?.time != null || timeZoneId == null)
    if (limits == null) return change.toString()
    val time = limits.time?.let {
        "TimeLimit(packageName=${it.packageName}, dailyMinutes=${it.dailyMinutes}, breakLevel=${it.breakLevel}, zoneId=$timeZoneId)"
    }
    val open = limits.open?.let {
        "OpenLimit(packageName=${it.packageName}, dailyOpens=${it.dailyOpens}, breakLevel=${it.breakLevel})"
    }
    val oldLimits = "EditorLimitsChange(packageName=${limits.packageName}, time=$time, open=$open)"
    return "EditorChange(label=${change.label}, upserts=${change.upserts}, deleteIds=${change.deleteIds}, limits=$oldLimits, timerExit=${change.timerExit})"
}
