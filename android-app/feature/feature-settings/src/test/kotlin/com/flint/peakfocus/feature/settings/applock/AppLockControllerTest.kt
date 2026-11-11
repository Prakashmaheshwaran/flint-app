package com.flint.peakfocus.feature.settings.applock

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State machine, throttle and record-lifecycle behaviour of the app-open PIN. */
class AppLockControllerTest {

    private val pin = "1379"

    @Test fun `fresh storage is off and unlocked`() {
        val controller = AppLockController(InMemoryAppLockStorage(), FakeClock())

        val state = controller.state.value
        assertEquals(PinStatus.Off, state.pinStatus)
        assertFalse(state.locked)
        assertFalse(state.gateVisible)
        assertEquals(0L, state.lockoutRemainingMs)
        assertFalse(state.busy)
        assertEquals(0, state.epoch)
    }

    @Test fun `a stored record is on and locked`() {
        val controller = AppLockController(InMemoryAppLockStorage(recordRaw = rawFor(pin)), FakeClock())

        assertEquals(PinStatus.On, controller.state.value.pinStatus)
        assertTrue(controller.state.value.locked)
        assertTrue(controller.state.value.gateVisible)
    }

    @Test fun `a cold start on the same storage is locked`() {
        val storage = InMemoryAppLockStorage()
        val first = AppLockController(storage, FakeClock())
        assertTrue(runBlocking { first.savePin("2468") })

        val cold = AppLockController(storage, FakeClock())
        assertEquals(PinStatus.On, cold.state.value.pinStatus)
        assertTrue(cold.state.value.locked)
        assertEquals(0, cold.state.value.epoch)
    }

    @Test fun `the correct pin unlocks and resets the throttle`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)

        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertFalse(controller.state.value.locked)
        assertFalse(controller.state.value.gateVisible)
        assertEquals(ThrottleState(), storage.throttle)
        assertFalse(controller.state.value.busy)
    }

    @Test fun `a wrong pin counts and escalates after four free attempts`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)

        repeat(4) {
            assertEquals(PinAttempt.Wrong(0L), unlockAttempt(controller, pin + "0"))
            assertEquals(it + 1, storage.throttle.failures)
            assertEquals(0L, controller.state.value.lockoutRemainingMs)
        }
        assertEquals(PinAttempt.Wrong(30_000L), unlockAttempt(controller, "0000"))
        assertEquals(5, storage.throttle.failures)
        assertEquals(30_000L, controller.state.value.lockoutRemainingMs)
    }

    @Test fun `a lockout refuses attempts without hashing or counting them`() {
        val clock = FakeClock(elapsed = 10_000)
        val storage = SpyStorage(recordRaw = rawFor(pin))
        repeat(5) { unlockAttempt(newController(storage, clock), "0000") }
        assertEquals(5, storage.throttle.failures)

        val dispatcher = RecordingDispatcher()
        val guarded = newController(storage, clock, dispatcher)
        assertEquals(PinAttempt.LockedOut(30_000L), unlockAttempt(guarded, "0000"))
        assertEquals(0, dispatcher.dispatches.get())
        assertEquals(5, storage.throttle.failures)

        clock.elapsed += 29_999
        assertEquals(PinAttempt.LockedOut(1L), unlockAttempt(guarded, pin))
        assertEquals(0, dispatcher.dispatches.get())
        assertEquals(5, storage.throttle.failures)

        clock.elapsed += 1
        assertEquals(PinAttempt.Wrong(60_000L), unlockAttempt(guarded, "0000"))
        assertTrue(dispatcher.dispatches.get() > 0)
        assertEquals(6, storage.throttle.failures)
    }

    @Test fun `a lockout survives a new controller on the same storage`() {
        val clock = FakeClock(elapsed = 10_000)
        val storage = SpyStorage(recordRaw = rawFor(pin))
        repeat(5) { unlockAttempt(newController(storage, clock), "0000") }

        val cold = newController(storage, clock)
        assertEquals(PinStatus.On, cold.state.value.pinStatus)
        assertTrue(cold.state.value.locked)
        assertEquals(30_000L, cold.state.value.lockoutRemainingMs)
        assertEquals(PinAttempt.LockedOut(30_000L), unlockAttempt(cold, pin))
    }

    @Test fun `a reboot restarts the full lockout on the new boot`() {
        val clock = FakeClock(elapsed = 10_000, boot = "boot-a")
        val storage = SpyStorage(recordRaw = rawFor(pin))
        repeat(5) { unlockAttempt(newController(storage, clock), "0000") }

        clock.boot = "boot-b"
        clock.elapsed = 0
        val rebooted = newController(storage, clock)
        assertEquals(30_000L, rebooted.state.value.lockoutRemainingMs)
        assertEquals(PinAttempt.LockedOut(30_000L), unlockAttempt(rebooted, pin))
        assertEquals("boot-b", storage.throttle.lockoutBootId)
        assertEquals(0L, storage.throttle.lockoutStartElapsedMs)

        clock.elapsed = 30_000
        assertEquals(PinAttempt.Success, unlockAttempt(rebooted, pin))
    }

    @Test fun `a lockout waited out on this boot is not restarted by a later reboot`() {
        val clock = FakeClock(elapsed = 10_000, boot = "boot-a")
        val storage = SpyStorage(recordRaw = rawFor(pin))
        repeat(5) { unlockAttempt(newController(storage, clock), "0000") }
        val controller = newController(storage, clock)

        clock.elapsed += 30_000
        controller.refreshLockout()
        assertEquals(0L, controller.state.value.lockoutRemainingMs)
        assertEquals(ThrottleState(failures = 5), storage.throttle)

        clock.boot = "boot-b"
        clock.elapsed = 0
        val rebooted = newController(storage, clock)
        assertEquals(0L, rebooted.state.value.lockoutRemainingMs)
        // The count is kept, so the next wrong PIN still escalates.
        assertEquals(PinAttempt.Wrong(60_000L), unlockAttempt(rebooted, "0000"))
        assertEquals(PinAttempt.LockedOut(60_000L), unlockAttempt(rebooted, pin))
    }

    @Test fun `a re-lock while the pin is hashed keeps the gate up`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        lateinit var controller: AppLockController
        // The user leaves Flint while PBKDF2 runs: onBackground() does not wait for the mutex.
        val dispatcher = RecordingDispatcher { index -> if (index == 1) controller.onBackground() }
        controller = newController(storage, dispatcher = dispatcher)

        assertEquals(PinAttempt.Unavailable, unlockAttempt(controller, pin))
        assertTrue(controller.state.value.locked)
        assertTrue(controller.state.value.gateVisible)
        // The PIN itself was right, so the attempt is not held against the user.
        assertEquals(ThrottleState(), storage.throttle)
    }

    @Test fun `a re-lock while a new pin is hashed saves nothing`() {
        val storage = SpyStorage()
        lateinit var controller: AppLockController
        val dispatcher = RecordingDispatcher { index -> if (index == 1) controller.onBackground() }
        controller = newController(storage, dispatcher = dispatcher)

        assertFalse(runBlocking { controller.savePin("2468") })
        assertNull(storage.recordRaw)
        assertEquals(PinStatus.Off, controller.state.value.pinStatus)
    }

    @Test fun `a re-lock while the current pin is hashed removes nothing`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        var armed = false
        lateinit var controller: AppLockController
        val dispatcher = RecordingDispatcher {
            if (armed) {
                armed = false
                controller.onBackground()
            }
        }
        controller = newController(storage, dispatcher = dispatcher)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertFalse(controller.state.value.locked)
        val raw = storage.recordRaw

        armed = true
        assertEquals(PinAttempt.Unavailable, runBlocking { controller.removePin(pin) })
        assertEquals(raw, storage.recordRaw)
        assertEquals(PinStatus.On, controller.state.value.pinStatus)
        assertTrue(controller.state.value.locked)
    }

    @Test fun `a clock change that looks like a reboot only lengthens the lockout`() {
        val clock = FakeClock(elapsed = 10_000, boot = "boot-a")
        val storage = SpyStorage(recordRaw = rawFor(pin))
        repeat(5) { unlockAttempt(newController(storage, clock), "0000") }

        // API 23 fallback style boot label after the wall clock moved backwards.
        clock.boot = "wall-4000"
        clock.elapsed = 0
        val afterChange = newController(storage, clock)
        assertEquals(PinAttempt.LockedOut(30_000L), unlockAttempt(afterChange, pin))
        assertEquals("wall-4000", storage.throttle.lockoutBootId)
    }

    @Test fun `an attempt is counted before the pin is hashed`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        lateinit var controller: AppLockController
        val dispatcher = RecordingDispatcher { index ->
            if (index == 1) {
                assertEquals(1, storage.throttle.failures)
                assertTrue(controller.state.value.busy)
            }
        }
        controller = newController(storage, dispatcher = dispatcher)

        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertTrue(dispatcher.dispatches.get() >= 1)
        assertFalse(controller.state.value.busy)
        assertEquals(ThrottleState(), storage.throttle)
    }

    @Test fun `a throttle write failure is unavailable without verifying`() {
        val storage = SpyStorage(recordRaw = rawFor(pin)).apply { failThrottleWrites = true }
        val dispatcher = RecordingDispatcher()
        val controller = newController(storage, dispatcher = dispatcher)

        assertEquals(PinAttempt.Unavailable, unlockAttempt(controller, pin))
        assertEquals(0, dispatcher.dispatches.get())
        assertEquals(ThrottleState(), storage.throttle)
        assertTrue(controller.state.value.locked)
    }

    @Test fun `a success after failures resets the counter`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        repeat(4) { unlockAttempt(controller, "0000") }
        assertEquals(4, storage.throttle.failures)

        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertEquals(ThrottleState(), storage.throttle)
        assertEquals(0L, controller.state.value.lockoutRemainingMs)
    }

    @Test fun `an over-long entry is wrong without hashing but still counts`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val dispatcher = RecordingDispatcher()
        val controller = newController(storage, dispatcher = dispatcher)

        assertEquals(PinAttempt.Wrong(0L), unlockAttempt(controller, "1".repeat(AppPinPolicy.MAX_ENTRY_LENGTH + 1)))
        assertEquals(1, storage.throttle.failures)
        assertEquals(0, dispatcher.dispatches.get())
    }

    @Test fun `an empty entry is wrong without hashing but still counts`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val dispatcher = RecordingDispatcher()
        val controller = newController(storage, dispatcher = dispatcher)

        assertEquals(PinAttempt.Wrong(0L), unlockAttempt(controller, ""))
        assertEquals(1, storage.throttle.failures)
        assertEquals(0, dispatcher.dispatches.get())
    }

    @Test fun `unlocking an already unlocked app is unavailable and does not count`() {
        val storage = SpyStorage()
        val controller = newController(storage)
        assertTrue(runBlocking { controller.savePin("2468") })

        assertEquals(PinAttempt.Unavailable, unlockAttempt(controller, "2468"))
        assertEquals(0, storage.throttle.failures)
        assertFalse(controller.state.value.locked)
    }

    @Test fun `changing the pin requires the current pin in this visit`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        val previousRaw = storage.recordRaw

        assertFalse("without authorization", runBlocking { controller.savePin("2468") })
        assertEquals(previousRaw, storage.recordRaw)
        assertEquals(PinAttempt.Wrong(0L), runBlocking { controller.authorizeChange("0000") })
        assertFalse("a wrong current pin does not authorize", runBlocking { controller.savePin("2468") })
        assertEquals(PinAttempt.Success, runBlocking { controller.authorizeChange(pin) })
        assertFalse("an invalid new pin is refused", runBlocking { controller.savePin("123") })
        assertEquals(previousRaw, storage.recordRaw)

        assertTrue(runBlocking { controller.savePin("2468") })
        assertEquals(PinStatus.On, controller.state.value.pinStatus)
        assertFalse(controller.state.value.locked)
        val saved = AppPinRecordCodec.decode(storage.recordRaw)
        assertTrue(saved is AppPinRecordRead.Valid)
        saved as AppPinRecordRead.Valid
        assertEquals(AppPinHasher.ITERATIONS, saved.record.iterations)
        assertTrue(AppPinHasher.matches("2468", saved.record))

        assertFalse("authorization is consumed", runBlocking { controller.savePin("1357") })
    }

    @Test fun `a failed record write keeps the previous pin`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertEquals(PinAttempt.Success, runBlocking { controller.authorizeChange(pin) })
        val previousRaw = storage.recordRaw

        storage.failRecordWrites = true
        assertFalse(runBlocking { controller.savePin("2468") })
        assertEquals(previousRaw, storage.recordRaw)
        assertFalse(controller.state.value.locked)
        assertEquals(PinAttempt.Success, unlockAttempt(appLockControllerOf(storage), pin))
    }

    @Test fun `background relocks and clears the change authorization`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertEquals(PinAttempt.Success, runBlocking { controller.authorizeChange(pin) })
        val epoch = controller.state.value.epoch

        controller.onBackground()
        assertTrue(controller.state.value.locked)
        assertTrue(controller.state.value.gateVisible)
        assertEquals(epoch + 1, controller.state.value.epoch)
        assertFalse(runBlocking { controller.savePin("2468") })

        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertFalse("unlocking does not restore authorization", runBlocking { controller.savePin("2468") })
    }

    @Test fun `background on a pin-less install stays unlocked`() {
        val controller = AppLockController(InMemoryAppLockStorage(), FakeClock())
        controller.onBackground()

        assertEquals(PinStatus.Off, controller.state.value.pinStatus)
        assertFalse(controller.state.value.locked)
        assertEquals(1, controller.state.value.epoch)
    }

    @Test fun `an external record change invalidates authorization on refresh`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertEquals(PinAttempt.Success, runBlocking { controller.authorizeChange(pin) })
        val epoch = controller.state.value.epoch

        storage.recordRaw = rawFor("2468", salt = salt(9))
        controller.refresh()
        assertEquals(PinStatus.On, controller.state.value.pinStatus)
        assertTrue(controller.state.value.locked)
        assertEquals(epoch + 1, controller.state.value.epoch)
        assertFalse(runBlocking { controller.savePin("1357") })

        storage.recordRaw = null
        controller.refresh()
        assertEquals(PinStatus.Off, controller.state.value.pinStatus)
        assertFalse(controller.state.value.locked)
        assertFalse(controller.state.value.gateVisible)
    }

    @Test fun `refresh without a change keeps state and epoch`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        val epoch = controller.state.value.epoch

        controller.refresh()
        assertFalse(controller.state.value.locked)
        assertEquals(epoch, controller.state.value.epoch)
    }

    @Test fun `removing the pin requires the current pin and resets the throttle`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))

        assertEquals(PinAttempt.Wrong(0L), runBlocking { controller.removePin("0000") })
        assertEquals(1, storage.throttle.failures)
        assertEquals(PinAttempt.Success, runBlocking { controller.removePin(pin) })
        assertNull(storage.recordRaw)
        assertEquals(ThrottleState(), storage.throttle)
        assertEquals(PinStatus.Off, controller.state.value.pinStatus)
        assertFalse(controller.state.value.locked)
    }

    @Test fun `removing the pin needs an unlocked app`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)

        assertEquals(PinAttempt.Unavailable, runBlocking { controller.removePin(pin) })
        assertEquals(rawFor(pin), storage.recordRaw)
        assertEquals(0, storage.throttle.failures)
    }

    @Test fun `a failed record removal keeps the pin`() {
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        val currentRaw = storage.recordRaw
        storage.failClear = true

        assertEquals(PinAttempt.Unavailable, runBlocking { controller.removePin(pin) })
        assertEquals(currentRaw, storage.recordRaw)
        assertEquals(PinStatus.On, controller.state.value.pinStatus)
    }

    @Test fun `save from off sets a pin, unlocks and locks again on a cold start`() {
        val storage = SpyStorage()
        val controller = newController(storage)
        val secret = "2468"

        assertTrue(runBlocking { controller.savePin(secret) })
        assertEquals(PinStatus.On, controller.state.value.pinStatus)
        assertFalse(controller.state.value.locked)
        val decoded = AppPinRecordCodec.decode(storage.recordRaw)
        assertTrue(decoded is AppPinRecordRead.Valid)
        decoded as AppPinRecordRead.Valid
        assertEquals(AppPinHasher.ITERATIONS, decoded.record.iterations)
        assertTrue(AppPinHasher.matches(secret, decoded.record))

        val cold = newController(storage)
        assertTrue(cold.state.value.locked)
        assertEquals(PinAttempt.Success, unlockAttempt(cold, secret))
    }

    @Test fun `save rejects every invalid new pin and keeps the install pin-less`() {
        val storage = SpyStorage()
        val controller = newController(storage)

        for (candidate in listOf("", "1", "123", "1234567", " 1234", "12 34", "12a4", "١٢٣٤", "１２３４")) {
            assertFalse("<$candidate> should be refused", runBlocking { controller.savePin(candidate) })
        }
        assertNull(storage.recordRaw)
        assertEquals(PinStatus.Off, controller.state.value.pinStatus)
        assertEquals(0, storage.throttle.failures)
    }

    @Test fun `an unreadable record is locked and can never be verified or replaced`() {
        val raw = "v2${SEP}${AppPinHasher.ALGORITHM}${SEP}120000${SEP}" +
            "00".repeat(AppPinHasher.SALT_BYTES) + SEP + "11".repeat(AppPinHasher.HASH_BYTES)
        val storage = SpyStorage(recordRaw = raw)
        val dispatcher = RecordingDispatcher()
        val controller = newController(storage, dispatcher = dispatcher)

        assertEquals(PinStatus.Unreadable, controller.state.value.pinStatus)
        assertTrue(controller.state.value.locked)
        assertTrue(controller.state.value.gateVisible)
        assertEquals(PinAttempt.Unavailable, unlockAttempt(controller, pin))
        assertEquals(PinAttempt.Unavailable, runBlocking { controller.authorizeChange(pin) })
        assertEquals(PinAttempt.Unavailable, runBlocking { controller.removePin(pin) })
        assertFalse(runBlocking { controller.savePin("2468") })

        assertEquals(raw, storage.recordRaw)
        assertEquals(0, storage.throttle.failures)
        assertEquals(0, dispatcher.dispatches.get())
    }

    @Test fun `a malformed record is locked and never overwritten`() {
        val storage = SpyStorage(recordRaw = "v1${SEP}${AppPinHasher.ALGORITHM}${SEP}abc")
        val controller = newController(storage)

        assertEquals(PinStatus.Unreadable, controller.state.value.pinStatus)
        assertTrue(controller.state.value.locked)
        assertFalse(runBlocking { controller.savePin("2468") })
        assertEquals("v1${SEP}${AppPinHasher.ALGORITHM}${SEP}abc", storage.recordRaw)
    }

    @Test fun `a successful unlock upgrades an old iteration count`() {
        val originalSalt = salt(3)
        val storage = SpyStorage(recordRaw = rawFor(pin, AppPinHasher.MIN_ITERATIONS, originalSalt))
        val controller = newController(storage)

        assertEquals(PinAttempt.Success, unlockAttempt(controller, pin))
        assertEquals(1, storage.recordWrites)
        val upgraded = AppPinRecordCodec.decode(storage.recordRaw)
        assertTrue(upgraded is AppPinRecordRead.Valid)
        upgraded as AppPinRecordRead.Valid
        assertEquals(AppPinHasher.ITERATIONS, upgraded.record.iterations)
        assertNotEquals(originalSalt.toList(), upgraded.record.salt.toList())
        assertTrue(AppPinHasher.matches(pin, upgraded.record))

        val cold = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(cold, pin))
        assertEquals(1, storage.recordWrites)
    }

    @Test fun `no stored string ever contains the pin`() {
        val storage = SpyStorage()
        val controller = AppLockController(storage, FakeClock(), FixedRandom(0x2A), Dispatchers.Unconfined)
        val secret = "918273"

        assertTrue(runBlocking { controller.savePin(secret) })
        val raw = requireNotNull(storage.recordRaw)
        assertFalse(raw.contains(secret))
        assertFalse(storage.writes.joinToString(" ").contains(secret))
        assertEquals(4, raw.count { it == SEP_CHAR })

        val decoded = AppPinRecordCodec.decode(raw)
        assertTrue(decoded is AppPinRecordRead.Valid)
        decoded as AppPinRecordRead.Valid
        assertArrayEquals(ByteArray(AppPinHasher.SALT_BYTES) { 0x2A }, decoded.record.salt)
        assertFalse(decoded.record.toString().contains(secret))

        val cold = newController(storage)
        assertEquals(PinAttempt.Success, unlockAttempt(cold, secret))
    }

    @Test fun `refresh lockout reports the remaining window and keeps the gate`() {
        val clock = FakeClock(elapsed = 10_000)
        val storage = SpyStorage(recordRaw = rawFor(pin))
        val controller = newController(storage, clock)
        repeat(5) { unlockAttempt(controller, "0000") }

        clock.elapsed += 12_000
        controller.refreshLockout()
        assertEquals(18_000L, controller.state.value.lockoutRemainingMs)
        assertTrue(controller.state.value.gateVisible)
    }

    private fun newController(
        storage: AppLockStorage,
        clock: FakeClock = FakeClock(),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): AppLockController = AppLockController(storage, clock, hashDispatcher = dispatcher)

    /** A second, independent controller over the same storage (cold start). */
    private fun appLockControllerOf(storage: AppLockStorage): AppLockController =
        AppLockController(storage, FakeClock())

    private fun unlockAttempt(controller: AppLockController, candidate: String): PinAttempt =
        runBlocking { controller.unlock(candidate) }

    private fun salt(offset: Int): ByteArray =
        ByteArray(AppPinHasher.SALT_BYTES) { (it + offset).toByte() }

    private fun recordFor(
        pin: String,
        iterations: Int = AppPinHasher.MIN_ITERATIONS,
        salt: ByteArray = salt(1),
    ): AppPinRecord = AppPinRecord(iterations, salt, AppPinHasher.hash(pin, salt, iterations))

    private fun rawFor(
        pin: String,
        iterations: Int = AppPinHasher.MIN_ITERATIONS,
        salt: ByteArray = salt(1),
    ): String = AppPinRecordCodec.encode(recordFor(pin, iterations, salt))

    private class FakeClock(var elapsed: Long = 10_000, var boot: String = "boot-a") : AppLockClock {
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun bootId(): String = boot
    }

    private class FixedRandom(private val value: Byte) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(value)
        }
    }

    /** Runs dispatched blocks inline and reports each dispatch, so hashing is observable. */
    private class RecordingDispatcher(
        private val onDispatch: (Int) -> Unit = {},
    ) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            onDispatch(dispatches.incrementAndGet())
            block.run()
        }
    }

    /** Records every write so tests can prove ordering and that no plaintext is stored. */
    private class SpyStorage(
        var recordRaw: String? = null,
        var throttle: ThrottleState = ThrottleState(),
    ) : AppLockStorage {
        var failRecordWrites = false
        var failThrottleWrites = false
        var failClear = false
        var recordWrites = 0
        val writes = mutableListOf<String>()

        override fun readRecordRaw(): String? = recordRaw

        override fun writeRecordRaw(raw: String): Boolean {
            recordWrites++
            writes += "record"
            if (failRecordWrites) return false
            recordRaw = raw
            return true
        }

        override fun clearRecord(): Boolean {
            writes += "clear"
            if (failClear) return false
            recordRaw = null
            return true
        }

        override fun readThrottle(): ThrottleState = throttle

        override fun writeThrottle(state: ThrottleState): Boolean {
            writes += "throttle:${state.failures}:${state.lockoutMillis}"
            if (failThrottleWrites) return false
            throttle = state
            return true
        }
    }

    private companion object {
        const val SEP = "\u0024"
        const val SEP_CHAR = '\u0024'
    }
}
