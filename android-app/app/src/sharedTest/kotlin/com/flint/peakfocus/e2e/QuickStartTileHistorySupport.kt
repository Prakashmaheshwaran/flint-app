package com.flint.peakfocus.e2e

import org.json.JSONObject
import org.junit.Assert.assertTrue

internal const val FOCUS_HISTORY_FILE = "files/focus-history/events.v1.jsonl"

/** Emitted by the read script only after `cat` exits 0, so it marks a successful history read. */
internal const val FOCUS_HISTORY_READ_OK = "flint-history-read-ok"

/** Emitted only after the location gate passed and `test -f` proves the file is genuinely absent. */
internal const val FOCUS_HISTORY_ABSENT = "flint-history-absent"

/**
 * `shell()` (UiDevice.executeShellCommand) returns only the child's stdout: the framework pipes
 * stdout, drops stderr, and Runtime.exec tokenizes the command with StringTokenizer (no shell, no
 * quoting), so `2>&1` cannot be appended and operators would be literal arguments. A plain
 * `cat <file>` is therefore indistinguishable between "file is genuinely empty" and "read failed",
 * and a bare `test -f <relative path>` cannot distinguish "absent" from "wrong directory".
 *
 * Three states are produced by one whitespace-free `sh -c` script (literal `${IFS}` survives
 * tokenization and is field-split by the inner shell into real whitespace):
 * 1. `pwd` is echoed first; the read section only runs when the location gate passes — the
 *    basename of the working directory must equal the app id and must live under `/data/`. A wrong
 *    directory therefore yields only the pwd line and never the absent marker.
 * 2. Location verified and `test -f` false => absent marker => genuinely empty history.
 * 3. Location verified and file present => `cat` output followed by the success marker. A non-zero
 *    `cat` exit (present but unreadable, failed run-as) short-circuits the marker: no absent
 *    marker, no success marker => fail closed below.
 *
 * The helper independently re-verifies the echoed pwd (expected `/data/.../<appId>`) before it
 * accepts the absent marker, so absence is trusted only when the expected data directory is proven.
 */
internal fun readFocusHistory(appId: String, shell: (String) -> String): List<JSONObject> {
    require(appId.matches(Regex("[A-Za-z0-9._]+"))) { "Unexpected appId: $appId" }
    val script =
        "p=\$(pwd);echo\${IFS}\$p;" +
            "test\${IFS}\${p##*/}\${IFS}=\${IFS}$appId&&" +
            "test\${IFS}\${p#/data/}\${IFS}!=\${IFS}\$p&&" +
            "(test\${IFS}-f\${IFS}$FOCUS_HISTORY_FILE||echo\${IFS}$FOCUS_HISTORY_ABSENT;" +
            "cat\${IFS}$FOCUS_HISTORY_FILE&&echo\${IFS}$FOCUS_HISTORY_READ_OK;true)"
    val raw = shell("run-as $appId sh -c $script")
    if (raw.isBlank()) {
        error("Focus-history read failed: run-as produced no output; output was: ${raw.take(200)}")
    }
    val newlineAt = raw.indexOf('\n')
    val pwdLine = if (newlineAt < 0) raw else raw.substring(0, newlineAt)
    val rest = if (newlineAt < 0) "" else raw.substring(newlineAt + 1)
    if (!pwdLine.startsWith("/data/") || !pwdLine.endsWith("/$appId")) {
        error("Focus-history read failed: unexpected working directory: $pwdLine (expected /data/.../$appId)")
    }
    if (rest.trim() == FOCUS_HISTORY_ABSENT) return emptyList()
    val markerAt = rest.lastIndexOf(FOCUS_HISTORY_READ_OK)
    val tail = if (markerAt < 0) "" else rest.substring(markerAt + FOCUS_HISTORY_READ_OK.length)
    if (markerAt < 0 || tail.isNotBlank()) {
        error("Focus-history read failed: history read did not succeed; output was: ${raw.take(200)}")
    }
    return parseFocusHistoryOutput(rest.substring(0, markerAt))
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
