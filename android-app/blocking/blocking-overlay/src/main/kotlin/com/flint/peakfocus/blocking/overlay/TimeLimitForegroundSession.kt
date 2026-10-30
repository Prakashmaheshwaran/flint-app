package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.os.SystemClock
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.datastore.ProtectionClock
import com.flint.peakfocus.core.datastore.TimeLimitForegroundObservation
import com.flint.peakfocus.core.model.EnforcementSignals
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * One detector incarnation. Ordered requests survive cancellation of an evaluation job so
 * departures close the durable span. A visit token also fences a failed/dropped departure:
 * its later return can never bridge time spent on Home, the dialer, or a sleeping screen.
 */
class TimeLimitForegroundSession internal constructor(
    private val write: suspend (TimeLimitForegroundObservation, () -> Boolean) -> Unit,
    private val eligible: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stampFor: (Long, Long) -> com.flint.peakfocus.core.model.AccessClockStamp = { wall, elapsed ->
        ProtectionClock.now().copy(wallEpochMs = wall, elapsedRealtimeMs = elapsed)
    },
) {
    enum class Path { A, B }
    private data class Request(val previous: String?, val visit: String?, val pkg: String?,
        val wall: Long, val elapsed: Long, val close: Boolean, val done: CompletableDeferred<Unit>)
    private val owner = UUID.randomUUID().toString()
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var currentPackage: String? = null
    private var currentVisit: String? = null
    @Volatile private var retired = false

    init {
        scope.launch {
            try {
                for (request in requests) {
                    try {
                        val stamp = stampFor(request.wall, request.elapsed)
                        write(TimeLimitForegroundObservation(owner, request.previous, request.visit, request.pkg, stamp, request.close)) {
                            !retired && eligible()
                        }
                        request.done.complete(Unit)
                    } catch (cancelled: CancellationException) {
                        request.done.cancel(cancelled)
                    } catch (failure: Exception) {
                        request.done.completeExceptionally(failure)
                    }
                }
            } finally { scope.cancel() }
        }
    }

    /** Call on the detector's serialized event loop, before reading its budget or allowing a floor. */
    @Synchronized fun checkpoint(packageName: String?): Deferred<Unit> {
        if (retired || (packageName == null && currentPackage == null)) return CompletableDeferred(Unit)
        val previous = currentVisit
        if (packageName != currentPackage) {
            currentVisit = packageName?.let { UUID.randomUUID().toString() }
            currentPackage = packageName
        }
        return enqueue(previous, currentVisit, packageName, false)
    }

    /** Retire synchronously; delayed work from this service can no longer begin a span. */
    @Synchronized fun close(): Deferred<Unit> {
        if (retired) return CompletableDeferred(Unit)
        retired = true
        val result = enqueue(currentVisit, null, null, true)
        currentPackage = null; currentVisit = null
        requests.close()
        return result
    }

    private fun enqueue(previous: String?, visit: String?, pkg: String?, close: Boolean): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        val request = Request(previous, visit, pkg, System.currentTimeMillis(), SystemClock.elapsedRealtime(), close, done)
        if (!requests.trySend(request).isSuccess) done.completeExceptionally(IllegalStateException("retired Time observer"))
        return done
    }

    companion object {
        private val owners = mutableMapOf<Path, Any>()
        fun create(context: Context, path: Path): TimeLimitForegroundSession {
            val token = Any()
            synchronized(owners) { owners[path] = token }
            val store = FlintPreferences.timeLimitUsage(context.applicationContext)
            return TimeLimitForegroundSession(store::observeForeground, eligible = {
                synchronized(owners) { owners[path] === token } &&
                    (path == Path.A && EnforcementSignals.detectors.pathAConnected ||
                        path == Path.B && !EnforcementSignals.detectors.pathAConnected)
            })
        }
    }
}
