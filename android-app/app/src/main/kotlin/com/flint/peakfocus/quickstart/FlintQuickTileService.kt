package com.flint.peakfocus.quickstart

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateFormat
import androidx.annotation.RequiresApi
import com.flint.peakfocus.R
import com.flint.peakfocus.blocking.overlay.ProtectionStatusHub
import com.flint.peakfocus.core.model.EnforcementSignals
import com.flint.peakfocus.glance.coordinatorGlanceReading
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Start focus" Quick Settings tile (3.2). Shows the coordinator's last same-boot check
 * through [QuickTileMapper]; a tap opens Flint's Start focus sheet through the non-exported
 * [QuickStartActivity] (after the device unlocks). It has no toggle: it never starts, stops,
 * pauses or shortens a session. Only the system can bind it (BIND_QUICK_SETTINGS_TILE).
 */
@RequiresApi(Build.VERSION_CODES.N)
class FlintQuickTileService : TileService() {
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listening = scope
        // Ask the live detectors for a current check (as opening the Start sheet does). This
        // only re-applies the saved policy; it creates, ends or changes no session.
        runCatching { EnforcementSignals.detectors.requestEvaluation() }
        scope.launch {
            ProtectionStatusHub.status.collectLatest { status ->
                while (isActive) {
                    val display = QuickTileMapper.map(
                        coordinatorGlanceReading(status),
                        SystemClock.elapsedRealtime(),
                        System.currentTimeMillis(),
                        ::formatEnd,
                    )
                    render(display)
                    val at = display.refreshAtElapsedMs ?: break
                    delay((at - SystemClock.elapsedRealtime()).coerceAtLeast(MIN_REFRESH_MS))
                }
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        listening?.cancel()
        listening = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        // A locked device unlocks first; Flint's own app-open PIN (if set) still gates the UI.
        if (isLocked) unlockAndRun { openStartFocus() } else openStartFocus()
    }

    private fun openStartFocus() {
        val intent = QuickStartIntents.startFocus(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, REQUEST_OPEN, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(display: QuickTileDisplay) {
        val tile = qsTile ?: return
        tile.state = if (display.mode == QuickTileMode.ACTIVE) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_focus)
        tile.label = display.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = display.subtitle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = display.stateDescription
        tile.contentDescription = display.contentDescription
        tile.updateTile()
    }

    private fun formatEnd(epochMs: Long): String = QuickTileMapper.formatTime(
        epochMs, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault(),
        DateFormat.is24HourFormat(this),
    )

    private companion object {
        const val REQUEST_OPEN = 3201
        const val MIN_REFRESH_MS = 250L
    }
}
