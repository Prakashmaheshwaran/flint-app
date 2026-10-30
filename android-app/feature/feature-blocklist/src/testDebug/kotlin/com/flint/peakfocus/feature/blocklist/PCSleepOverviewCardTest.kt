package com.flint.peakfocus.feature.blocklist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.core.model.BreakLevel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PCSleepOverviewCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun PC_82_mixedMorningHardcoreUsesTheRealOverviewBadgesEmphasizedFill() {
        var emphasizedFill = Color.Unspecified
        var contentView: View? = null
        compose.setContent { FlintTheme {
            emphasizedFill = MaterialTheme.colorScheme.primary
            contentView = LocalView.current
            SleepCard(SleepConfig(enabled = true, morningEnabled = true,
                breakLevel = BreakLevel.EASY, morningBreakLevel = BreakLevel.HARDCORE), {}, {})
        } }
        val bounds = compose.onNodeWithText("TIER: HARDCORE", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val view = checkNotNull(contentView)
            val pixels = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                // Draw the real Compose view; window PixelCopy waits for a frame callback
                // that Robolectric does not deliver. This uses its native Canvas instead.
                view.draw(Canvas(pixels))
                assertEquals(emphasizedFill, Color(pixels.getPixel(bounds.left.toInt() + 1, bounds.center.y.toInt())))
            } finally { pixels.recycle() }
        }
    }
}
