package com.flint.peakfocus.feature.blocklist

import android.content.Context
import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import com.flint.peakfocus.core.datastore.EditorSafety
import com.flint.peakfocus.core.model.SafetyFloor

/** Fresh role resolution on IO immediately before saving/arming, shared by both editors. */
internal fun loadEditorSafety(context: Context): EditorSafety {
    val sources = RuntimeSafetySurfaces.resolveBySource(context)
    val floor = sources.values.flatMapTo(mutableSetOf()) { it.orEmpty() }
    return EditorSafety(sources.isNotEmpty() && sources.values.all { it != null },
        floor + SafetyFloor.STATIC_PACKAGES + context.packageName)
}
