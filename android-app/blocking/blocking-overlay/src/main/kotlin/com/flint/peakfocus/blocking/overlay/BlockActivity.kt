package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.BreakDecision
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.feature.blockscreen.BlockScreen
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Full-screen fallback host for the shared `BlockScreen` (feature-blockscreen) — used on
 * Path B when the SYSTEM_ALERT_WINDOW grant is missing, so the overlay can't be drawn.
 * Launched with FLAG_ACTIVITY_NEW_TASK from an *active* foreground service (never from the
 * cold background; see the Android-15 note in the Path B service).
 *
 * Same host contract as the overlay surfaces: this Activity owns the clock (1s re-render),
 * and delegates break granting, HARDER cooldown accounting and Emergency-Pass consumption to
 * the shared Path B [BlockScreenCoordinator] (via [PathBBlockHandoff]) — so state stays
 * identical whichever surface the user happens to see. Edge-to-edge, so the ember glow runs
 * behind the system bars like it does in the raw overlay windows. All colors come from
 * [FlintTheme] tokens; the window background is set from the theme at runtime (nothing
 * hard-coded, the old hex in themes.xml is gone).
 *
 * Refused exits — a Back press, a Hardcore break denial, a spent Emergency Pass — feed the
 * screen's nudge counter instead of silently no-oping: the block answers firmly (shake +
 * reject haptic) without ever opening.
 */
class BlockActivity : ComponentActivity() {

    /** One block launch, resolved from its intent extras. */
    private data class BlockedTarget(
        val packageName: String,
        val label: String?,
        val cause: BlockCause,
    )

    /** The blocked target as Compose-observable state: [onNewIntent] swaps it in place of
     *  recreate(), so the glow and breathing mark run continuously across re-used instances. */
    private var target by mutableStateOf<BlockedTarget?>(null)

    /** Refusal counter fed to `BlockScreen`'s nudge parameter — increments animate. */
    private var nudge by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before setContent, so the glow draws behind the system bars on Android 14-and-below
        // too (the shared screen already safeDrawingPadding()s its content).
        enableEdgeToEdge()
        val initial = intent.toBlockedTarget()
        if (initial == null) {
            // Legacy/malformed launch (no package to account against): nothing to enforce.
            finish()
            return
        }
        target = initial

        // Back must not leave the block screen. A dispatcher callback (predictive-back safe)
        // replaces the old deprecated onBackPressed override — and instead of swallowing the
        // press silently it nudges: the screen shakes and reject-buzzes, firm but not dead.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    nudge++
                }
            },
        )

        val coordinator = PathBBlockHandoff.coordinator(applicationContext)

        setContent {
            FlintTheme(darkTheme = true) {
                // Window background from the theme token — the XML theme stays palette-free
                // (design tokens are single-source); its only jobs are the transparent bars
                // for edge-to-edge and the system dark background for the one frame before
                // Compose draws — accepted.
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                }

                val current = target ?: return@FlintTheme
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var refresh by remember { mutableIntStateOf(0) }

                // Host-owned clock: tick the countdowns; leave when the block window ends or
                // an exemption (granted here or on another surface) starts covering the app.
                // Keyed on the target so a block swapped in by onNewIntent tracks its own
                // window.
                LaunchedEffect(current) {
                    while (isActive) {
                        delay(1_000L)
                        now = System.currentTimeMillis()
                        val endsAt = current.cause.endsAtEpochMs
                        val over = endsAt != null && now >= endsAt
                        if (over || BlockExemptions.isExempt(current.packageName, now)) {
                            finish()
                            break
                        }
                    }
                }

                val state = remember(current, now, refresh) {
                    blockScreenState(
                        current.packageName,
                        current.label,
                        current.cause,
                        coordinator.currentBreakSession(),
                        now,
                        coordinator.isEmergencyPassAvailable(),
                    )
                }
                BlockScreen(
                    state = state,
                    onDismiss = {
                        goHome()
                        finish()
                    },
                    onOpenFlint = {
                        openFlint()
                        finish()
                    },
                    onBreakRequested = {
                        when (coordinator.requestBreak(current.packageName, current.cause)) {
                            is BreakDecision.Granted -> finish() // exemption granted; app is free
                            is BreakDecision.Pending -> refresh++ // show the cooldown pill now
                            BreakDecision.DeniedHardcore -> nudge++ // refusal, made visible
                        }
                    },
                    onEmergencyPassRequested = {
                        if (coordinator.requestEmergencyPass(current.packageName, current.cause)) {
                            finish()
                        } else {
                            nudge++ // this week's pass is already spent — the block stands
                        }
                    },
                    nudge = nudge,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a new block (possibly another app) re-used this instance. Swapping the
        // observable target crossfades the words in place — no recreate(), so the glow and
        // the breathing mark never restart. A malformed re-launch keeps the current block.
        setIntent(intent)
        intent.toBlockedTarget()?.let { target = it }
    }

    /** Parses a launch/re-launch intent; null when there's no package to account against. */
    private fun Intent.toBlockedTarget(): BlockedTarget? {
        val blockedPackage = getStringExtra(EXTRA_BLOCKED_PACKAGE)
        if (blockedPackage.isNullOrBlank()) return null
        return BlockedTarget(
            packageName = blockedPackage,
            label = getStringExtra(EXTRA_BLOCKED_LABEL),
            cause = BlockCause(
                reason = enumExtra(EXTRA_REASON, BlockScreenReason.MANUAL_SESSION),
                breakLevel = enumExtra(EXTRA_BREAK_LEVEL, BreakLevel.EASY),
                endsAtEpochMs = getLongExtra(EXTRA_ENDS_AT_EPOCH_MS, NO_END).takeIf { it > 0L },
            ),
        )
    }

    private fun goHome() = runCatching {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun openFlint() = runCatching {
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
    }

    private inline fun <reified T : Enum<T>> Intent.enumExtra(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(getStringExtra(key).orEmpty()) }.getOrDefault(fallback)

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "flint.blocked_package"
        const val EXTRA_BLOCKED_LABEL = "flint.blocked_label"
        const val EXTRA_REASON = "flint.block_reason"
        const val EXTRA_BREAK_LEVEL = "flint.break_level"
        const val EXTRA_ENDS_AT_EPOCH_MS = "flint.ends_at_epoch_ms"
        private const val NO_END = -1L

        /** Launch the fallback block screen for [packageName] with a resolved [cause]. */
        fun launch(context: Context, packageName: String, label: String?, cause: BlockCause) {
            // Best-effort: without SYSTEM_ALERT_WINDOW, background-activity-launch limits may
            // block this too — Path B degrades honestly (strategy doc §3).
            runCatching {
                context.startActivity(
                    Intent(context, BlockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_BLOCKED_PACKAGE, packageName)
                        .putExtra(EXTRA_BLOCKED_LABEL, label)
                        .putExtra(EXTRA_REASON, cause.reason.name)
                        .putExtra(EXTRA_BREAK_LEVEL, cause.breakLevel.name)
                        .putExtra(EXTRA_ENDS_AT_EPOCH_MS, cause.endsAtEpochMs ?: NO_END),
                )
            }
        }
    }
}
