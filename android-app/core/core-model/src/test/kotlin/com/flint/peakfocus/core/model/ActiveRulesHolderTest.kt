package com.flint.peakfocus.core.model

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The holder's one hard guarantee: a writer replaces only its own lane. The regression this
 * pins down is the old "clobber window" — `BlocklistStore.load()` (a11y-service connect, boot
 * warm-up, every legacy setter) used to assign the whole list, silently dropping the
 * DataStore-authored rules out of enforcement until the next emission or app resume.
 */
class ActiveRulesHolderTest {

    private fun rule(id: String) = BlockRule(
        id = id,
        name = id,
        targets = BlockTargets(apps = setOf(AppRef("com.example.$id"))),
    )

    @Before
    fun resetHolder() = ActiveRulesHolder.reset()

    @Test
    fun startsEmpty() {
        assertEquals(emptyList<BlockRule>(), ActiveRulesHolder.rules)
    }

    @Test
    fun mergesBothLanes() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, listOf(rule("blocklist")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus"), rule("sleep")))
        assertEquals(listOf("blocklist", "focus", "sleep"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun emptyLegacyPublishDoesNotWipeDataStoreRules() {
        // The clobber regression: legacy reload with an empty blocklist must keep DataStore rules.
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, emptyList())
        assertEquals(listOf("focus"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun legacyReloadDoesNotDuplicateOrDropDataStoreRules() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus")))
        repeat(3) {
            ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, listOf(rule("blocklist")))
        }
        assertEquals(listOf("blocklist", "focus"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun writerReplacesOnlyItsOwnLane() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, listOf(rule("old-blocklist")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, listOf(rule("new-blocklist")))
        assertEquals(listOf("new-blocklist", "focus"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun mergeOrderIsLaneOrderNotPublishOrder() {
        // Engine verdict precedence scans in order — the merged list must be stable however
        // the writers race, legacy lane first.
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, listOf(rule("blocklist")))
        assertEquals(listOf("blocklist", "focus"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun unknownLanesAppendAlphabeticallyAfterKnownLanes() {
        ActiveRulesHolder.publish("zeta", listOf(rule("z")))
        ActiveRulesHolder.publish("alpha", listOf(rule("a")))
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus")))
        assertEquals(listOf("focus", "a", "z"), ActiveRulesHolder.rules.map { it.id })
    }

    @Test
    fun resetDropsEveryLane() {
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, listOf(rule("blocklist")))
        ActiveRulesHolder.reset()
        assertEquals(emptyList<BlockRule>(), ActiveRulesHolder.rules)
    }

    @Test
    fun concurrentWritersBothLand() {
        // The two real writers race (main-thread setters vs. the bridge's collector coroutine).
        // Whatever the interleaving, the final snapshot must hold both lanes.
        repeat(50) {
            ActiveRulesHolder.reset()
            val start = CountDownLatch(1)
            val writers = listOf(
                thread { start.await(); ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_LEGACY_BLOCKLIST, listOf(rule("blocklist"))) },
                thread { start.await(); ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus"))) },
            )
            start.countDown()
            writers.forEach { it.join() }
            assertEquals(listOf("blocklist", "focus"), ActiveRulesHolder.rules.map { it.id })
        }
    }

    @Test
    fun readersSeeSnapshotsNotTornState() {
        // rules is a @Volatile immutable snapshot: a reader holding a reference must not see
        // it mutate under a later publish.
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, listOf(rule("focus")))
        val snapshot = ActiveRulesHolder.rules
        ActiveRulesHolder.publish(ActiveRulesHolder.SOURCE_DATASTORE, emptyList())
        assertEquals(listOf("focus"), snapshot.map { it.id })
        assertTrue(ActiveRulesHolder.rules.isEmpty())
    }
}
