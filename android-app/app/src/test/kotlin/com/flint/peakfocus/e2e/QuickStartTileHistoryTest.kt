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

    @Test fun `blank run-as output fails closed instead of parsing as empty history`() {
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "" }
        }
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "\n" }
        }
    }

    @Test fun `read output without the sentinel fails closed`() {
        // run-as/cat errors normally reach stderr only (dropped by executeShellCommand); if any
        // error text does reach stdout, or the sentinel file cannot be read, never parse as empty.
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "run-as: package not debuggable: app.flint.focus\n" }
        }
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { """{"type":"started","session":"s"}""" }
        }
    }

    @Test fun `sentinel-only output is a genuinely empty history`() {
        // cat exited 0 on an empty file, so the script echoed the marker with nothing before it
        assertEquals(
            emptyList<JSONObject>(),
            readFocusHistory("app.flint.focus") { "$FOCUS_HISTORY_READ_OK\n" },
        )
        assertEquals(
            emptyList<JSONObject>(),
            readFocusHistory("app.flint.focus") { FOCUS_HISTORY_READ_OK },
        )
    }

    @Test fun `missing or unreadable history read is rejected while genuine empty is accepted`() {
        // genuine empty history (cat exit 0) is the only marker-only success
        assertEquals(
            emptyList<JSONObject>(),
            readFocusHistory("app.flint.focus") { "$FOCUS_HISTORY_READ_OK\n" },
        )
        // missing file: cat exits non-zero, && short-circuits, no marker is ever emitted
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "" }
        }
        // unreadable file: likewise rejected, and any non-marker text is never parsed as empty
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "cat: $FOCUS_HISTORY_FILE: Permission denied\n" }
        }
    }

    @Test fun `marker must be the final output of the read`() {
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "$FOCUS_HISTORY_READ_OK\nstray-after-marker\n" }
        }
    }

    @Test fun `sentinel output plus JSONL parses the history events`() {
        val started = """{"type":"started","session":"s"}"""
        val completed = """{"type":"completed","session":"s"}"""
        // cat prints the history first; echo appends the marker last, glued when the file has no
        // final newline of its own
        val glued = readFocusHistory("app.flint.focus") {
            "$started\n$completed$FOCUS_HISTORY_READ_OK\n"
        }
        assertEquals(listOf("started", "completed"), glued.map { it.getString("type") })
        val newlineSeparated = readFocusHistory("app.flint.focus") {
            "$started\n$completed\n$FOCUS_HISTORY_READ_OK\n"
        }
        assertEquals(listOf("started", "completed"), newlineSeparated.map { it.getString("type") })
    }

    @Test fun `read command carries sentinel and history in one run-as invocation`() {
        var command = ""
        readFocusHistory("app.flint.focus") {
            command = it
            "$FOCUS_HISTORY_READ_OK\n"
        }
        assertEquals(
            "run-as app.flint.focus sh -c cat\${IFS}$FOCUS_HISTORY_FILE&&echo\${IFS}$FOCUS_HISTORY_READ_OK",
            command,
        )
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
