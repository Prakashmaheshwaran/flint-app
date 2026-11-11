package com.flint.peakfocus.feature.settings.applock

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Durable storage for the app-open PIN record and its throttle state.
 *
 * Every write is synchronous and durable and reports failure instead of throwing: a caller that
 * cannot persist a failed-attempt counter or the PIN record must be able to fail closed.
 * Implementations never delete the record on a read error (ADR-012 §12).
 */
interface AppLockStorage {

    /** Raw encoded record, or null when no PIN has ever been saved. */
    fun readRecordRaw(): String?

    /** Persists [raw] as the current record. Returns false when the write did not land. */
    fun writeRecordRaw(raw: String): Boolean

    /** Removes the record. Returns false when the removal did not land. */
    fun clearRecord(): Boolean

    fun readThrottle(): ThrottleState

    fun writeThrottle(state: ThrottleState): Boolean
}

/** Elapsed-realtime clock plus a boot identity; wall-clock time is never used for throttling. */
interface AppLockClock {
    fun elapsedRealtimeMs(): Long
    fun bootId(): String
}

/**
 * Real clock: [SystemClock.elapsedRealtime] and the kernel boot counter.
 *
 * `Settings.Global.BOOT_COUNT` is API 24+, so API 23 (and any read failure) falls back to a
 * wall-clock-derived boot label. A manual clock change can therefore look like a reboot, which
 * restarts — and so only lengthens — a lockout; it can never shorten one.
 */
class SystemAppLockClock(context: Context) : AppLockClock {

    private val contentResolver = context.applicationContext.contentResolver

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun bootId(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bootCount = runCatching {
                Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
            }.getOrNull()
            if (bootCount != null && bootCount > 0) return bootCount.toString()
        }
        return WALL_BOOT_PREFIX + ((System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000L)
    }

    private companion object {
        const val WALL_BOOT_PREFIX = "wall-"
    }
}

/**
 * SharedPreferences-backed storage. `commit()` is used everywhere because [AppLockStorage]
 * promises durability: the caller must know whether a counter or record write actually landed.
 */
class SharedPreferencesAppLockStorage(context: Context) : AppLockStorage {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readRecordRaw(): String? {
        val values = runCatching { preferences.all }.getOrNull() ?: return UNREADABLE_RECORD
        if (!values.containsKey(KEY_RECORD)) return null
        // A wrong-typed value must never look like "no PIN" (fail open) and must not be deleted.
        return values[KEY_RECORD] as? String ?: UNREADABLE_RECORD
    }

    override fun writeRecordRaw(raw: String): Boolean = runCatching {
        preferences.edit().putString(KEY_RECORD, raw).commit()
    }.getOrDefault(false)

    override fun clearRecord(): Boolean = runCatching {
        preferences.edit().remove(KEY_RECORD).commit()
    }.getOrDefault(false)

    override fun readThrottle(): ThrottleState {
        val values = runCatching { preferences.all }.getOrNull() ?: return failClosedThrottle(null)
        if (THROTTLE_KEYS.none { values.containsKey(it) }) return ThrottleState()

        val failures = when (val raw = values[KEY_FAILURES]) {
            is Int -> raw.takeIf { it >= 0 } ?: return failClosedThrottle(values)
            else -> return failClosedThrottle(values)
        }
        val start = when (val raw = values[KEY_LOCKOUT_START]) {
            is Long -> raw.takeIf { it >= 0 } ?: return failClosedThrottle(values)
            null -> 0L
            else -> return failClosedThrottle(values)
        }
        val millis = when (val raw = values[KEY_LOCKOUT_MS]) {
            is Long -> raw.takeIf { it >= 0 } ?: return failClosedThrottle(values)
            null -> 0L
            else -> return failClosedThrottle(values)
        }
        val boot = when (val raw = values[KEY_LOCKOUT_BOOT]) {
            is String -> raw
            null -> null
            else -> return failClosedThrottle(values)
        }
        return ThrottleState(failures, start, millis, boot)
    }

    override fun writeThrottle(state: ThrottleState): Boolean = runCatching {
        preferences.edit()
            .putInt(KEY_FAILURES, state.failures)
            .putLong(KEY_LOCKOUT_START, state.lockoutStartElapsedMs)
            .putLong(KEY_LOCKOUT_MS, state.lockoutMillis)
            .putString(KEY_LOCKOUT_BOOT, state.lockoutBootId)
            .commit()
    }.getOrDefault(false)

    /** Corrupt throttle values become a maximal lockout; a readable failure count is preserved. */
    private fun failClosedThrottle(values: Map<String, *>?): ThrottleState {
        val failures = (values?.get(KEY_FAILURES) as? Int)?.takeIf { it >= 0 } ?: FAIL_CLOSED_FAILURES
        val start = (values?.get(KEY_LOCKOUT_START) as? Long)?.takeIf { it >= 0 } ?: 0L
        return ThrottleState(
            failures = failures,
            lockoutStartElapsedMs = start,
            lockoutMillis = AppPinThrottle.MAX_LOCKOUT_MILLIS,
            lockoutBootId = values?.get(KEY_LOCKOUT_BOOT) as? String,
        )
    }

    companion object {
        const val PREFS_NAME = "flint_app_lock"
        const val KEY_RECORD = "pin_record"
        const val KEY_FAILURES = "pin_failures"
        const val KEY_LOCKOUT_START = "pin_lockout_start_elapsed"
        const val KEY_LOCKOUT_MS = "pin_lockout_ms"
        const val KEY_LOCKOUT_BOOT = "pin_lockout_boot"

        private val THROTTLE_KEYS =
            listOf(KEY_FAILURES, KEY_LOCKOUT_START, KEY_LOCKOUT_MS, KEY_LOCKOUT_BOOT)

        /** Matches the escalation table's ceiling so corrupt state cannot shorten a lockout. */
        private const val FAIL_CLOSED_FAILURES = 11

        /** Decodes to [AppPinRecordRead.Unreadable]; never written by this class. */
        private const val UNREADABLE_RECORD = "!unreadable"
    }
}

/**
 * In-memory storage for tests and previews.
 *
 * [recordRaw] and [throttle] are exposed so a test can inspect or seed state; [failRecordWrites],
 * [failClear] and [failThrottleWrites] simulate a storage failure on the next write.
 */
class InMemoryAppLockStorage(
    recordRaw: String? = null,
    throttle: ThrottleState = ThrottleState(),
) : AppLockStorage {

    @Volatile
    var recordRaw: String? = recordRaw

    @Volatile
    var throttle: ThrottleState = throttle

    @Volatile
    var failRecordWrites: Boolean = false

    @Volatile
    var failClear: Boolean = false

    @Volatile
    var failThrottleWrites: Boolean = false

    override fun readRecordRaw(): String? = recordRaw

    override fun writeRecordRaw(raw: String): Boolean {
        if (failRecordWrites) return false
        recordRaw = raw
        return true
    }

    override fun clearRecord(): Boolean {
        if (failClear) return false
        recordRaw = null
        return true
    }

    override fun readThrottle(): ThrottleState = throttle

    override fun writeThrottle(state: ThrottleState): Boolean {
        if (failThrottleWrites) return false
        throttle = state
        return true
    }
}
