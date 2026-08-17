package dev.kwery.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.kwery.FocusManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports app foreground state, driven by [ProcessLifecycleOwner].
 *
 * TanStack Query's `focusManager` listens for browser window focus. The Android
 * analogue is deliberately **process** lifecycle — the whole app moving between
 * foreground and background — rather than per-Activity focus.
 *
 * Per-Activity focus would fire for dialogs, permission prompts, and the app
 * switcher, so every one of those would trigger a refetch of every visible
 * query. Process lifecycle fires only when the user actually leaves and returns.
 *
 * Kwery additionally ignores returns that happen inside the client's grace
 * window, so a brief app switch refetches nothing at all.
 *
 * ```kotlin
 * val client = QueryClient(
 *     config = QueryClientConfig(focusManager = AndroidFocusManager()),
 * )
 * ```
 *
 * Construct on the main thread: [ProcessLifecycleOwner.get] requires it.
 */
public class AndroidFocusManager(
    lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
) : FocusManager {

    private val state = MutableStateFlow(
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
    )

    override val isFocused: StateFlow<Boolean> = state.asStateFlow()

    init {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> state.value = true
                    Lifecycle.Event.ON_STOP -> state.value = false
                    else -> Unit
                }
            },
        )
    }
}
