package com.flint.peakfocus.core.datastore

import android.os.SystemClock
import com.flint.peakfocus.core.model.AccessClockStamp
import java.io.File
import java.util.UUID

/** A stable OS boot identity when available; otherwise pending waits conservatively cancel. */
object ProtectionClock {
    private val bootId: String by lazy {
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotBlank() } }.getOrNull()
            ?: "process-${UUID.randomUUID()}"
    }
    fun now() = AccessClockStamp(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId)
}
