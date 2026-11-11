package com.flint.peakfocus.feature.settings.applock

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether a verifiable PIN record exists. [Unreadable] is a PIN that exists but cannot be checked. */
enum class PinStatus { Off, On, Unreadable }

/**
 * Observable app-lock state.
 *
 * [locked] is independent of [pinStatus]: a locked app always has a gate, an unlocked app may
 * still be PIN-protected. [busy] is true while a verifying operation holds the controller.
 */
data class AppLockState(
    val pinStatus: PinStatus,
    val locked: Boolean,
    val lockoutRemainingMs: Long = 0,
    val busy: Boolean = false,
    /** Increments whenever a re-lock or record change invalidates in-progress PIN dialogs. */
    val epoch: Int = 0,
) {
    val gateVisible: Boolean get() = pinStatus != PinStatus.Off && locked
}

/** Result of a PIN-verifying operation. */
sealed interface PinAttempt {
    /** The PIN was correct. */
    object Success : PinAttempt

    /** The PIN was wrong; [lockoutMs] is the lockout that now applies (0 while attempts are free). */
    data class Wrong(val lockoutMs: Long) : PinAttempt

    /** Refused without verifying: [remainingMs] must elapse first. */
    data class LockedOut(val remainingMs: Long) : PinAttempt

    /** Refused: no verifiable record, no gate to unlock, or storage could not be updated. */
    object Unavailable : PinAttempt
}

/**
 * Android app-open PIN state machine (port of iOS `FlintAppLock`).
 *
 * Defaults: no record means the feature is off and the app is unlocked, so existing flows are
 * unaffected. A record means the app starts locked on every cold start. Backgrounding while a
 * record exists re-locks and drops any current-PIN authorization; changing or removing an
 * existing PIN therefore requires the current PIN in this foreground visit.
 *
 * Every verification shares one persisted throttle: attempts are counted *before* hashing, so a
 * process kill (or a crash) mid-hash still consumes an attempt, and the record is never touched by
 * a failed attempt. An [PinStatus.Unreadable] record is fail-closed: it can never be verified,
 * overwritten or deleted from inside the app.
 *
 * @param random source of salts; replaceable for deterministic tests.
 * @param hashDispatcher dispatcher for PBKDF2 so the caller's thread never pays the cost.
 */
class AppLockController(
    private val storage: AppLockStorage,
    private val clock: AppLockClock,
    private val random: SecureRandom = SecureRandom(),
    private val hashDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val mutex = Mutex()
    private val stateLock = Any()

    /** Last record snapshot read from storage. Guarded by [stateLock]. */
    private var known: AppPinRecordRead = readRecord()

    /** Record the user proved knowledge of in this foreground visit. Guarded by [stateLock]. */
    private var authorization: AppPinRecord? = null

    private val _state = MutableStateFlow(initialState())

    /** UI-facing state. The construction-time read is synchronous so the first frame is correct. */
    val state: StateFlow<AppLockState> = _state.asStateFlow()

    /** Re-locks when a PIN exists, drops change authorization and invalidates open PIN dialogs. */
    fun onBackground() {
        synchronized(stateLock) {
            authorization = null
            _state.update { it.copy(locked = it.pinStatus != PinStatus.Off, epoch = it.epoch + 1) }
        }
    }

    /** Re-reads the record; a changed or removed record cannot leave stale access behind. */
    fun refresh() {
        reconcile()
    }

    /** Recomputes [AppLockState.lockoutRemainingMs]; the gate calls this about once per second. */
    fun refreshLockout() {
        val remaining = lockoutRemaining(persistRestart = true)
        _state.update { it.copy(lockoutRemainingMs = remaining) }
    }

    /** Verifies [pin] against the gate. Only available while a gate is visible. */
    suspend fun unlock(pin: String): PinAttempt = attempt(
        pin = pin,
        precheck = { it.gateVisible },
        upgradeIterations = true,
    ) {
        synchronized(stateLock) {
            _state.update { it.copy(locked = false) }
        }
        PinAttempt.Success
    }

    /**
     * Proves knowledge of the current PIN so it may be replaced or removed in this visit.
     * Requires a PIN that is currently unlocked; a previous authorization is dropped first.
     */
    suspend fun authorizeChange(currentPin: String): PinAttempt {
        synchronized(stateLock) { authorization = null }
        return attempt(
            pin = currentPin,
            precheck = { it.pinStatus == PinStatus.On && !it.locked },
        ) { record ->
            synchronized(stateLock) { authorization = record }
            PinAttempt.Success
        }
    }

    /** Drops a current-PIN authorization without changing the PIN. */
    fun cancelChange() {
        synchronized(stateLock) { authorization = null }
    }

    /**
     * Saves a new PIN with a fresh salt. Requires an unlocked, writable record slot: with a PIN
     * already set, [authorizeChange] must have succeeded for the currently stored record.
     *
     * The new record is written, read back, decoded and verified against [newPin] before the
     * controller adopts it. Any failure leaves the previous record in place and this returns false.
     */
    suspend fun savePin(newPin: String): Boolean = mutex.withLock {
        val current = reconcile()
        if (current.locked || current.pinStatus == PinStatus.Unreadable) return@withLock false
        if (!AppPinPolicy.isValidNewPin(newPin)) return@withLock false

        val previous = (known as? AppPinRecordRead.Valid)?.record
        if (current.pinStatus == PinStatus.On) {
            val authorized = synchronized(stateLock) { authorization }
            if (previous == null || authorized != previous) return@withLock false
        }

        val epoch = current.epoch
        val record = withContext(hashDispatcher) { AppPinHasher.newRecord(newPin, random) }
        // A re-lock while hashing ended this visit (and any change authorization): save nothing.
        if (_state.value.epoch != epoch) return@withLock false
        if (!writeRecord(AppPinRecordCodec.encode(record))) return@withLock false

        val readBack = readRecord()
        val stored = readBack as? AppPinRecordRead.Valid
        val verified = stored != null &&
            stored.record == record &&
            previous != record &&
            withContext(hashDispatcher) { AppPinHasher.matches(newPin, stored.record) }
        if (!verified) {
            // The stored bytes are not provably the new record: adopt reality and fail closed.
            synchronized(stateLock) {
                known = readBack
                authorization = null
                val status = statusOf(readBack)
                _state.update {
                    it.copy(pinStatus = status, locked = status != PinStatus.Off, epoch = it.epoch + 1)
                }
            }
            return@withLock false
        }

        writeThrottle(ThrottleState())
        synchronized(stateLock) {
            known = readBack
            authorization = null
            // The record is saved either way, but a re-lock during the read-back hash ended this
            // visit: the new PIN then starts locked, like any PIN after leaving Flint.
            val relocked = _state.value.epoch != epoch
            _state.update {
                it.copy(pinStatus = PinStatus.On, locked = relocked, lockoutRemainingMs = 0, epoch = it.epoch + 1)
            }
        }
        true
    }

    /**
     * Removes an existing PIN after verifying [currentPin]. Requires a PIN that is unlocked.
     * A failed record removal leaves the PIN in place and returns [PinAttempt.Unavailable].
     */
    suspend fun removePin(currentPin: String): PinAttempt = attempt(
        pin = currentPin,
        precheck = { it.pinStatus == PinStatus.On && !it.locked },
    ) {
        if (!clearRecord()) {
            PinAttempt.Unavailable
        } else {
            writeThrottle(ThrottleState())
            synchronized(stateLock) {
                known = AppPinRecordRead.Absent
                authorization = null
                _state.update {
                    it.copy(
                        pinStatus = PinStatus.Off,
                        locked = false,
                        lockoutRemainingMs = 0,
                        epoch = it.epoch + 1,
                    )
                }
            }
            PinAttempt.Success
        }
    }

    /**
     * Shared verifying path: reconcile, precheck, throttle, hash, then [onSuccess].
     *
     * The attempt is counted and the *would-be* lockout is persisted before hashing starts, so an
     * interrupted verification can only consume an attempt. While hashing, [AppLockState.busy] is
     * true and the mutex keeps every other verification queued.
     */
    private suspend fun attempt(
        pin: String,
        precheck: (AppLockState) -> Boolean,
        upgradeIterations: Boolean = false,
        onSuccess: (AppPinRecord) -> PinAttempt,
    ): PinAttempt = mutex.withLock {
        reconcile()
        val current = _state.value
        // An unreadable (or absent) record can never verify: refuse without hashing or counting.
        if (current.pinStatus != PinStatus.On) return@withLock PinAttempt.Unavailable
        if (!precheck(current)) return@withLock PinAttempt.Unavailable
        // onBackground() does not wait for this mutex: a re-lock during hashing bumps the epoch.
        val epoch = current.epoch
        _state.update { it.copy(busy = true) }
        try {
            val now = clock.elapsedRealtimeMs()
            val bootId = clock.bootId()
            val throttle = readThrottle()
            val remaining = lockoutRemaining(throttle, now, bootId, persistRestart = true)
            if (remaining > 0) {
                _state.update { it.copy(lockoutRemainingMs = remaining) }
                return@withLock PinAttempt.LockedOut(remaining)
            }

            val failures = throttle.failures + 1
            val lockoutMs = AppPinThrottle.lockoutMillisAfter(failures)
            val pending = ThrottleState(
                failures = failures,
                lockoutStartElapsedMs = if (lockoutMs > 0) now else 0L,
                lockoutMillis = lockoutMs,
                lockoutBootId = if (lockoutMs > 0) bootId else null,
            )
            // Count first, verify second: a process kill mid-hash cannot dodge the counter.
            if (!writeThrottle(pending)) return@withLock PinAttempt.Unavailable

            val record = (known as? AppPinRecordRead.Valid)?.record
                ?: return@withLock PinAttempt.Unavailable
            val verified = pin.isNotEmpty() &&
                pin.length <= AppPinPolicy.MAX_ENTRY_LENGTH &&
                withContext(hashDispatcher) { AppPinHasher.matches(pin, record) }
            if (!verified) {
                _state.update { it.copy(lockoutRemainingMs = lockoutMs) }
                return@withLock PinAttempt.Wrong(lockoutMs)
            }

            // An externally replaced record must not be unlocked by a hash of the old one.
            if (!isKnown(record)) return@withLock PinAttempt.Unavailable
            writeThrottle(ThrottleState())
            // The PIN was right, but a re-lock landed while it was hashed: the result belongs to
            // a visit that already ended, so it must not unlock, authorize or remove anything.
            if (_state.value.epoch != epoch) return@withLock PinAttempt.Unavailable
            if (upgradeIterations && !upgradeRecord(pin, record)) {
                return@withLock PinAttempt.Unavailable
            }
            // The upgrade hashes again; re-validate with no suspension point before onSuccess.
            if (_state.value.epoch != epoch) return@withLock PinAttempt.Unavailable
            _state.update { it.copy(lockoutRemainingMs = 0) }
            onSuccess(record)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * Re-hashes a record saved at an older cost at the current [AppPinHasher.ITERATIONS] with a
     * fresh salt. Best effort: a storage failure keeps the old, still-valid record.
     *
     * @return false only when storage kept neither the upgraded nor the previous record.
     */
    private suspend fun upgradeRecord(pin: String, record: AppPinRecord): Boolean {
        if (record.iterations == AppPinHasher.ITERATIONS) return true
        val upgraded = withContext(hashDispatcher) { AppPinHasher.newRecord(pin, random) }
        if (!writeRecord(AppPinRecordCodec.encode(upgraded))) return true
        val readBack = readRecord()
        if (readBack == AppPinRecordRead.Valid(upgraded)) {
            synchronized(stateLock) { known = readBack }
            return true
        }
        synchronized(stateLock) {
            known = readBack
            val status = statusOf(readBack)
            _state.update {
                it.copy(pinStatus = status, locked = status != PinStatus.Off, epoch = it.epoch + 1)
            }
        }
        return false
    }

    /** Re-reads the record and applies any external change. Returns the resulting state. */
    private fun reconcile(): AppLockState {
        synchronized(stateLock) {
            val current = readRecord()
            if (current != known) {
                known = current
                authorization = null
                val status = statusOf(current)
                _state.update {
                    it.copy(pinStatus = status, locked = status != PinStatus.Off, epoch = it.epoch + 1)
                }
            }
            return _state.value
        }
    }

    private fun isKnown(record: AppPinRecord): Boolean =
        synchronized(stateLock) { (known as? AppPinRecordRead.Valid)?.record == record }

    private fun lockoutRemaining(persistRestart: Boolean): Long {
        val now = clock.elapsedRealtimeMs()
        return lockoutRemaining(readThrottle(), now, clock.bootId(), persistRestart)
    }

    private fun lockoutRemaining(
        throttle: ThrottleState,
        now: Long,
        bootId: String,
        persistRestart: Boolean,
    ): Long {
        if (throttle.lockoutMillis <= 0) return 0L
        val remaining = AppPinThrottle.remainingMillis(throttle, now, bootId)
        if (remaining > 0 && throttle.lockoutBootId != bootId && persistRestart) {
            // A new boot restarts the window in full; persist the new anchor so it cannot replay.
            writeThrottle(throttle.copy(lockoutStartElapsedMs = now, lockoutBootId = bootId))
        } else if (remaining == 0L && throttle.lockoutBootId == bootId && persistRestart) {
            // A window observed to have ended on this boot is closed (the failure count stays),
            // so a later reboot cannot restart a lockout the user already waited out.
            writeThrottle(throttle.copy(lockoutStartElapsedMs = 0L, lockoutMillis = 0L, lockoutBootId = null))
        }
        return remaining
    }

    private fun readRecord(): AppPinRecordRead = runCatching {
        AppPinRecordCodec.decode(storage.readRecordRaw())
    }.getOrElse { AppPinRecordRead.Unreadable("storage read failed") }

    private fun readThrottle(): ThrottleState = runCatching { storage.readThrottle() }.getOrElse {
        ThrottleState(failures = 11, lockoutMillis = AppPinThrottle.MAX_LOCKOUT_MILLIS)
    }

    private fun writeRecord(raw: String): Boolean =
        runCatching { storage.writeRecordRaw(raw) }.getOrDefault(false)

    private fun clearRecord(): Boolean =
        runCatching { storage.clearRecord() }.getOrDefault(false)

    private fun writeThrottle(state: ThrottleState): Boolean =
        runCatching { storage.writeThrottle(state) }.getOrDefault(false)

    private fun initialState(): AppLockState {
        val status = statusOf(known)
        return AppLockState(
            pinStatus = status,
            locked = status != PinStatus.Off,
            lockoutRemainingMs = lockoutRemaining(persistRestart = false),
        )
    }

    private fun statusOf(read: AppPinRecordRead): PinStatus = when (read) {
        AppPinRecordRead.Absent -> PinStatus.Off
        is AppPinRecordRead.Valid -> PinStatus.On
        is AppPinRecordRead.Unreadable -> PinStatus.Unreadable
    }
}

/** Process-wide app-lock controller backed by SharedPreferences and the device clocks. */
object AppLock {

    @Volatile
    private var instance: AppLockController? = null

    fun controller(context: Context): AppLockController {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appContext = context.applicationContext
            return AppLockController(
                storage = SharedPreferencesAppLockStorage(appContext),
                clock = SystemAppLockClock(appContext),
            ).also { instance = it }
        }
    }
}
