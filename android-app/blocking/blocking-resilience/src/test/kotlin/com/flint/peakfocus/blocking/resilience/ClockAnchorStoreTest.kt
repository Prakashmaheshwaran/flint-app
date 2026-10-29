package com.flint.peakfocus.blocking.resilience

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.model.AccessClockStamp
import java.util.Properties
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClockAnchorStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences(ClockAnchorStore.PREFS, Context.MODE_PRIVATE)
    private val before = AccessClockStamp(1_000_000, 1_000, "boot-a")
    private val store get() = ClockAnchorStore(prefs)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun `R_18 PC_54 cold Application startup preserves prior evidence until receiver measurement`() {
        store.initialize(before, 0)
        TimeChangeReceiver.baselineAnchor(context)
        assertEquals(before.wallEpochMs, prefs.getLong(ClockAnchorStore.WALL, -1))
        val after = before.copy(wallEpochMs = before.wallEpochMs + 8 * 3_600_000 + 60_000,
            elapsedRealtimeMs = before.elapsedRealtimeMs + 60_000)
        assertEquals(8 * 3_600_000L, store.measureAndRebaseline(after, 0).wallDeltaMs)
        assertEquals(0L, store.measureAndRebaseline(after, 0).wallDeltaMs)
        assertEquals(after.wallEpochMs, prefs.getLong(ClockAnchorStore.WALL, -1))
    }

    @Test fun `R_18 PC_55 same-boot process restart measures rollback including genuine elapsed time`() {
        store.initialize(before, 0)
        val after = before.copy(wallEpochMs = before.wallEpochMs - 100_000, elapsedRealtimeMs = before.elapsedRealtimeMs + 60_000)
        assertEquals(-160_000L, store.measureAndRebaseline(after, 0).wallDeltaMs)
    }

    @Test fun `R_18 PC_58 reboot cannot masquerade as same boot when uptime is greater`() {
        store.initialize(before, 0)
        val after = before.copy(wallEpochMs = 100_000_000, elapsedRealtimeMs = 50_000, bootSessionId = "boot-b")
        store.initialize(after, 0)
        assertEquals("boot-a", prefs.getString(ClockAnchorStore.BOOT, null))
        assertEquals(0L, store.measureAndRebaseline(after, 0).wallDeltaMs)
        assertEquals("boot-b", prefs.getString(ClockAnchorStore.BOOT, null))
    }

    @Test fun `R_18 PC_58 legacy fixture migrates without inventing cross-boot elapsed evidence`() {
        val legacy = Properties().apply {
            requireNotNull(ClockAnchorStoreTest::class.java.getResourceAsStream("/clock-anchor-v1.properties")).use(::load)
        }
        val editor = prefs.edit()
        legacy.forEach { key, value -> editor.putLong(key.toString(), value.toString().toLong()) }
        editor.commit()
        val bytes = prefs.all.toMap()
        TimeChangeReceiver.baselineAnchor(context)
        assertEquals(bytes, prefs.all)
        val after = before.copy(wallEpochMs = 100_000_000, elapsedRealtimeMs = 100_000)
        val migrated = store.measureAndRebaseline(after, 0)
        assertEquals(0L, migrated.wallDeltaMs)
        assertEquals(-14_400_000L, migrated.oldUtcOffsetMs)
        assertEquals(2, prefs.getInt(ClockAnchorStore.VERSION, -1))
        assertEquals(after.bootSessionId, prefs.getString(ClockAnchorStore.BOOT, null))
        assertEquals(5_000L, store.measureAndRebaseline(after.copy(wallEpochMs = after.wallEpochMs + 6_000,
            elapsedRealtimeMs = after.elapsedRealtimeMs + 1_000), 0).wallDeltaMs)
    }

    @Test fun `R_18 PC_56 pure zone change preserves elapsed wall progression`() {
        store.initialize(before, 0)
        val after = before.copy(wallEpochMs = before.wallEpochMs + 60_000, elapsedRealtimeMs = before.elapsedRealtimeMs + 60_000)
        val shift = store.measureAndRebaseline(after, 3_600_000)
        assertEquals(0L, shift.wallDeltaMs)
        assertEquals(0L, shift.oldUtcOffsetMs)
        assertEquals(3_600_000L, shift.newUtcOffsetMs)
    }

    @Test fun `R_18 future anchor version remains recoverable and cannot yield a measured jump`() {
        store.initialize(before, 0)
        prefs.edit().putInt(ClockAnchorStore.VERSION, 99).commit()
        val retained = prefs.all.toMap()
        store.initialize(before.copy(wallEpochMs = 2_000_000), 0)
        assertEquals(0L, store.measureAndRebaseline(before.copy(wallEpochMs = 3_000_000), 0).wallDeltaMs)
        assertEquals(retained, prefs.all)
    }
}
