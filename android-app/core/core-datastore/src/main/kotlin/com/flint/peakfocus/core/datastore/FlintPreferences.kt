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

    fun blockRules(context: Context): BlockRulesStore =
        DataStoreBlockRulesStore(context.applicationContext.flintPreferencesDataStore)

    fun limits(context: Context): LimitsStore =
        DataStoreLimitsStore(context.applicationContext.flintPreferencesDataStore)

    fun focusState(context: Context): FocusStateStore =
        DataStoreFocusStateStore(context.applicationContext.flintPreferencesDataStore)
}
