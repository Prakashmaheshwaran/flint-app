package com.flint.peakfocus.core.common.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Flint strike mark (design/logo-mark.svg) as a dependency-free ImageVector — the brand
 * moment for the block screen, home status hero, and empty states. Three facets: the charcoal
 * strike body, the spark chip, the bronze fall-away.
 */
object FlintIcons {

    /** The tri-color mark. Draw with `Icon(..., tint = Color.Unspecified)` to keep the facets. */
    fun mark(body: Color, spark: Color, deep: Color): ImageVector =
        ImageVector.Builder(
            name = "FlintMark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 56f,
            viewportHeight = 56f,
        ).apply {
            path(fill = SolidColor(body)) {
                moveTo(14f, 44f)
                lineTo(28f, 8f)
                lineTo(36f, 26f)
                lineTo(44f, 18f)
                lineTo(30f, 48f)
                close()
            }
            path(fill = SolidColor(spark)) {
                moveTo(36f, 26f)
                lineTo(44f, 18f)
                lineTo(42f, 30f)
                close()
            }
            path(fill = SolidColor(deep)) {
                moveTo(30f, 48f)
                lineTo(42f, 30f)
                lineTo(44f, 40f)
                close()
            }
        }.build()

    /**
     * Three ascending bars — the Stats tab glyph (material-icons-core ships no bar chart).
     * Single-color paths so `Icon` tint applies; [filled] is the selected-tab treatment,
     * the unfilled variant draws the same bars as 1.8dp stroked outlines.
     */
    fun statsBars(filled: Boolean): ImageVector =
        ImageVector.Builder(
            name = if (filled) "FlintStatsBarsFilled" else "FlintStatsBarsOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            val bars = listOf(
                Triple(4f, 8f, 13f), // left, right, top — shortest bar
                Triple(10f, 14f, 8.5f),
                Triple(16f, 20f, 4f), // tallest bar, the "today" beat
            )
            bars.forEach { (left, right, top) ->
                if (filled) {
                    path(fill = SolidColor(Color.Black)) {
                        moveTo(left, top)
                        lineTo(right, top)
                        lineTo(right, 20f)
                        lineTo(left, 20f)
                        close()
                    }
                } else {
                    path(
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.8f,
                        strokeLineJoin = StrokeJoin.Round,
                    ) {
                        moveTo(left, top)
                        lineTo(right, top)
                        lineTo(right, 20f)
                        lineTo(left, 20f)
                        close()
                    }
                }
            }
        }.build()
}

/**
 * The mark in current theme colors (body follows onBackground so it reads on both themes;
 * spark/deep stay the brand accents). Remembered per color set.
 */
@Composable
fun rememberFlintMark(): ImageVector {
    val body = MaterialTheme.colorScheme.onBackground
    val spark = MaterialTheme.colorScheme.primary
    val deep = MaterialTheme.colorScheme.tertiary
    return remember(body, spark, deep) { FlintIcons.mark(body, spark, deep) }
}
