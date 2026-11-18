package com.flint.peakfocus.core.common.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic pixel evidence for the streak flame at every shipped size and state.
 * Draws the production painter into a host bitmap (no device, no Compose test framework)
 * and writes PNGs to `build/flame-shots/`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreakFlameRenderTest {

    private val density = 2f

    private fun painterFor(dp: Int): StreakFlamePainter {
        val (inner, outer) = fringeWidthsDp(dp.toFloat())
        return StreakFlamePainter(
            geometry = FlameGeometry(dp * density),
            compact = dp < 48,
            emberCount = when {
                dp >= 128 -> 3
                dp >= 64 -> 2
                dp >= 36 -> 1
                else -> 0
            },
            fringeInner = Stroke(inner * density),
            fringeOuter = Stroke(outer * density),
        )
    }

    private fun render(dp: Int, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ImageBitmap {
        val px = (dp * density).toInt()
        val image = ImageBitmap(px, px)
        val canvas = Canvas(image)
        CanvasDrawScope().draw(
            Density(density),
            LayoutDirection.Ltr,
            canvas,
            Size(px.toFloat(), px.toFloat()),
        ) {
            drawRect(Color(0xFF0A090B))
            this.block()
        }
        return image
    }

    private fun write(image: ImageBitmap, name: String) {
        val dir = File("build/flame-shots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun paintedPixels(image: ImageBitmap, step: Int = 2): Int {
        val bitmap = image.asAndroidBitmap()
        var painted = 0
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                if (bitmap.getPixel(x, y) and 0x00FFFFFF != 0x000A090B) painted++
            }
        }
        return painted
    }

    /** Max per-channel distance from the #0A090B matte: the matte plus the faint bloom is ~4. */
    private fun ink(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return maxOf(abs(r - 10), abs(g - 9), abs(b - 11))
    }

    /** Above this the pixel is silhouette or heat, not the bloom halo or contact light. */
    private val silhouetteInk = 48

    /**
     * Rendered silhouette aspect from pixels, thresholding the bloom halo out.
     *
     * The contour spans Â±0.45 w over a 0.90 s tall body, so this measurement — not the
     * nominal w/h constant — is the aspect the design signs off on.
     */
    private fun silhouetteAspect(image: ImageBitmap): Float {
        val bitmap = image.asAndroidBitmap()
        var minX = bitmap.width
        var maxX = -1
        var minY = bitmap.height
        var maxY = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (ink(bitmap.getPixel(x, y)) < silhouetteInk) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        assertTrue("no silhouette found", maxX >= minX && maxY >= minY)
        return (maxY - minY + 1).toFloat() / (maxX - minX + 1).toFloat()
    }

    /** Mean x of silhouette pixels inside a horizontal band, given as image fractions. */
    private fun bandCentroidX(image: ImageBitmap, fromY: Float, toY: Float): Float {
        val bitmap = image.asAndroidBitmap()
        val y0 = (bitmap.height * fromY).toInt()
        val y1 = (bitmap.height * toY).toInt()
        var sum = 0.0
        var count = 0
        for (y in y0 until y1) {
            for (x in 0 until bitmap.width) {
                if (ink(bitmap.getPixel(x, y)) >= silhouetteInk) {
                    sum += x
                    count++
                }
            }
        }
        assertTrue("empty band $fromY..$toY", count > 0)
        return (sum / count).toFloat()
    }

    private fun samePixels(a: ImageBitmap, b: ImageBitmap): Boolean {
        val left = a.asAndroidBitmap()
        val right = b.asAndroidBitmap()
        for (y in 0 until left.height) {
            for (x in 0 until left.width) {
                if (left.getPixel(x, y) != right.getPixel(x, y)) return false
            }
        }
        return true
    }

    @Test
    fun renderedSilhouetteMatchesFlameShape() {
        val measured = sortedMapOf<Int, Float>()
        for (dp in listOf(24, 32, 40, 96, 148, 160)) {
            val image = render(dp) {
                painterFor(dp).draw(this, StreakFlameState.Active, STATIC_FLAME_SECONDS, 0f)
            }
            val aspect = silhouetteAspect(image)
            measured[dp] = aspect
            assertTrue("silhouette H:W $aspect at ${dp}dp should read as upright flame", aspect in 1.05f..1.60f)
            assertTrue("active flame should be visible at ${dp}dp", paintedPixels(image) > 10)
        }
        val atRiskAspect = silhouetteAspect(
            render(148) { painterFor(148).draw(this, StreakFlameState.AtRisk, STATIC_FLAME_SECONDS, 0f) },
        )
        val activeAspect = measured.getValue(148)
        assertTrue(abs(atRiskAspect - activeAspect) < 0.08f)
    }


    @Test
    fun poseIsDeterministicAndDeformsOnlyTheUpperBody() {
        val painter = painterFor(148)
        val first = render(148) {
            painter.draw(this, StreakFlameState.Active, STATIC_FLAME_SECONDS, 0f)
        }
        val second = render(148) {
            painter.draw(this, StreakFlameState.Active, STATIC_FLAME_SECONDS, 0f)
        }
        assertTrue("static pose is not deterministic", samePixels(first, second))

        // 1.68 s later: the pose leans the other way (about 0.044 w of tip travel).
        val later = render(148) {
            painter.draw(this, StreakFlameState.Active, 2.40, 0f)
        }
        assertTrue("no animation reached the raster", !samePixels(second, later))
        val upperShift = abs(bandCentroidX(second, 0.05f, 0.45f) - bandCentroidX(later, 0.05f, 0.45f))
        val baseShift = abs(bandCentroidX(second, 0.72f, 0.95f) - bandCentroidX(later, 0.72f, 0.95f))
        assertTrue("upper body does not deform laterally (shift $upperShift px)", upperShift > 3f)
        assertTrue(
            "base moves like the tip (upper $upperShift px, base $baseShift px)",
            upperShift > 3f * maxOf(baseShift, 0.5f),
        )
    }

    @Test
    fun renderStateCrossFadeCompositesBothStates() {
        val painter = painterFor(148)
        val active = render(148) { painter.draw(this, StreakFlameState.Active, STATIC_FLAME_SECONDS, 0f) }
        val dormant = render(148) { painter.draw(this, StreakFlameState.Inactive, STATIC_FLAME_SECONDS, 0f) }
        val mid = render(148) {
            painter.drawCrossFade(this, from = StreakFlameState.Active, to = StreakFlameState.Inactive, elapsed = STATIC_FLAME_SECONDS, flashIn = 0f, progress = 0.5f)
        }
        assertTrue(paintedPixels(active) > 100)
        assertTrue(paintedPixels(dormant) > 100)
        assertTrue(paintedPixels(mid) > 100)
        assertTrue(!samePixels(mid, active))
        assertTrue(!samePixels(mid, dormant))
    }

    @Test
    fun renderEverySizeAndState() {
        var rendered = 0
        for (dp in listOf(24, 32, 40, 96, 148, 160)) {
            val painter = painterFor(dp)
            for (state in StreakFlameState.values()) {
                val image = render(dp) {
                    painter.draw(this, state, STATIC_FLAME_SECONDS, flashIn = 0f)
                }
                write(image, "flame-${dp}dp-${state.name.lowercase()}.png")
                assertTrue("blank render at ${dp}dp ${state.name}", paintedPixels(image) > 20)
                rendered++
            }
        }
        assertTrue("expected every size/state rendered", rendered == 18)
    }

}
