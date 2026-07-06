package com.flint.peakfocus

import android.app.Application
import com.flint.peakfocus.blocking.resilience.BootReceiver
import com.flint.peakfocus.blocking.resilience.TimeChangeReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Flint application entry point — the app-side integration wiring lives here:
 *
 *  - [ActiveRulesBridge] publishes the DataStore rules the Blocklist tab writes into the
 *    engine's in-memory rule list — its own holder lane, merged with the lane the legacy
 *    quick blocklist (core-data) publishes for itself.
 *  - Boot re-arm: registers the hook [BootReceiver] runs after BOOT_COMPLETED, so the
 *    Path B fallback service comes back when it is the active path (the resilience module
 *    deliberately does not depend on blocking-usagestats — this seam is documented there).
 *  - Time-change guard: baselines [TimeChangeReceiver]'s clock anchor (every process start,
 *    which also covers reboots — elapsedRealtime restarts there) and registers the same
 *    Path B gate hook to run after a clock/timezone change re-arm.
 *
 * Still future: notification channels owned here, a real dependency graph.
 */
class FlintApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ActiveRulesBridge.start(this, appScope)
        BootReceiver.registerRearmAction { context -> PathBServiceGate.sync(context) }
        TimeChangeReceiver.baselineAnchor(this)
        TimeChangeReceiver.registerRearmAction { context -> PathBServiceGate.sync(context) }
    }
}
