package com.flint.peakfocus.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Production entry point for the DataStore-backed stores. Lives in its own file so the
 * process-wide delegate below is only initialized when production code actually asks for a
 * store — JVM tests construct the `DataStore*Store` classes directly with a fake and never
 * touch this. All three stores share the single "flint_preferences" file.
 */

/** The single Preferences DataStore backing all Flint preference stores. */
private val Context.flintPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "flint_preferences",
)

object FlintPreferences {

    fun harderWait(context: Context): HarderWaitStore =
        DataStoreHarderWaitStore(context.applicationContext.flintPreferencesDataStore)

    fun editorCommitments(context: Context): EditorCommitmentStore =
        DataStoreEditorCommitmentStore(context.applicationContext.flintPreferencesDataStore, clockAtCommit = ProtectionClock::now)

    fun blockRules(context: Context): BlockRulesStore =
        DataStoreBlockRulesStore(context.applicationContext.flintPreferencesDataStore)

    fun groups(context: Context): GroupsStore =
        DataStoreGroupsStore(context.applicationContext.flintPreferencesDataStore)

    fun limits(context: Context): LimitsStore =
        EditorMigratingLimitsStore(context.applicationContext.flintPreferencesDataStore)

    fun focusState(context: Context): FocusStateStore =
        DataStoreFocusStateStore(context.applicationContext.flintPreferencesDataStore)

    /** Additive v2 ledger; existing v1 rules and discipline state keep their own keys. */
    fun accessState(context: Context): AccessStateStore =
        DataStoreAccessStateStore(context.applicationContext.flintPreferencesDataStore)
}
