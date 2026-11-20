package com.flint.peakfocus.feature.stats

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.history.FocusDay
import com.flint.peakfocus.core.history.FocusHistoryText
import com.flint.peakfocus.core.history.FocusSummary
import com.flint.peakfocus.core.history.HistoryAvailability
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression for the Insights Week/Day switch.
 *
 * The emulator suite cannot reach this card: the Focus Streak card pushes it below the fold on
 * the runner's 1080x1920 display, and scrolling that page kills the software-rendered emulator.
 * This host test pins the switch instead, so a chip that stops changing the view fails here.
 *
 * The fixture records one completed 5m session three days ago and nothing today. Week must show
 * that session; Day (today) must not. The totals and the period label therefore differ per
 * period, which is exactly what a broken switch would stop doing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusSessionsCardTest {
    @get:Rule val compose = createComposeRule()

    private val today: LocalDate = LocalDate.now()
    private val dayLabel = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val dataDay = today.minusDays(3)

    private fun summary(): FocusSummary {
        val days = (6 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            if (date == dataDay) FocusDay(date, focusedMs = 300_000L, completed = 1)
            else FocusDay(date, focusedMs = null)
        }
        return FocusSummary(HistoryAvailability.VALUE, sessions = emptyList(), days = days, streak = 0)
    }

    private fun setCard() {
        compose.setContent { MaterialTheme { FocusSessionsCard(summary()) } }
    }

    @Test
    fun weekShowsTheOlderSessionAndTheWeekLabel() {
        setCard()
        compose.onNodeWithText("Week").assertIsSelected()
        compose.onNodeWithTag("focus.sessions.total").assertTextEquals("5m")
        compose.onNodeWithText("1 completed · 0 ended early").assertExists()
        compose.onNodeWithText(FocusHistoryText.weekLabel(summary().days)).assertExists()
        // The bare day label belongs to the Day view only.
        compose.onNodeWithText(today.format(dayLabel)).assertDoesNotExist()
    }

    @Test
    fun daySwitchDropsTheOlderSessionAndShowsTheDayLabel() {
        setCard()
        compose.onNodeWithText("Day").performClick()
        compose.onNodeWithText("Day").assertIsSelected()
        // Today has no session, so the three-days-ago 5m must drop out of the total.
        compose.onNodeWithTag("focus.sessions.total").assertTextEquals("No data")
        compose.onNodeWithText("0 completed · 0 ended early").assertExists()
        compose.onNodeWithText("1 completed · 0 ended early").assertDoesNotExist()
        compose.onNodeWithText(today.format(dayLabel)).assertExists()
        compose.onNodeWithText(FocusHistoryText.weekLabel(summary().days)).assertDoesNotExist()
    }
}
