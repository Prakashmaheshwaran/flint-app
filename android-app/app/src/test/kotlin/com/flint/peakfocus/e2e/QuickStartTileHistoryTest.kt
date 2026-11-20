package com.flint.peakfocus.e2e

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuickStartTileHistoryTest {
    @Test fun `empty history is accepted`() {
        assertEquals(emptyList<JSONObject>(), parseFocusHistoryOutput(""))
        assertEquals(emptyList<JSONObject>(), parseFocusHistoryOutput("   \n"))
    }

    @Test fun `genuinely absent history file is accepted`() {
        val output = "cat: /data/user/0/app.flint.focus/files/focus-history/events.v1.jsonl: No such file or directory\n"
        assertEquals(emptyList<JSONObject>(), parseFocusHistoryOutput(output))
    }

    @Test fun `started event is detected and causes immediate failure`() {
        var reads = 0
        val clock = FakeClock()
        val error = try {
            assertNoStartedFocusSessionWithin(
                readEvents = {
                    reads++
                    listOf(JSONObject("""{"type":"started","session":"s1"}"""))
                },
                nowMs = clock::now,
                sleepMs = clock::sleep,
            )
            null
        } catch (e: AssertionError) {
            e
        }
        assertTrue(error?.message?.contains("Tile click must not create a focus session") == true)
        assertEquals(1, reads)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test fun `delayed started event appearing during polling is detected`() {
        var reads = 0
        val clock = FakeClock()
        val error = try {
            assertNoStartedFocusSessionWithin(
                readEvents = {
                    reads++
                    if (reads < 4) emptyList()
                    else listOf(JSONObject("""{"type":"started","session":"s2"}"""))
                },
                nowMs = clock::now,
                sleepMs = clock::sleep,
            )
            null
        } catch (e: AssertionError) {
            e
        }
        assertTrue(error?.message?.contains("Tile click must not create a focus session") == true)
        assertEquals(4, reads)
        assertEquals(listOf(100L, 100L, 100L), clock.sleeps)
        assertEquals(300L, clock.now())
    }

    @Test fun `unexpected run-as errors fail closed`() {
        expectFailureContaining(
            "Unexpected focus-history read failure",
        ) {
            parseFocusHistoryOutput("run-as: package not debuggable: app.flint.focus\n")
        }
        expectFailureContaining(
            "Unexpected focus-history read failure",
        ) {
            parseFocusHistoryOutput("run-as: unknown package: app.flint.focus\n")
        }
    }

    @Test fun `permission errors fail closed`() {
        expectFailureContaining(
            "Unexpected focus-history read failure",
        ) {
            parseFocusHistoryOutput("run-as: Permission denied\n")
        }
        // cat-level permission denial is unexpected non-JSON output and must fail closed
        expectFailureContaining(
            "Malformed non-empty focus-history output",
        ) {
            parseFocusHistoryOutput("cat: /data/user/0/app.flint.focus/files/focus-history/events.v1.jsonl: Permission denied\n")
        }
    }

    @Test fun `malformed JSONL fails closed`() {
        expectFailureContaining("Malformed focus-history JSONL") {
            parseFocusHistoryOutput("{\"type\":\"started\"\n") // truncated/invalid JSON
        }
        expectFailureContaining("Malformed non-empty focus-history output") {
            parseFocusHistoryOutput("not-json-at-all\n")
        }
    }

    @Test fun `unexpected non-JSON output fails closed`() {
        expectFailureContaining("Malformed non-empty focus-history output") {
            parseFocusHistoryOutput("ERROR: something went wrong\n")
        }
        expectFailureContaining("Malformed non-empty focus-history output") {
            parseFocusHistoryOutput("<?xml version=\"1.0\"?><x/>\n")
        }
    }

    @Test fun `polling accepts no started event through full 5 second window`() {
        var reads = 0
        val clock = FakeClock()
        assertNoStartedFocusSessionWithin(
            readEvents = {
                reads++
                if (reads % 2 == 0) emptyList()
                else listOf(JSONObject("""{"type":"ended_early","session":"x"}"""))
            },
            nowMs = clock::now,
            sleepMs = clock::sleep,
        )
        // first check at 0, then checks after sleeps at 100,200,...,5000 => 51 reads, 50 sleeps
        assertEquals(51, reads)
        assertEquals(50, clock.sleeps.size)
        assertTrue(clock.sleeps.all { it == 100L })
        assertEquals(5000L, clock.now())
    }

    private fun expectFailureContaining(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("Expected failure containing: $fragment")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            assertTrue("Expected '$fragment' in '${e.message}'", e.message?.contains(fragment) == true)
        }
    }

    private class FakeClock {
        private var current = 0L
        val sleeps = mutableListOf<Long>()
        fun now(): Long = current
        fun sleep(ms: Long) {
            sleeps += ms
            current += ms
        }
    }
}
