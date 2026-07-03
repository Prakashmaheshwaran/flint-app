package com.flint.peakfocus.blocking.resilience

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flint.peakfocus.core.data.BlocklistStore
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Boot re-arm (strategy doc §5.6/§6.1): refresh Flint's app-side enforcement state after a reboot.
 *
 * What this does NOT do: re-enable the AccessibilityService. The OS rebinds an *enabled* service
 * by itself after boot (android-app/README.md documents that, emulator-verified); if an OEM
 * silently disabled it, only the user can turn it back on, and [PermissionHealthChecker] is how
 * the UI finds out. Per ADR-007 / Play policy nothing here opens Settings or auto-redirects —
 * this module only observes and re-arms local state.
 *
 * What it does, in order:
 *  1. **Cache warm-up** — [BlocklistStore.load] projects the persisted blocklist + schedule into
 *     the in-memory `ActiveRulesHolder` (core-model) so the first detection event after boot sees
 *     live rules even if no service has run `onServiceConnected` yet.
 *  2. **Registered re-arm hooks** — runs every action added via [registerRearmAction]. The app
 *     shell registers these in `Application.onCreate` (a manifest receiver runs in the app
 *     process, so `onCreate` has already executed). This is the seam for e.g. restarting the
 *     path-B fallback service without this module depending on it.
 *
 * Android 15 note: a hook that starts a foreground service from here must use an FGS type that
 * may start from BOOT_COMPLETED. And boot re-arm is best-effort regardless — some OEM power
 * managers strip boot receivers from "optimized" apps, so the per-launch
 * [PermissionHealthChecker] check is the backstop, not this receiver.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldHandle(intent.action)) return
        val appContext = context.applicationContext

        // 1. Warm the in-memory rules cache from persisted state. Synchronous SharedPreferences
        //    read of a tiny file — well inside a receiver's time budget.
        runCatching { BlocklistStore(appContext).load() }

        // 2. App-registered re-arm hooks. Failures are isolated so one bad hook cannot
        //    break the rest of re-arm.
        for (action in rearmActions) {
            runCatching { action(appContext) }
        }
    }

    companion object {
        /**
         * The standard boot broadcast plus the OEM "fast boot" variants that replace it on some
         * devices (HTC Fast Boot and lookalikes). Kept in sync with this module's manifest.
         */
        private val HANDLED_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )

        private val rearmActions = CopyOnWriteArrayList<(Context) -> Unit>()

        /** True only for the boot(-ish) actions this receiver is registered for. */
        internal fun shouldHandle(action: String?): Boolean =
            action != null && action in HANDLED_ACTIONS

        /**
         * Register extra work to run after boot re-arm (call from `Application.onCreate`).
         * Actions run on the main thread inside the receiver's time budget — keep them quick
         * (e.g. a `startForegroundService` hand-off), and assume best-effort delivery.
         */
        fun registerRearmAction(action: (Context) -> Unit) {
            rearmActions += action
        }
    }
}
