package com.flint.peakfocus

import com.flint.peakfocus.core.datastore.EditorCommitmentState
import com.flint.peakfocus.core.datastore.editorPublicationFailure
import com.flint.peakfocus.core.model.*
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorRulePublicationTest {
    @Test fun `PC-12 healthy publication sleeps until a boundary or a datastore change`() = runTest {
        val changes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        var calls = 0
        backgroundScope.launch {
            runEditorRulePublication(changes, { calls++; 3_600_000L }, { throw it })
        }
        runCurrent(); assertEquals(1, calls)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, calls)
        changes.emit(Unit); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(3_600_000); runCurrent(); assertEquals(3, calls)
    }

    @Test fun `PC-71 unexpected loop failure replaces Ready with Unavailable and retries`() = runTest {
        val changes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val published = mutableListOf<StorageState<List<BlockRule>>>()
        var fail = false
        backgroundScope.launch {
            runEditorRulePublication(changes, {
                check(!fail) { "decoding failed" }
                published += StorageState.Ready(emptyList())
                null
            }, { published += StorageState.Unavailable(editorPublicationFailure(it)) })
        }
        runCurrent(); assertTrue(published.last() is StorageState.Ready)
        fail = true; changes.emit(Unit); runCurrent()
        assertEquals(StorageState.Unavailable(StorageFailure.CORRUPT), published.last())
        fail = false; advanceTimeBy(EDITOR_RETRY_MS); runCurrent()
        assertTrue(published.last() is StorageState.Ready)
    }

    @Test fun `PC-12 future schedule start remains derivable for reconciliation`() {
        val at = Instant.parse("2026-10-28T20:55:00Z").toEpochMilli()
        val rule = BlockRule("evening", "Evening", BlockTargets(apps = setOf(AppRef("a"))),
            Schedule(startMinuteOfDay = 21 * 60, endMinuteOfDay = 22 * 60))
        val state = EditorCommitmentState(causes = mapOf("rule:evening" to
            com.flint.peakfocus.core.datastore.EditorCause("inactive", null, "UTC")))
        assertEquals(5 * 60_000L, nextEditorReconciliationDelay(listOf(rule), state, AccessClockStamp(at, 0, "boot")))
    }
}
