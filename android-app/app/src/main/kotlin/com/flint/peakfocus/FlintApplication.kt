package com.flint.peakfocus

import android.app.Application
import com.flint.peakfocus.blocking.resilience.BootReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Flint application entry point — the app-side integration wiring lives here:
 *
 *  - [ActiveRulesBridge] keeps the engine's in-memory rule list fed from both the legacy
 *    quick blocklist (core-data) and the DataStore rules the Blocklist tab writes.
 *  - Boot re-arm: registers the hook [BootReceiver] runs after BOOT_COMPLETED, so the
 *    Path B fallback service comes back when it is the active path (the resilience module
 *    deliberately does not depend on blocking-usagestats — this seam is documented there).
 *
 * Still future: notification channels owned here, a real dependency graph.
 */
class FlintApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ActiveRulesBridge.start(this, appScope)
        BootReceiver.registerRearmAction { context -> PathBServiceGate.sync(context) }
    }
}
