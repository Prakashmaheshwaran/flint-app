package com.flint.peakfocus.blocking.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flint.peakfocus.core.common.theme.FlintTheme
import com.flint.peakfocus.feature.blockscreen.BlockScreen
import com.flint.peakfocus.feature.blockscreen.BlockScreenState

/**
 * Hosts the shared, branded `BlockScreen` composable inside a raw window (a
 * `TYPE_ACCESSIBILITY_OVERLAY` window on Path A, `TYPE_APPLICATION_OVERLAY` on Path B) —
 * windows that, unlike an Activity, provide no lifecycle for Compose to hang off.
 *
 * Compose requires the window's root view tree to expose a [LifecycleOwner] and a
 * [SavedStateRegistryOwner] (`ComposeView` refuses to compose otherwise), so this view *is*
 * both owners and installs itself via [setViewTreeLifecycleOwner] /
 * [setViewTreeSavedStateRegistryOwner] before composition can start. The repo had no prior
 * pattern for this; this is the standard hand-rolled-owner approach for overlay windows.
 *
 * Lifecycle contract — **one-shot**: create a fresh instance per show. CREATED at
 * construction, RESUMED on window attach, DESTROYED on detach (which also disposes the
 * composition via ComposeView's default strategy). Never re-attach a detached instance.
 *
 * The host owns the clock: call [render] with a fresh [BlockScreenState] (e.g. once per
 * second) to tick countdowns; this view holds no timers of its own. Every color comes from
 * [FlintTheme] tokens — nothing hard-coded (dark theme: the brand-primary block-screen look).
 */
@SuppressLint("ViewConstructor") // programmatic-only view; never inflated from XML
class BlockScreenHostView(
    context: Context,
    onDismiss: () -> Unit,
    onOpenFlint: () -> Unit,
    onBreakRequested: () -> Unit,
    onEmergencyPassRequested: () -> Unit,
) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    /** Compose-observable render state; null until the first [render] (composes to nothing). */
    private val state = mutableStateOf<BlockScreenState?>(null)

    init {
        // Capture touches so the app beneath the overlay is unusable (mirrors the previous
        // hand-built overlay's behavior; the BlockScreen surface consumes touches anyway).
        isClickable = true

        // Order matters: restore the (empty) saved state while still INITIALIZED, install the
        // ViewTree owners, then move to CREATED — all before any window attach can compose.
        savedStateRegistryController.performRestore(null)
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        addView(
            ComposeView(context).apply {
                setContent {
                    val current = state.value ?: return@setContent
                    FlintTheme(darkTheme = true) {
                        BlockScreen(
                            state = current,
                            onDismiss = onDismiss,
                            onOpenFlint = onOpenFlint,
                            onBreakRequested = onBreakRequested,
                            onEmergencyPassRequested = onEmergencyPassRequested,
                        )
                    }
                }
            },
        )
    }

    /** Show (or re-render) the block screen with [newState]. Call on the main thread. */
    fun render(newState: BlockScreenState) {
        state.value = newState
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDetachedFromWindow()
    }
}
