package com.flint.peakfocus.feature.settings.applock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SharedPreferences durability, fail-closed reads and the process-wide controller. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppLockStorageRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val preferences
        get() = context.getSharedPreferences(SharedPreferencesAppLockStorage.PREFS_NAME, Context.MODE_PRIVATE)

    private val storage get() = SharedPreferencesAppLockStorage(context)

    @Before fun reset() {
        preferences.edit().clear().commit()
    }

    @Test fun `record round trips through preferences`() {
        assertNull(storage.readRecordRaw())

        val record = AppPinRecord(
            iterations = AppPinHasher.MIN_ITERATIONS,
            salt = ByteArray(AppPinHasher.SALT_BYTES) { it.toByte() },
            hash = ByteArray(AppPinHasher.HASH_BYTES) { (it * 3).toByte() },
        )
        val raw = AppPinRecordCodec.encode(record)
        assertTrue(storage.writeRecordRaw(raw))
        assertEquals(raw, preferences.getString(SharedPreferencesAppLockStorage.KEY_RECORD, null))
        assertEquals(raw, storage.readRecordRaw())
        assertEquals(AppPinRecordRead.Valid(record), AppPinRecordCodec.decode(storage.readRecordRaw()))

        assertTrue(storage.clearRecord())
        assertNull(storage.readRecordRaw())
    }

    @Test fun `throttle round trips through preferences`() {
        assertEquals(ThrottleState(), storage.readThrottle())

        val state = ThrottleState(
            failures = 6,
            lockoutStartElapsedMs = 42_000,
            lockoutMillis = 60_000,
            lockoutBootId = "boot-a",
        )
        assertTrue(storage.writeThrottle(state))
        assertEquals(state, storage.readThrottle())
        assertEquals(6, preferences.getInt(SharedPreferencesAppLockStorage.KEY_FAILURES, -1))
        assertEquals(
            42_000L,
            preferences.getLong(SharedPreferencesAppLockStorage.KEY_LOCKOUT_START, -1),
        )
        assertEquals(
            60_000L,
            preferences.getLong(SharedPreferencesAppLockStorage.KEY_LOCKOUT_MS, -1),
        )
        assertEquals("boot-a", preferences.getString(SharedPreferencesAppLockStorage.KEY_LOCKOUT_BOOT, null))
    }

    @Test fun `a malformed throttle value fails closed and keeps the pin record`() {
        val recordRaw = AppPinRecordCodec.encode(
            AppPinRecord(
                iterations = AppPinHasher.MIN_ITERATIONS,
                salt = ByteArray(AppPinHasher.SALT_BYTES) { it.toByte() },
                hash = ByteArray(AppPinHasher.HASH_BYTES) { (it + 1).toByte() },
            ),
        )
        assertTrue(storage.writeRecordRaw(recordRaw))
        preferences.edit()
            .putInt(SharedPreferencesAppLockStorage.KEY_FAILURES, 6)
            .putLong(SharedPreferencesAppLockStorage.KEY_LOCKOUT_START, 42_000)
            .putString(SharedPreferencesAppLockStorage.KEY_LOCKOUT_MS, "not-a-long")
            .putString(SharedPreferencesAppLockStorage.KEY_LOCKOUT_BOOT, "boot-a")
            .commit()

        val closed = storage.readThrottle()
        assertEquals(6, closed.failures)
        assertEquals(AppPinThrottle.MAX_LOCKOUT_MILLIS, closed.lockoutMillis)
        // Anchored to an unknown boot: even long after the stored start, the full window applies.
        assertEquals(0L, closed.lockoutStartElapsedMs)
        assertEquals(null, closed.lockoutBootId)
        assertEquals(
            AppPinThrottle.MAX_LOCKOUT_MILLIS,
            AppPinThrottle.remainingMillis(
                closed,
                nowElapsedMs = 42_000 + AppPinThrottle.MAX_LOCKOUT_MILLIS + 1,
                bootId = "boot-a",
            ),
        )

        // The record is neither deleted nor rewritten by a corrupt throttle read.
        assertEquals(recordRaw, storage.readRecordRaw())
    }

    @Test fun `a malformed failure count fails closed with the maximum lockout`() {
        preferences.edit()
            .putString(SharedPreferencesAppLockStorage.KEY_FAILURES, "many")
            .commit()

        val closed = storage.readThrottle()
        assertEquals(11, closed.failures)
        assertEquals(AppPinThrottle.MAX_LOCKOUT_MILLIS, closed.lockoutMillis)
        assertEquals(
            AppPinThrottle.MAX_LOCKOUT_MILLIS,
            AppPinThrottle.remainingMillis(closed, nowElapsedMs = 0, bootId = "boot-a"),
        )
    }

    @Test fun `a wrong typed record is unreadable rather than absent`() {
        preferences.edit().putInt(SharedPreferencesAppLockStorage.KEY_RECORD, 7).commit()

        val read = AppPinRecordCodec.decode(storage.readRecordRaw())
        assertTrue("a non-string record must be unreadable: $read", read is AppPinRecordRead.Unreadable)
        assertNotNull(preferences.getAll()[SharedPreferencesAppLockStorage.KEY_RECORD])
    }

    @Test fun `the controller is a process singleton`() {
        val first = AppLock.controller(context)
        val second = AppLock.controller(context)

        assertSame(first, second)
        assertEquals(PinStatus.Off, first.state.value.pinStatus)
        assertFalse(first.state.value.locked)
    }

    @Test fun `the system clock reports elapsed time and a boot identity`() {
        val clock = SystemAppLockClock(context)

        assertTrue(clock.elapsedRealtimeMs() >= 0)
        assertTrue(clock.bootId().isNotEmpty())
    }
}
