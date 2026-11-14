package com.flint.peakfocus.core.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette
import com.flint.peakfocus.core.common.theme.FlintSpacing
import com.flint.peakfocus.core.common.theme.FlintText

/**
 * Bottom sheet celebration for Focus Streak — professional sheet rising from below.
 * Flame → number → DAY STREAK → motivation → Continue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusStreakCelebrationSheet(
    streak: Int,
    mood: StreakEmberMood,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val numberColor = when (mood) {
        StreakEmberMood.Active -> FlintPalette.Ember
        StreakEmberMood.Warning -> StreakCoolDeep
        StreakEmberMood.Dormant -> FlintPalette.Mist.copy(alpha = 0.75f)
    }
    // Hero number — Opal sheet gradient: light top melting into the mood color.
    val numberBrush = when (mood) {
        StreakEmberMood.Active -> Brush.verticalGradient(
            listOf(Color(0xFFFFE9B0), FlintPalette.Ember),
        )
        StreakEmberMood.Warning -> Brush.verticalGradient(
            listOf(StreakCoolTop, StreakCoolDeep),
        )
        StreakEmberMood.Dormant -> null
    }
    val numberStyle = TextStyle(
        fontFamily = FlintText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 68.sp,
        lineHeight = 72.sp,
        letterSpacing = (-1.4).sp,
    ).merge(FlintNumerals)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("streak.celebration"),
        sheetState = sheetState,
        containerColor = Color(0xFF0A090B),
        contentColor = FlintPalette.Bone,
        scrimColor = Color(0xFF07080B).copy(alpha = 0.72f),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(40.dp, 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(FlintPalette.Bone.copy(alpha = 0.18f)),
            )
        },
    ) {
        // Minimum, not fixed: the number/label/motivation scale with the system font, so a
        // hard 460dp box clipped content (and pushed Continue off the sheet) at Large and
        // accessibility font sizes. The column scrolls only when the content still exceeds
        // the available sheet height; the backdrop keeps filling whatever the box becomes.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 460.dp),
        ) {
            FlintStreakCinematicStage(mood = mood, modifier = Modifier.matchParentSize())
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp)
                    .padding(top = 12.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StreakFlame(
                    state = mood.toFlameState(),
                    streakCount = streak,
                    size = 148.dp,
                    contentDescription = null,
                )
                Text(
                    streak.toString(),
                    style = if (numberBrush == null) {
                        numberStyle
                    } else {
                        numberStyle.copy(brush = numberBrush)
                    },
                    color = if (numberBrush == null) numberColor else Color.Unspecified,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    FocusStreakSheetCopy.DAY_STREAK.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = numberColor.copy(alpha = 0.92f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    FocusStreakSheetCopy.motivation(mood, streak),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FlintPalette.Mist.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, start = 8.dp, end = 8.dp),
                )
                Spacer(Modifier.height(FlintSpacing.xl))
                EmberButton(
                    FocusStreakSheetCopy.CONTINUE,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
