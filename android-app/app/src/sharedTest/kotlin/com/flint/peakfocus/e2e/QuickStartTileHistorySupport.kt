package com.flint.peakfocus.e2e

import org.json.JSONObject
import org.junit.Assert.assertTrue

internal const val FOCUS_HISTORY_FILE = "files/focus-history/events.v1.jsonl"

/** Emitted by the read script only after `cat` exits 0, so it marks a successful history read. */
internal const val FOCUS_HISTORY_READ_OK = "flint-history-read-ok"

/**
 * `shell()` (UiDevice.executeShellCommand) returns only the child's stdout: the framework pipes
 * stdout, drops stderr, and Runtime.exec tokenizes the command with StringTokenizer (no shell, no
 * quoting), so `2>&1` cannot be appended and operators would be literal arguments. A plain
 * `cat <file>` is therefore indistinguishable between "file is genuinely empty" and "read failed".
 *
 * The read runs `sh -c` through run-as with a whitespace-free script (literal `${IFS}` survives
 * tokenization and is field-split by the inner shell): it cats the history file FIRST and only
 * then `&& echo`s the marker. Any non-zero `cat` exit (missing file, unreadable file, run-as
 * failure) short-circuits, producing no marker at all, which fails closed below. Genuine empty
 * history still succeeds: `cat` exits 0 on an empty file, so the marker follows nothing.
 */
internal fun readFocusHistory(appId: String, shell: (String) -> String): List<JSONObject> {
    val raw = shell("run-as $appId sh -c cat\${IFS}$FOCUS_HISTORY_FILE&&echo\${IFS}$FOCUS_HISTORY_READ_OK")
    // The script appends the marker last; everything after it must be only its trailing newline.
    val markerAt = raw.lastIndexOf(FOCUS_HISTORY_READ_OK)
    val tail = if (markerAt < 0) "" else raw.substring(markerAt + FOCUS_HISTORY_READ_OK.length)
    if (markerAt < 0 || tail.isNotBlank()) {
        error("Focus-history read failed: history read did not succeed; output was: ${raw.take(200)}")
    }
    return parseFocusHistoryOutput(raw.substring(0, markerAt))
}

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
