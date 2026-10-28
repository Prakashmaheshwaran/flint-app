package com.flint.peakfocus.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.blocking.engine.ProtectionContract
import com.flint.peakfocus.core.model.*
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow

/** Fresh records read inside one serialized flint_preferences edit, after editor preparation. */
data class ProtectionSnapshot(
    val focus: FocusSnapshot,
    val rules: List<BlockRule>,
    val times: List<TimeLimit>,
    val opens: List<OpenLimit>,
    val editor: EditorCommitmentState,
    val harder: HarderWaitPolicy.Ledger,
    val observedAt: AccessClockStamp,
)

data class ProtectionMutation<R>(
    val focus: FocusSnapshot,
    val result: R,
    val rules: List<BlockRule>? = null,
    val times: List<TimeLimit>? = null,
    val opens: List<OpenLimit>? = null,
    val editor: EditorCommitmentState? = null,
    val harder: HarderWaitPolicy.Ledger? = null,
)

sealed interface ProtectionCommit<out R> {
    data class Committed<R>(val snapshot: ProtectionSnapshot, val result: R) : ProtectionCommit<R>
    data class Unavailable(val reason: StorageFailure) : ProtectionCommit<Nothing>
}

interface ProtectionTransactionStore {
    val state: Flow<StorageState<ProtectionSnapshot>>
    /** Reducer is pure; only the returned acknowledgement authorizes any desired release. */
    suspend fun <R> transact(transform: (ProtectionSnapshot) -> ProtectionMutation<R>): ProtectionCommit<R>
}

class DataStoreProtectionTransactionStore(
    private val dataStore: DataStore<Preferences>,
    private val clockAtCommit: () -> AccessClockStamp,
    private val defaultZone: () -> String = { TimeZone.getDefault().id },
) : ProtectionTransactionStore {
    private val editors = DataStoreEditorCommitmentStore(dataStore, defaultZone = defaultZone)

    override val state: Flow<StorageState<ProtectionSnapshot>> = dataStore.data.storageStates { prefs ->
        readSnapshot(prefs, clockAtCommit())
    }

    override suspend fun <R> transact(transform: (ProtectionSnapshot) -> ProtectionMutation<R>): ProtectionCommit<R> {
        var committed: ProtectionCommit.Committed<R>? = null
        return try {
            dataStore.edit { prefs ->
                val before = prefs.asMap().toMap()
                val now = clockAtCommit()
                val original = readFocus(prefs)
                val archive = if (original.protection == null) legacyArchive(prefs) else null
                // The editor owns migration, due queued edits and occurrence materialization;
                // no independent occurrence resolver or Harder count ledger is introduced.
                editors.prepare(prefs, now)
                var current = readSnapshot(prefs, now)
                if (current.focus.protection == null) {
                    val initial = migrateLegacyPass(current.focus, now)
                    prefs[PrefsKeys.PROTECTION_ARCHIVE] = checkNotNull(archive)
                    current = current.copy(focus = current.focus.copy(protection = initial,
                        // Old package receipts lack the global/frozen recovery scope. Preserve
                        // their spend/archive, but never let them masquerade as ADR-012 access.
                        exemptions = current.focus.exemptions.filterValues { it.kind != ExemptionKind.EMERGENCY_PASS }))
                }
                val mutation = transform(current)
                mutation.rules?.let(prefs::putEditorRules)
                mutation.times?.let { next ->
                    val raw = PrefsCodec.encodeTimeLimits(next); StrictPrefsCodec.timeLimits(raw)
                    prefs[PrefsKeys.TIME_LIMITS] = raw
                }
                mutation.opens?.let { next ->
                    val raw = PrefsCodec.encodeOpenLimits(next); StrictPrefsCodec.openLimits(raw)
                    prefs[PrefsKeys.OPEN_LIMITS] = raw
                }
                mutation.editor?.let(prefs::putEditorState)
                mutation.harder?.let(prefs::putHarderWait)
                // Include editor/config changes in the store-owned focus revision too. The
                // acknowledgement projection can reject an older pre-commit reader snapshot.
                val changedOther = mutation.rules != null || mutation.times != null || mutation.opens != null || mutation.editor != null || mutation.harder != null
                writeFocus(prefs, original, mutation.focus, forceRevision = changedOther || prefs.asMap() != before)
                committed = ProtectionCommit.Committed(readSnapshot(prefs, now), mutation.result)
            }
            checkNotNull(committed)
        } catch (cause: Exception) {
            ProtectionCommit.Unavailable(storageFailure(cause) ?: throw cause)
        }
    }

    private fun readSnapshot(prefs: Preferences, now: AccessClockStamp) = ProtectionSnapshot(
        readFocus(prefs), prefs.editorRules(), prefs.editorTimes(), prefs.editorOpens(),
        readEditorState(prefs), readHarderWait(prefs), now,
    )

    private fun migrateLegacyPass(focus: FocusSnapshot, now: AccessClockStamp): ProtectionLedger {
        val used = focus.emergencyPass.usedAtEpochMs
        if (used != null && used !in 0..now.wallEpochMs) wireCorrupt()
        if (used == null && focus.exemptions.values.any { it.kind == ExemptionKind.EMERGENCY_PASS }) wireCorrupt()
        val cooldown = used?.let {
            val remaining = (ProtectionContract.PASS_COOLDOWN_MS - (now.wallEpochMs - it)).coerceAtLeast(0)
            if (remaining == 0L) null else ProtectionCooldown("legacy-pass-$it", it, now, remaining)
        }
        return ProtectionLedger(now, now, cooldown = cooldown, lastUsedAtEpochMs = used,
            previousAccessEnded = focus.exemptions.values.any { it.kind == ExemptionKind.EMERGENCY_PASS })
    }

    private fun legacyArchive(prefs: Preferences): String = buildList {
        add("A|1")
        for (key in listOf(PrefsKeys.FOCUS_LEDGER, PrefsKeys.BREAK_SESSION, PrefsKeys.OPEN_COUNTS, PrefsKeys.DEFAULT_BREAK_LEVEL)) {
            val raw = StrictPrefsCodec.string(prefs, key)
            add(listOf(PrefsCodec.escape(key.name), (raw != null).toString(), PrefsCodec.escape(raw.orEmpty())).joinToString("|"))
        }
        for (key in listOf(PrefsKeys.EMERGENCY_PASS_USED_AT, PrefsKeys.EMERGENCY_PASS_WEEK_START, PrefsKeys.OPEN_COUNTS_DAY)) {
            val raw = StrictPrefsCodec.long(prefs, key)
            add("${PrefsCodec.escape(key.name)}|${raw != null}|${raw?.toString().orEmpty()}")
        }
    }.joinToString("\n")
}
