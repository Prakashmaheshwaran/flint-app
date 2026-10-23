package com.flint.peakfocus

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.flint.peakfocus.feature.connected.ConnectedConfig
import com.flint.peakfocus.feature.connected.ConnectedScreen

/** Emulator-only tooling; the account client is not a release dependency. */
@Composable
internal fun ConnectedDevelopmentScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ConnectedScreen(config = ConnectedConfig.localEmulator, onBack = onBack, modifier = modifier)
}
