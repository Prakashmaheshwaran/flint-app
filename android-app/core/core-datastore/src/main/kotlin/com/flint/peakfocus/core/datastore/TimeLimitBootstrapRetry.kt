package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * R-18b in-process retry of a first-open capture whose device zone/clock discovery failed.
 * DataStore runs [TimeLimitBootstrapMigration] once per process, so without this a failed
 * discovery left Time unmeasured until a later process. Each attempt is the same migrate()
 * in its own serialized edit: it writes only the marker, only while the marker is absent,
 * so it can never double-capture, re-zone an established ledger or touch legacy usage.
 * A failed attempt writes nothing; Time stays fail-closed (retryable IO) until a success.
 * After this attempt itself publishes a Ready capture, [onCaptured] runs the ordinary
 * editor preparation that initializes the ledger, as a fresh-process first load would.
 */
internal class TimeLimitBootstrapRetry(
    private val store: DataStore<Preferences>,
    private val migration: TimeLimitBootstrapMigration,
    private val onCaptured: suspend () -> Unit,
    /** Null disables the backoff timer (fixtures drive [attempt] directly). */
    private val scope: CoroutineScope?,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val initialBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 60_000,
) {
    private val mutex = Mutex()
    @Volatile var settled = false; private set
    private var nextAttemptAtMs: Long? = null
    private var backoffMs = initialBackoffMs
    private var timer: Job? = null

    /** Called for every emission of the wrapped data; a marker of any kind ends retrying. */
    suspend fun observe(prefs: Preferences) {
        if (settled) return
        if (!migration.shouldMigrate(prefs)) settled = true else schedule()
    }

    /** Called before writes. Bounded by the same backoff gate as the timer. */
    suspend fun attempt() {
        if (settled) return
        var published: TimeLimitBootstrapCapture? = null
        mutex.withLock {
            if (settled || nextAttemptAtMs?.let { monotonicMs() < it } == true) return
            val result = try {
                store.updateData { prefs ->
                    migration.migrate(prefs).also { next -> published = if (next !== prefs) readTimeLimitBootstrapCapture(next) else null }
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (result != null && !migration.shouldMigrate(result)) settled = true else {
                published = null
                nextAttemptAtMs = monotonicMs() + backoffMs
                backoffMs = minOf(backoffMs * 2, maxBackoffMs)
            }
        }
        if (published is TimeLimitBootstrapCapture.Ready) try { onCaptured() }
        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            // The capture is durable; any later writer's editor preparation initializes the ledger.
        }
        if (!settled) schedule()
    }

    private fun schedule() {
        val owner = scope ?: return
        synchronized(this) {
            if (settled || timer?.isActive == true) return
            timer = owner.launch {
                while (!settled) {
                    val wait = mutex.withLock { nextAttemptAtMs }?.let { it - monotonicMs() } ?: 0L
                    if (wait > 0) delay(wait)
                    attempt()
                }
            }
        }
    }
}
