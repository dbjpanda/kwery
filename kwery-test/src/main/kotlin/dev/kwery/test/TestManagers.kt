package dev.kwery.test

import dev.kwery.FocusManager
import dev.kwery.OnlineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [OnlineManager] whose connectivity you set directly.
 *
 * [TestQueryClient] already wires one of these up and exposes
 * [TestQueryClient.setOnline]. This exists for the other case: a consumer who
 * builds their own [dev.kwery.QueryClient] — with a real persister, say — and
 * still needs to drive connectivity.
 *
 * ```kotlin
 * val online = TestOnlineManager()
 * val client = QueryClient(scope, QueryClientConfig(onlineManager = online))
 *
 * online.setOnline(false)
 * // …assert FetchStatus.Paused
 * ```
 */
public class TestOnlineManager(initiallyOnline: Boolean = true) : OnlineManager {
    private val flow = MutableStateFlow(initiallyOnline)

    override val isOnline: StateFlow<Boolean> = flow.asStateFlow()

    public fun setOnline(online: Boolean) {
        flow.value = online
    }
}

/**
 * A [FocusManager] whose foreground state you set directly.
 *
 * The counterpart to [TestOnlineManager], for exercising focus-triggered
 * refetching without `ProcessLifecycleOwner` — and so without Robolectric.
 */
public class TestFocusManager(initiallyFocused: Boolean = true) : FocusManager {
    private val flow = MutableStateFlow(initiallyFocused)

    override val isFocused: StateFlow<Boolean> = flow.asStateFlow()

    public fun setFocused(focused: Boolean) {
        flow.value = focused
    }
}
