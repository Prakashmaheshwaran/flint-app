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
}
