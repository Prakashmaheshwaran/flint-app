package com.flint.peakfocus.quickstart

import android.content.Context
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.flint.peakfocus.R
import com.flint.peakfocus.blocking.overlay.ProtectionStatus
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One launcher shortcut, as plain data so selection and labels are testable. */
data class QuickStartShortcutSpec(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    val groupId: String?,
    val rank: Int,
)

/**
 * Long-press launcher shortcuts (3.2): "Start focus" plus up to [MAX_GROUPS] of the user's
 * saved App Groups. Every shortcut only opens the Start focus sheet through the non-exported
 * [QuickStartActivity] (a group pre-fills the apps); none starts, stops or changes a session.
 * All are dynamic and built from code, so the target survives an applicationId re-key.
 */
object QuickStartShortcuts {
    const val START_ID = "focus-start"
    const val GROUP_PREFIX = "focus-group-"
    const val MAX_GROUPS = 3
    private const val SHORT_MAX = 12
    private const val LONG_MAX = 25

    /** Groups with at least one app, name-sorted like the Rules editor's chip rail. */
    fun specs(groups: List<AppGroup>): List<QuickStartShortcutSpec> {
        val start = QuickStartShortcutSpec(START_ID, "Start focus", "Start focus", null, 0)
        val saved = groups.filter { it.apps.isNotEmpty() && it.id.isNotBlank() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { group: AppGroup -> group.name }.thenBy { it.id })
            .take(MAX_GROUPS)
            .mapIndexed { index, group ->
                val name = ProtectionStatus.sanitizeDisplayName(group.name).ifBlank { "Saved group" }
                QuickStartShortcutSpec(
                    id = GROUP_PREFIX + group.id,
                    shortLabel = ellipsize(name, SHORT_MAX),
                    longLabel = ellipsize("Focus: $name", LONG_MAX),
                    groupId = group.id,
                    rank = index + 1,
                )
            }
        return listOf(start) + saved
    }

    /** Keeps the published set in step with saved groups for as long as the process lives. */
    fun start(context: Context, scope: CoroutineScope): Job {
        val app = context.applicationContext
        return scope.launch {
            readySpecs(FlintPreferences.groups(app).groupsState).collect { specs -> publish(app, specs) }
        }
    }

    /** Unavailable storage publishes nothing: existing shortcuts stay until a real read. */
    internal fun readySpecs(groups: Flow<StorageState<List<AppGroup>>>): Flow<List<QuickStartShortcutSpec>> =
        groups.filterIsInstance<StorageState.Ready<List<AppGroup>>>().map { specs(it.value) }.distinctUntilChanged()

    /**
     * Replaces Flint's dynamic shortcuts only when they differ from [specs], so background
     * process starts don't spend the launcher's rate limit. Pinned copies of a deleted group
     * keep working: their tap opens the plain sheet with a "no longer saved" note.
     */
    fun publish(context: Context, specs: List<QuickStartShortcutSpec>): Boolean = try {
        val current = ShortcutManagerCompat.getDynamicShortcuts(context).map {
            listOf(it.id, it.shortLabel.toString(), it.longLabel?.toString(), it.rank)
        }.toSet()
        val wanted = specs.map { listOf(it.id, it.shortLabel, it.longLabel, it.rank) }.toSet()
        current == wanted || ShortcutManagerCompat.setDynamicShortcuts(context, specs.map { info(context, it) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: RuntimeException) {
        // Launcher shortcuts are a convenience; a rate limit or launcher failure is not fatal.
        Log.w("FlintShortcuts", "Couldn't publish launcher shortcuts", failure)
        false
    }

    private fun info(context: Context, spec: QuickStartShortcutSpec): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, spec.id)
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.longLabel)
            .setRank(spec.rank)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_focus))
            .setIntent(QuickStartIntents.startFocus(context, spec.groupId, spec.id))
            .build()

    private fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}
