package com.flint.peakfocus.e2e

import org.json.JSONObject
import org.junit.Assert.assertTrue

internal fun parseFocusHistoryOutput(output: String): List<JSONObject> {
    if (output.isBlank()) return emptyList()

    val trimmed = output.trimEnd('\n', '\r')
    if (trimmed.contains("run-as:")) {
        error("Unexpected focus-history read failure: $trimmed")
    }

    val lines = trimmed.lines()
    if (lines.size == 1 && lines[0].contains("No such file or directory")) {
        return emptyList() // genuinely absent history file
    }

    return lines.map { line ->
        check(line.startsWith("{")) { "Malformed non-empty focus-history output: $output" }
        try {
            JSONObject(line)
        } catch (e: Exception) {
            error("Malformed focus-history JSONL: ${e.message}; output=$output")
        }
    }
}

internal fun assertNoStartedFocusSessionWithin(
    readEvents: () -> List<JSONObject>,
    nowMs: () -> Long,
    sleepMs: (Long) -> Unit,
    timeoutMs: Long = 5_000L,
    intervalMs: Long = 100L,
) {
    val deadline = nowMs() + timeoutMs
    while (true) {
        assertTrue(
            "Tile click must not create a focus session",
            readEvents().none { it.optString("type") == "started" },
        )
        if (nowMs() >= deadline) break
        sleepMs(intervalMs)
    }
}
