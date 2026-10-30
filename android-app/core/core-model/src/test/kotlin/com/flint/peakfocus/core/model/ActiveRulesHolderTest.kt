package com.flint.peakfocus.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The holder's one job: no writer may ever clobber another's contribution. The legacy
 * quick-blocklist projection and the DataStore rules arrive from independent writers on
 * independent triggers (service connect, boot warm-up, setters, Flow emissions) — the
 * pre-source-keyed regression was `BlocklistStore` overwriting the whole list and silently
 * dropping DataStore rules from enforcement.
 */
class ActiveRulesHolderTest {

    private fun rule(id: String) = BlockRule(
        id = id,
        name = id,
        targets = BlockTargets(apps = setOf(AppRef("com.example.$id"))),
    )

    @After
    fun clearAllSources() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        ActiveRulesHolder.publish("future-source", emptyList())
    }

    @Test
    fun sourcesCombine() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, listOf(rule("legacy")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("ds")))
        assertEquals(listOf("legacy", "ds"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun legacyWriterCannotClobberDatastoreRules() {
        // The original bug, as a sequence: DataStore rules land, then a legacy writer fires
        // (service connect / boot / setter). DataStore rules must survive.
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("ds")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, listOf(rule("legacy")))
        assertTrue(ActiveRulesHolder.rules.any { it.id == "ds" })

        // And the empty-projection flavor: clearing the legacy blocklist keeps DataStore rules.
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        assertEquals(listOf("ds"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun republishingASourceReplacesOnlyThatSource() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, listOf(rule("old-legacy")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("ds")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, listOf(rule("new-legacy")))
        assertEquals(listOf("new-legacy", "ds"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun legacyOrdersAheadOfDatastoreRegardlessOfPublishOrder() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("ds")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, listOf(rule("legacy")))
        assertEquals(listOf("legacy", "ds"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun unknownSourcesAppendAfterKnownOnes() {
        ActiveRulesHolder.publish("future-source", listOf(rule("future")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("ds")))
        assertEquals(listOf("ds", "future"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test fun unavailableAndLoadingNeverEraseLastKnownGoodRules() {
        val source = ActiveRulesHolder.SOURCE_DATASTORE
        ActiveRulesHolder.publish(source, listOf(rule("ds")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, listOf(rule("legacy")))
        ActiveRulesHolder.publishState(source, StorageState.Unavailable(StorageFailure.IO))
        assertEquals(StorageState.Unavailable(StorageFailure.IO), ActiveRulesHolder.dataStoreState)
        assertEquals(listOf("legacy", "ds"), ActiveRulesHolder.rules.map { it.id })
        ActiveRulesHolder.publishState(source, StorageState.Loading)
        assertEquals(StorageState.Loading, ActiveRulesHolder.dataStoreState)
        assertEquals(listOf("legacy", "ds"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test fun onlyReadyEmptyMayClearPreviouslyLoadedRules() {
        val source = ActiveRulesHolder.SOURCE_DATASTORE
        ActiveRulesHolder.publish(source, listOf(rule("ds")))
        ActiveRulesHolder.publishState(source, StorageState.Unavailable(StorageFailure.CORRUPT))
        ActiveRulesHolder.publishState(source, StorageState.Ready(emptyList()))
        assertTrue(ActiveRulesHolder.rules.isEmpty())
        assertEquals(StorageState.Ready(emptyList<BlockRule>()), ActiveRulesHolder.dataStoreState)
    }

    @Test fun publishingCallerOwnedListTakesASnapshot() {
        val rules = mutableListOf(rule("ds"))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, rules)
        rules.clear()
        assertEquals(listOf("ds"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test fun PC_73_sourceExclusionCannotReintroduceADeletedDatastoreRuleAsAnotherOwner() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, listOf(rule("legacy")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("old-ds")))
        ActiveRulesHolder.publish("future-source", listOf(rule("future")))
        val outside = ActiveRulesHolder.rulesOutside(ActiveRulesHolder.SOURCE_DATASTORE)
        assertEquals(listOf("legacy", "future"), outside.map { it.id })
        ActiveRulesHolder.publishState(ActiveRulesHolder.SOURCE_DATASTORE, StorageState.Unavailable(StorageFailure.IO))
        assertEquals(outside, ActiveRulesHolder.rulesOutside(ActiveRulesHolder.SOURCE_DATASTORE))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY, emptyList())
        assertEquals(listOf("legacy", "future"), outside.map { it.id })
    }

    @Test fun readinessChangesWakeObserversWithoutLosingLastGoodRules() {
        val source = ActiveRulesHolder.SOURCE_DATASTORE
        val expected = listOf(rule("ds"))
        ActiveRulesHolder.publish(source, expected)
        val states = mutableListOf<StorageState<List<BlockRule>>>()
        val snapshots = mutableListOf<List<BlockRule>>()
        val cancel = ActiveRulesHolder.subscribe {
            states += ActiveRulesHolder.dataStoreState
            snapshots += ActiveRulesHolder.rules
        }
        try {
            val unavailable = StorageState.Unavailable(StorageFailure.IO)
            ActiveRulesHolder.publishState(source, unavailable)
            ActiveRulesHolder.publishState(source, unavailable)
            ActiveRulesHolder.publishState(source, StorageState.Loading)
            ActiveRulesHolder.publish(source, expected)
            assertEquals(listOf(unavailable, StorageState.Loading, StorageState.Ready(expected)), states)
            assertEquals(listOf(expected, expected, expected), snapshots)
        } finally {
            cancel()
        }
    }

    @Test fun observerFailureDoesNotPreventOtherObserversOrPublication() {
        val source = ActiveRulesHolder.SOURCE_DATASTORE
        val expected = listOf(rule("ds"))
        ActiveRulesHolder.publish(source, emptyList())
        val fail = ActiveRulesHolder.subscribe { error("stale observer") }
        var observed: List<BlockRule>? = null
        val cancel = ActiveRulesHolder.subscribe { observed = ActiveRulesHolder.rules }
        try {
            ActiveRulesHolder.publish(source, expected)
            assertEquals(expected, ActiveRulesHolder.rules)
            assertEquals(expected, observed)
        } finally {
            fail()
            cancel()
        }
    }
}
