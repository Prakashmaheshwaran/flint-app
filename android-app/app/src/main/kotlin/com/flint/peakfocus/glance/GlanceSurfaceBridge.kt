package com.flint.peakfocus.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface GlanceFocusReader {
    val changes: Flow<Unit>
    suspend fun read(): FocusTodayReading
}

object UnavailableFocusReader : GlanceFocusReader {
    override val changes: Flow<Unit> = flowOf(Unit)
    override suspend fun read() = FocusTodayReading()
}

/** App composition only. No display snapshot is saved as protection authority. */
object GlanceSurfaceBridge {
    private var protection: GlanceProtectionReader = UnavailableProtectionReader
    private var focus: GlanceFocusReader = UnavailableFocusReader
    private val lock = Mutex()
    private var observer: Job? = null
    private var boundary: Job? = null
    private val mutableDisplay = MutableStateFlow(GlanceDisplayMapper.map(
        ProtectionReading(), FocusTodayReading(), 0, 0,
    ))
    val display: StateFlow<GlanceDisplay> = mutableDisplay.asStateFlow()

    fun start(
        context: Context,
        scope: CoroutineScope,
        protectionReader: GlanceProtectionReader = UnavailableProtectionReader,
        focusReader: GlanceFocusReader = UnavailableFocusReader,
    ) {
        observer?.cancel()
        boundary?.cancel()
        protection = protectionReader
        focus = focusReader
        val app = context.applicationContext
        observer = scope.launch {
            merge(protection.changes.catch { emit(Unit) }, focus.changes.catch { emit(Unit) })
                .onStart { emit(Unit) }.conflate().collect {
                refresh(app)
                boundary?.cancel()
                display.value.refreshAtElapsedMs?.let { at ->
                    boundary = scope.launch {
                        delay((at - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                        refresh(app)
                    }
                }
            }
        }
    }

    suspend fun readDisplay(): GlanceDisplay = lock.withLock {
        readGlanceDisplay(protection, focus, SystemClock::elapsedRealtime, System::currentTimeMillis)
            .also { mutableDisplay.value = it }
    }

    suspend fun refresh(context: Context) {
        val state = readDisplay()
        // Widget/launcher failures must not cancel the app's independent observation job.
        guardedGlanceRead({ Unit }, timeoutMs = 8_000) {
            scheduleRefresh(context, state.refreshAtElapsedMs)
            FlintGlanceWidget().updateAll(context)
        }
    }

    private fun scheduleRefresh(context: Context, elapsedMs: Long?) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = PendingIntent.getBroadcast(context, 0,
            Intent(context, GlanceRefreshReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (elapsedMs == null) alarm.cancel(intent)
        else alarm.set(AlarmManager.ELAPSED_REALTIME, elapsedMs, intent) // Inexact; never enforcement.
    }
}

class GlanceRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeout(8_000) { GlanceSurfaceBridge.refresh(context.applicationContext) }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                // Optional rendering can be deferred by the launcher; never crash Flint.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the cached, observation-qualified surface until the next update.
            } finally {
                pending.finish()
            }
        }
    }
}
