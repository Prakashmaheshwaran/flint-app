package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
 * identical whichever surface the user happens to see. All colors come from [FlintTheme]
 * tokens; the window background is set from the theme at runtime (nothing hard-coded, the
 * old hex in themes.xml is gone).
 */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE)
        if (blockedPackage.isNullOrBlank()) {
            // Legacy/malformed launch (no package to account against): nothing to enforce.
            finish()
            return
        }
        val label = intent.getStringExtra(EXTRA_BLOCKED_LABEL)
        val cause = BlockCause(
            reason = enumExtra(EXTRA_REASON, BlockScreenReason.MANUAL_SESSION),
            breakLevel = enumExtra(EXTRA_BREAK_LEVEL, BreakLevel.EASY),
            endsAtEpochMs = intent.getLongExtra(EXTRA_ENDS_AT_EPOCH_MS, NO_END).takeIf { it > 0L },
        )
        val coordinator = PathBBlockHandoff.coordinator(applicationContext)

        setContent {
            FlintTheme(darkTheme = true) {
                // Window background from the theme token — replaces the hard-coded hex the
                // XML theme used to carry (design tokens are single-source).
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                }

                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var refresh by remember { mutableIntStateOf(0) }

                // Host-owned clock: tick the countdowns; leave when the block window ends or
                // an exemption (granted here or on another surface) starts covering the app.
                LaunchedEffect(Unit) {
                    while (isActive) {
                        delay(1_000L)
                        now = System.currentTimeMillis()
                        val over = cause.endsAtEpochMs != null && now >= cause.endsAtEpochMs
                        if (over || BlockExemptions.isExempt(blockedPackage, now)) {
                            finish()
                            break
                        }
                    }
                }

                val state = remember(now, refresh) {
                    blockScreenState(blockedPackage, label, cause, coordinator.currentBreakSession(), now)
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
                        when (coordinator.requestBreak(blockedPackage, cause)) {
                            is BreakDecision.Granted -> finish() // exemption granted; app is free
                            is BreakDecision.Pending -> refresh++ // show the cooldown pill now
                            BreakDecision.DeniedHardcore -> Unit
                        }
                    },
                    onEmergencyPassRequested = {
                        if (coordinator.requestEmergencyPass(blockedPackage, cause)) finish()
                        // else: this week's pass is already spent — the block stands.
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a new block (possibly another app) re-used this instance — rebuild.
        setIntent(intent)
        recreate()
    }

    // Block screens shouldn't be dismissible by Back. Re-trigger logic handles Home eviction.
    @Deprecated("Intentionally swallow Back on the block screen")
    override fun onBackPressed() = Unit

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

    private inline fun <reified T : Enum<T>> enumExtra(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(intent.getStringExtra(key).orEmpty()) }.getOrDefault(fallback)

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
