package com.flint.peakfocus

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import com.flint.peakfocus.feature.onboarding.SetupStep

/** Shared by Today setup and You fixes. Only a pending disclosure can open Accessibility Settings. */
internal class AccessibilityDisclosureNavigation(initiallyVisible: Boolean = false) {
    var showDisclosure by mutableStateOf(initiallyVisible)
        private set

    fun request() { showDisclosure = true }

    fun setup(step: SetupStep, openOtherSettings: (SetupStep) -> Unit) {
        if (step == SetupStep.ENABLE_ACCESSIBILITY) request() else openOtherSettings(step)
    }

    fun accept(openAccessibilitySettings: () -> Unit) {
        if (!showDisclosure) return
        showDisclosure = false
        openAccessibilitySettings()
    }

    fun decline() { showDisclosure = false }

    companion object {
        val StateSaver = Saver<AccessibilityDisclosureNavigation, Boolean>(
            save = { it.showDisclosure },
            restore = { AccessibilityDisclosureNavigation(it) },
        )
    }
}
