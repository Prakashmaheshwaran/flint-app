package com.flint.peakfocus.quickstart

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.pm.ShortcutManagerCompat
import com.flint.peakfocus.MainActivity
import com.flint.peakfocus.feature.blocklist.FocusQuickStart
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One pending request plus the same-boot time it was posted. */
data class QuickStartPending(val request: FocusQuickStart, val postedElapsedMs: Long)

/**
 * Process-local hand-off from [QuickStartActivity] to Flint's UI (3.2). Nothing is persisted,
 * so a request never survives process death, and it expires after [MAX_AGE_MS]. Flint's UI
 * consumes it only once its content is composed: after Welcome/consent and after the app-open
 * PIN gate (if any), so these entries can never skip a gate the launcher icon would show.
 */
object QuickStartInbox {
    const val MAX_AGE_MS = 120_000L

    private val ids = AtomicLong()
    private val mutable = MutableStateFlow<QuickStartPending?>(null)
    val pending: StateFlow<QuickStartPending?> = mutable.asStateFlow()

    fun post(groupId: String?, nowElapsedMs: Long): FocusQuickStart {
        val request = FocusQuickStart(ids.incrementAndGet(), groupId)
        mutable.value = QuickStartPending(request, nowElapsedMs)
        return request
    }

    fun isFresh(pending: QuickStartPending, nowElapsedMs: Long): Boolean =
        nowElapsedMs >= pending.postedElapsedMs && nowElapsedMs - pending.postedElapsedMs < MAX_AGE_MS

    /** Clears only the request it names; a newer tap posted meanwhile stays pending. */
    fun consume(id: Long) = mutable.update { current -> current?.takeUnless { it.request.id == id } }

    internal fun resetForTest() { mutable.value = null }
}

/** The intents Flint's own tile and shortcuts send to the non-exported entry. */
object QuickStartIntents {
    /** Namespaced to the code package, not the applicationId, so a re-key needs no change. */
    const val ACTION_START_FOCUS = "com.flint.peakfocus.action.START_FOCUS"
    const val EXTRA_GROUP_ID = "com.flint.peakfocus.extra.GROUP_ID"
    const val EXTRA_SHORTCUT_ID = "com.flint.peakfocus.extra.SHORTCUT_ID"
    private const val MAX_ID_LENGTH = 128

    fun startFocus(context: Context, groupId: String? = null, shortcutId: String? = null): Intent =
        Intent(context, QuickStartActivity::class.java)
            .setAction(ACTION_START_FOCUS)
            .apply {
                groupId?.let { putExtra(EXTRA_GROUP_ID, it) }
                shortcutId?.let { putExtra(EXTRA_SHORTCUT_ID, it) }
            }

    /**
     * What a received intent asks for: null = just open Flint. Only the action and an opaque
     * saved-group id are read; the id is a lookup key that Flint resolves against its own
     * store, so a malformed one can only fail to pre-fill.
     */
    fun parse(intent: Intent?): Parsed? {
        if (intent?.action != ACTION_START_FOCUS) return null
        val group = runCatching { intent.getStringExtra(EXTRA_GROUP_ID) }.getOrNull()?.takeIf(::validId)
        val shortcut = runCatching { intent.getStringExtra(EXTRA_SHORTCUT_ID) }.getOrNull()?.takeIf(::validId)
        return Parsed(group, shortcut)
    }

    data class Parsed(val groupId: String?, val shortcutId: String?)

    private fun validId(value: String): Boolean =
        value.length in 1..MAX_ID_LENGTH && value.none { it.isWhitespace() || it.isISOControl() }
}

/**
 * Non-exported entry used only by Flint's own Quick Settings tile and launcher shortcuts
 * (the system launches both as Flint itself). Other apps cannot start it. It posts an
 * in-process "open Start focus" request and brings MainActivity forward. It never saves,
 * starts, stops, pauses or changes a session; Begin in Flint is the only commit (ADR-012).
 */
class QuickStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            QuickStartIntents.parse(intent)?.let { parsed ->
                QuickStartInbox.post(parsed.groupId, SystemClock.elapsedRealtime())
                parsed.shortcutId?.let { id -> runCatching { ShortcutManagerCompat.reportShortcutUsed(this, id) } }
            }
            // Reorder, never clear: no other Flint activity (for example a block screen) is
            // finished by this entry. An existing MainActivity is reused, not duplicated.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        finish()
    }
}
