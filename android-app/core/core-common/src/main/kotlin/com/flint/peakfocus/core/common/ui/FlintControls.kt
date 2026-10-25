package com.flint.peakfocus.core.common.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flint.peakfocus.core.common.theme.FlintMotion
import com.flint.peakfocus.core.common.theme.FlintNumerals
import com.flint.peakfocus.core.common.theme.FlintPalette

/*
 * Ember & Mist controls (design/ember-mist/E00-System.dc.html). One ember action per screen,
 * a bone confirm, quiet text actions, hairline ghosts, chips, segmented pills and the switch.
 */

/** True when the system animator scale is off — ambient motion (glow, breath, drift) stops. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) FlintMotion.PressedScale else 1f,
        animationSpec = FlintMotion.SpringSettle,
        label = "flintPressScale",
    )
    return scale
}

/** The one ember action per screen: 56dp pill, ember gradient, warm shadow. */
@Composable
fun EmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = FlintPalette.OnEmber,
        interactionSource = source,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .shadow(if (enabled) 18.dp else 0.dp, CircleShape, ambientColor = FlintPalette.Ember, spotColor = FlintPalette.Ember),
    ) {
        Box(
            Modifier.background(Brush.verticalGradient(listOf(FlintPalette.EmberTop, FlintPalette.Ember))),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The calm confirm: bone pill, dark text ("Done", "Keep focusing", "Save"). */
@Composable
fun BoneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = FlintPalette.Bone,
        contentColor = FlintPalette.OnBone,
        interactionSource = source,
        modifier = (if (compact) modifier.height(36.dp) else modifier.fillMaxWidth().height(56.dp))
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f },
    ) {
        Box(Modifier.padding(horizontal = if (compact) 16.dp else 0.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = if (compact) MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.titleSmall.fontSize)
                else MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** The secondary text action under a primary one ("Take a break", "Start another"). */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
    color: Color = FlintPalette.Bone,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = if (enabled) color else FlintPalette.Faint,
        border = if (outlined) BorderStroke(1.dp, FlintPalette.Divider) else null,
        modifier = modifier.fillMaxWidth().height(if (outlined) 52.dp else 48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
    }
}

/** Hairline capsule, 44dp ("Emergency Pass · 1", "End session"). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = FlintPalette.Bone,
        border = BorderStroke(1.dp, FlintPalette.Border),
        interactionSource = source,
        modifier = modifier
            .heightIn(min = 44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.5f },
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = MaterialTheme.typography.titleSmall.fontWeight), maxLines = 1)
        }
    }
}

/** Round icon button with a hairline ring (−/+ steppers, "New rule", close). */
@Composable
fun FlintCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    filled: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (filled) FlintPalette.BoneFill else Color.Transparent,
        contentColor = FlintPalette.Bone,
        border = if (filled) null else BorderStroke(1.dp, FlintPalette.Border),
        modifier = modifier.size(size).semanticsLabel(contentDescription),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** Preset / choice chip. Selected = bone fill with dark text. */
@Composable
fun FlintChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) FlintPalette.Bone else FlintPalette.BoneFill,
        contentColor = if (selected) FlintPalette.OnBone else FlintPalette.Bone,
        border = BorderStroke(1.dp, if (selected) FlintPalette.Bone else FlintPalette.Border),
        modifier = modifier.heightIn(min = height).selectableRole(selected),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Chip text: 13/500, tabular digits. */
@Composable
fun FlintChipText(text: String, color: Color = Color.Unspecified) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.merge(FlintNumerals).copy(fontWeight = MaterialTheme.typography.titleSmall.fontWeight),
        color = color,
        maxLines = 1,
    )
}

/** Pill track with a raised segment — Block/Allow list, break level, Today/7 days. */
@Composable
fun <T> FlintSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .background(if (compact) FlintPalette.BoneFill else FlintPalette.BoneTrack)
            .padding(if (compact) 3.dp else 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val fill by animateColorAsState(
                if (isSelected) FlintPalette.Segment else Color.Transparent,
                tween(FlintMotion.DurationShort),
                label = "segmentFill",
            )
            Box(
                (if (compact) Modifier else Modifier.weight(1f))
                    .clip(RoundedCornerShape(if (compact) 13.dp else 14.dp))
                    .background(fill)
                    .selectable(isSelected, enabled = enabled, role = Role.Tab) { onSelect(value) }
                    .heightIn(min = if (compact) 36.dp else 40.dp)
                    .padding(horizontal = if (compact) 12.dp else 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = if (compact) MaterialTheme.typography.bodySmall.fontSize else MaterialTheme.typography.bodyMedium.fontSize,
                        fontWeight = MaterialTheme.typography.titleSmall.fontWeight,
                    ),
                    color = if (isSelected) FlintPalette.Bone else FlintPalette.Mist,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Switch colours: ember track and warm knob on, bone@0.14 track and mist knob off. */
@Composable
fun flintSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = FlintPalette.Knob,
    checkedTrackColor = FlintPalette.Ember,
    checkedBorderColor = FlintPalette.Ember,
    checkedIconColor = FlintPalette.Ember,
    uncheckedThumbColor = FlintPalette.Mist,
    uncheckedTrackColor = FlintPalette.SwitchOff,
    uncheckedBorderColor = FlintPalette.SwitchOff,
)

/** A list row: title (+ subtitle), trailing value or slot, optional chevron, hairline beneath. */
@Composable
fun FlintRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    chevron: Boolean = false,
    titleColor: Color = FlintPalette.Bone,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val base = if (onClick != null) modifier.clip(RoundedCornerShape(2.dp)).flintClickable(onClick) else modifier
    Column(base.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FlintPalette.Mist)
                }
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodySmall.merge(FlintNumerals), color = FlintPalette.Mist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
            if (chevron) FlintChevron()
        }
        FlintHairline()
    }
}

@Composable
fun FlintHairline(modifier: Modifier = Modifier, color: Color = FlintPalette.Hairline) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

@Composable
fun FlintChevron() {
    Icon(
        imageVector = FlintGlyphs.chevronRight,
        contentDescription = null,
        tint = FlintPalette.Mist,
        modifier = Modifier.size(16.dp),
    )
}

/** Eyebrow: 11/500/+2, uppercase, mist. */
@Composable
fun FlintEyebrow(text: String, modifier: Modifier = Modifier, color: Color = FlintPalette.Mist, textAlign: TextAlign? = null) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = color, modifier = modifier, textAlign = textAlign)
}

/** The lock line under privacy-sensitive screens. */
@Composable
fun FlintPrivacyNote(text: String, modifier: Modifier = Modifier, color: Color = FlintPalette.Mist) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(FlintGlyphs.lock, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** A selectable card (break levels): ember wash + ember border when chosen. */
@Composable
fun FlintChoiceCard(
    title: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) FlintPalette.EmberWash else FlintPalette.BoneFaint)
            .border(1.dp, if (selected) FlintPalette.Ember.copy(alpha = 0.7f) else FlintPalette.Divider, RoundedCornerShape(18.dp))
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = FlintPalette.Bone)
        Text(hint, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = FlintPalette.Mist)
    }
}

/** A tappable quiet panel: title + subtitle + chevron ("Block 7 apps · 2 sites"). */
@Composable
fun FlintNavPanel(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlintCard(
        modifier = modifier,
        onClick = if (enabled) onClick else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FlintPalette.Mist)
            }
            Spacer(Modifier.width(12.dp))
            FlintChevron()
        }
    }
}
