package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.provider.Settings
import java.util.UUID

/** Sample once at coordinator creation, never on a foreground-event/counter hot path. */
internal object BootIdentity {
    private val processFallback = "process:${UUID.randomUUID()}"
    @Volatile private var cached: String? = null

    fun current(context: Context): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: (runCatching {
                Settings.Global.getString(context.contentResolver, "boot_count")
                    ?.toLongOrNull()?.takeIf { it >= 0 }?.let { "boot:$it" }
            }.getOrNull() ?: processFallback).also { cached = it }
        }
    }
}
