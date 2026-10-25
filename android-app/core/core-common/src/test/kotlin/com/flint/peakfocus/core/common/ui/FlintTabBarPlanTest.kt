package com.flint.peakfocus.core.common.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for [planFlintTabBar]. The two-row fallback is hard to reach with a single
 * device/font capture, so these cases pin the decision and the row grouping directly.
 */
class FlintTabBarPlanTest {

    private fun widths(today: Float, rules: Float, insights: Float, you: Float): Map<FlintTabGlyph, Dp> =
        mapOf(
            FlintTabGlyph.Today to today.dp,
            FlintTabGlyph.Rules to rules.dp,
            FlintTabGlyph.Insights to insights.dp,
            FlintTabGlyph.You to you.dp,
        )

    @Test
    fun `wide bar keeps a single row`() {
        val plan = planFlintTabBar(widths(60f, 60f, 60f, 60f), 320.dp)

        assertTrue(plan.singleRow)
        assertEquals(listOf(FlintTabGlyph.entries.toList()), plan.rows)
    }

    @Test
    fun `narrow bar falls back to two rows without dropping tabs`() {
        val plan = planFlintTabBar(widths(72f, 64f, 86f, 48f), 240.dp)

        assertFalse(plan.singleRow)
        assertEquals(
            listOf(
                listOf(FlintTabGlyph.Today, FlintTabGlyph.Rules),
                listOf(FlintTabGlyph.Insights, FlintTabGlyph.You),
            ),
            plan.rows,
        )
        assertEquals(FlintTabGlyph.entries.toList(), plan.rows.flatten())
    }

    @Test
    fun `single row shares equal widths only when the widest label fits four times`() {
        val equal = planFlintTabBar(widths(48f, 48f, 48f, 48f), 200.dp)
        assertTrue(equal.singleRow)
        assertTrue(equal.equalWidths)

        val proportional = planFlintTabBar(widths(48f, 48f, 86f, 48f), 240.dp)
        assertTrue(proportional.singleRow)
        assertFalse(proportional.equalWidths)
    }

    @Test
    fun `exact fit stays on one row`() {
        assertTrue(planFlintTabBar(widths(60f, 60f, 60f, 60f), 240.dp).singleRow)
    }

    @Test
    fun `one dp over the available width uses two rows`() {
        val plan = planFlintTabBar(widths(60f, 60f, 60f, 60f), 239.dp)

        assertFalse(plan.singleRow)
        assertEquals(2, plan.rows.size)
        assertTrue(plan.rows.all { it.size == 2 })
    }
}
