package com.flint.peakfocus.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import java.io.File
import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real app UI and real elapsed time. No seeded rules/history or changed clock. */
@RunWith(AndroidJUnit4::class)
class FocusHistoryTest : FlintE2EFixture() {
    @Test fun realCompletionAndEarlyEndSurviveRestartAndRenderHistory() {
        visible(By.text("No focus sessions yet")); evidence("empty-today")
        clickTab("Insights")
        visible(By.text("FOCUS SESSIONS")); visible(By.text("No focus sessions yet"))
        evidence("empty-insights")
        clickTab("Today")

        enableService() // Fixture-owned disposable Accessibility grant; real history assertions stay unchanged.
        val began = startFiveMinuteTimer()
        val initial = awaitHistory { events -> events.count { it.getString("type") == "started" } == 1 }
        val first = initial.single { it.getString("type") == "started" }
        val firstId = first.getString("session")
        assertEquals(300_000L, first.getLong("plannedEnd") - first.getLong("at"))
        visible(By.text("Breathe with the light.")); evidence("running")

        // This is deliberately a real five-minute wait, bounded independently of wall time.
        val completed = awaitHistory(360_000) { events -> events.any {
            it.getString("session") == firstId && it.getString("type") == "completed"
        } }
        assertTrue("Natural completion required real elapsed time", SystemClock.elapsedRealtime() - began >= 295_000)
        val terminal = requireNotNull(firstTerminal(completed, firstId))
        assertEquals("completed", terminal.getString("type"))
        assertEquals(first.getLong("plannedEnd"), terminal.getLong("at"))
        visible(By.text("The ember has set.")); visible(By.text("5m focused"))
        evidence("complete")

        restartFlint()
        visible(By.text("The ember has set.")); visible(By.text("5m focused"))
        evidence("complete-after-restart")
        click("Done")
        visible(By.text("FOCUSED TODAY")); visible(By.text(duration(focusedToday(completed))))
        evidence("today-completed")
        restartFlint()
        visible(By.text("FOCUSED TODAY")) // persisted Done must not reopen Complete
        assertFalse(device.hasObject(By.text("The ember has set.")))

        startFiveMinuteTimer()
        val secondEvents = awaitHistory { events -> events.count { it.getString("type") == "started" } == 2 }
        val second = secondEvents.single { it.getString("type") == "started" && it.getString("session") != firstId }
        assertEquals(300_000L, second.getLong("plannedEnd") - second.getLong("at"))
        click("End session")
        visible(By.text("Leave early?"))
        val hold = visible(By.text("Hold to leave").enabled(true)).visibleBounds
        // One physical 2.5s hold through the normal cancellable control. Never replay it.
        assertTrue(device.swipe(hold.centerX(), hold.centerY(), hold.centerX(), hold.centerY(), 500))
        val ended = awaitHistory { events -> events.any {
            it.getString("session") == second.getString("session") && it.getString("type") == "ended_early"
        } }
        val early = requireNotNull(firstTerminal(ended, second.getString("session")))
        assertEquals("ended_early", early.getString("type"))
        assertTrue(early.getLong("at") < second.getLong("plannedEnd"))
        val outcomes = ended.map { it.getString("session") }.distinct().mapNotNull { firstTerminal(ended, it)?.getString("type") }
        assertEquals(1, outcomes.count { it == "completed" })
        assertEquals(1, outcomes.count { it == "ended_early" })
        visible(By.text("FOCUSED TODAY")); visible(By.text(duration(focusedToday(ended))))
        evidence("today-completed-and-early")

        restartFlint()
        visible(By.text("FOCUSED TODAY")); visible(By.text(duration(focusedToday(history()))))
        clickTab("Insights")
        visible(By.text("FOCUS SESSIONS")); visible(By.text("1 completed · 1 ended early"))
        evidence("insights-week")
        click("Day")
        visible(By.text(duration(focusedToday(history()))))
        evidence("insights-day")
    }

    private fun startFiveMinuteTimer(): Long {
        scrollTo("Start focus").click()
        // Both calls follow a fresh process launch, so the UI draft starts empty.
        scrollTo("Choose what rests").click()
        visible(By.text("What rests"))
        scrollTo(target).click(); click("Add · 1")
        visible(By.text("Block 1 app"))
        showDurationControls()
        visible(By.desc("25 minutes").enabled(true)).click()
        scrollTo("Begin 25m")
        for (minutes in listOf(20, 15, 10, 5)) {
            showDurationControls()
            visible(By.desc("Less time").enabled(true)).click()
            scrollTo("Begin ${minutes}m") // fresh postcondition before another input
        }
        val begin = visible(By.text("Begin 5m").enabled(true))
        val began = SystemClock.elapsedRealtime()
        begin.click()
        visible(By.text("Breathe with the light."))
        return began
    }

    private fun showDurationControls() {
        repeat(8) {
            refreshAccessibilityCache()
            if (device.findObject(By.desc("Less time")) != null) return
            val bounds = visible(By.scrollable(true)).visibleBounds
            device.swipe(bounds.centerX(), bounds.top + bounds.height() / 4,
                bounds.centerX(), bounds.bottom - bounds.height() / 4, 35)
            SystemClock.sleep(250)
        }
        visible(By.desc("Less time"))
    }

    private fun restartFlint() {
        shell("am force-stop $app")
        assertTrue("App process stopped", shell("pidof $app").trim().isEmpty())
        launchFlint()
        visible(By.text("Today"))
    }

    private fun history(): List<JSONObject> = shell("run-as $app cat files/focus-history/events.v1.jsonl")
        .lineSequence().filter { it.startsWith("{") }.map(::JSONObject).toList()

    private fun awaitHistory(timeoutMs: Long = timeout, predicate: (List<JSONObject>) -> Boolean): List<JSONObject> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val events = history()
            if (predicate(events)) return events
            SystemClock.sleep(500)
        } while (SystemClock.elapsedRealtime() < deadline)
        evidence("history-timeout")
        throw AssertionError("History did not reach the expected durable state: ${history()}")
    }

    private fun focusedToday(events: List<JSONObject>): Long {
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return events.groupBy { it.getString("session") }.values.sumOf { group ->
            val start = group.firstOrNull { it.getString("type") == "started" } ?: return@sumOf 0L
            val end = firstTerminal(group, start.getString("session")) ?: return@sumOf 0L
            (minOf(end.getLong("at"), start.getLong("plannedEnd")) - maxOf(dayStart, start.getLong("at"))).coerceAtLeast(0)
        }
    }

    private fun firstTerminal(events: List<JSONObject>, sessionId: String): JSONObject? = events
        .filter { it.getString("session") == sessionId && it.optInt("v") == 1 }
        .distinctBy { it.getString("id") }.sortedBy { it.getLong("at") }
        .firstOrNull { it.getString("type") in setOf("completed", "ended_early", "superseded") }

    private fun duration(ms: Long): String = when {
        ms in 1..59_999 -> "<1m"
        else -> "${ms / 60_000}m" // these two five-minute sessions remain below one hour
    }

    private fun evidence(stage: String) {
        screenshot(stage)
        val directory = File(instrumentation.context.getExternalFilesDir(null), "smoke")
        File(directory, "${testName.methodName}-$stage-history.jsonl")
            .writeText(shell("run-as $app cat files/focus-history/events.v1.jsonl"))
    }
}
