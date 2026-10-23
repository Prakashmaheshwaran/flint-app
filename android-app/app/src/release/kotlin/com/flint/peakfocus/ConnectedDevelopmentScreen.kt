package com.flint.peakfocus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/** A restored debug navigation destination returns to local Settings in a release build. */
@Composable
internal fun ConnectedDevelopmentScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { onBack() }
}
