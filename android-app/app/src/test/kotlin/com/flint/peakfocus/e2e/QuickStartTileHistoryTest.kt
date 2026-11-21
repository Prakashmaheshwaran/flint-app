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
        // error text does reach stdout, there is no verified pwd line and no success marker,
        // so it must never parse as empty history.
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "run-as: package not debuggable: app.flint.focus\n" }
        }
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { """{"type":"started","session":"s"}""" }
        }
    }

    @Test fun `sentinel-only output is a genuinely empty history`() {
        // verified location + cat exit 0 on an empty file: marker follows the pwd line only
        assertEquals(
            emptyList<JSONObject>(),
            readFocusHistory("app.flint.focus") { "$dataDir\n$FOCUS_HISTORY_READ_OK\n" },
        )
        assertEquals(
            emptyList<JSONObject>(),
            readFocusHistory("app.flint.focus") { "$dataDir\n$FOCUS_HISTORY_READ_OK" },
        )
    }

    @Test fun `verified absent history file is accepted while failed and unreadable reads fail closed`() {
        // state 3: location verified in-script and echoed back, `test -f` proves absence
        assertEquals(
            emptyList<JSONObject>(),
            readFocusHistory("app.flint.focus") { "$dataDir\n$FOCUS_HISTORY_ABSENT\n" },
        )
        // state 2: present, readable, empty file
        assertEquals(
            emptyList<JSONObject>(),
            readFocusHistory("app.flint.focus") { "$dataDir\n$FOCUS_HISTORY_READ_OK\n" },
        )
        // failed run-as / no output: never an empty history
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "" }
        }
        // present but unreadable: location line only — no absent marker, no success marker
        expectFailureContaining("history read did not succeed") {
            readFocusHistory("app.flint.focus") { "$dataDir\n" }
        }
        // unreadable text on stdout is never parsed as empty
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") { "$dataDir\ncat: $FOCUS_HISTORY_FILE: Permission denied\n" }
        }
    }

    @Test fun `absent marker without a verified location fails closed`() {
        // no pwd line means the expected data directory was never confirmed
        expectFailureContaining("unexpected working directory") {
            readFocusHistory("app.flint.focus") { "$FOCUS_HISTORY_ABSENT\n" }
        }
    }

    @Test fun `wrong working directory fails closed even when the file appears absent`() {
        // the naive relative `test -f` would report absent here; the echoed pwd exposes it
        expectFailureContaining("unexpected working directory") {
            readFocusHistory("app.flint.focus") { "/data/local/tmp\n$FOCUS_HISTORY_ABSENT\n" }
        }
        expectFailureContaining("unexpected working directory") {
            readFocusHistory("app.flint.focus") { "/\n" }
        }
    }

    @Test fun `malformed history content fails closed through the read helper`() {
        expectFailureContaining("Malformed") {
            readFocusHistory("app.flint.focus") {
                "$dataDir\nnot-json-at-all\n$FOCUS_HISTORY_READ_OK\n"
            }
        }
    }

    @Test fun `marker must be the final output of the read`() {
        expectFailureContaining("Focus-history read failed") {
            readFocusHistory("app.flint.focus") {
                "$dataDir\n$FOCUS_HISTORY_READ_OK\nstray-after-marker\n"
            }
        }
    }

    @Test fun `verified location plus JSONL parses the history events`() {
        val started = """{"type":"started","session":"s"}"""
        val completed = """{"type":"completed","session":"s"}"""
        // cat prints the history first; echo appends the marker last, glued when the file has no
        // final newline of its own
        val glued = readFocusHistory("app.flint.focus") {
            "$dataDir\n$started\n$completed$FOCUS_HISTORY_READ_OK\n"
        }
        assertEquals(listOf("started", "completed"), glued.map { it.getString("type") })
        val newlineSeparated = readFocusHistory("app.flint.focus") {
            "$dataDir\n$started\n$completed\n$FOCUS_HISTORY_READ_OK\n"
        }
        assertEquals(listOf("started", "completed"), newlineSeparated.map { it.getString("type") })
    }

    @Test fun `read command gates the history probe behind an in-script location check`() {
        var command = ""
        readFocusHistory("app.flint.focus") {
            command = it
            "$dataDir\n$FOCUS_HISTORY_READ_OK\n"
        }
        assertEquals(
            "run-as app.flint.focus sh -c p=\$(pwd);echo\${IFS}\$p;" +
                "test\${IFS}\${p##*/}\${IFS}=\${IFS}app.flint.focus&&" +
                "test\${IFS}\${p#/data/}\${IFS}!=\${IFS}\$p&&" +
                "(cat\${IFS}$FOCUS_HISTORY_FILE&&echo\${IFS}$FOCUS_HISTORY_READ_OK||" +
                "(test\${IFS}-f\${IFS}$FOCUS_HISTORY_FILE||echo\${IFS}$FOCUS_HISTORY_ABSENT);true)",
            command,
        )
    }

    @Test fun `absent marker alongside content fails closed instead of parsing`() {
        // The read script can no longer emit this ordering — the absent marker comes only from
        // the cat failure branch, after a successful read has already printed the success marker.
        // If it ever arrived anyway, it must never parse as empty and never drop the real events.
        expectFailureContaining("Malformed") {
            readFocusHistory("app.flint.focus") {
                "$dataDir\n$FOCUS_HISTORY_ABSENT\n" +
                    "{\"type\":\"started\",\"session\":\"s\"}\n$FOCUS_HISTORY_READ_OK\n"
            }
        }
        // content without the success marker (e.g. read raced by a rewrite) stays fail-closed too
        expectFailureContaining("history read did not succeed") {
            readFocusHistory("app.flint.focus") {
                "$dataDir\n{\"type\":\"started\",\"session\":\"s\"}\n$FOCUS_HISTORY_ABSENT\n"
            }
        }
    }

    private val dataDir = "/data/user/0/app.flint.focus"

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
