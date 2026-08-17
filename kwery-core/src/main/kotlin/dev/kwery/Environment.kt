package dev.kwery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports whether the app is in the foreground.
 *
 * The analogue of TanStack's `focusManager`, which listens for browser window
 * focus. `kwery-core` only declares the contract (AD-1); `kwery-android`
 * implements it against `ProcessLifecycleOwner`.
 *
 * Modelled as a [StateFlow] rather than a listener registry so refetch triggers
 * compose as ordinary Flow operators and tests can drive them with a
 * `MutableStateFlow`.
 */
public interface FocusManager {
    public val isFocused: StateFlow<Boolean>

    public companion object {
        /** Always focused — the JVM default, where there is no app lifecycle. */
        public val AlwaysFocused: FocusManager = object : FocusManager {
            override val isFocused: StateFlow<Boolean> = MutableStateFlow(true)
        }
    }
}

/**
 * Reports whether the network is usable.
 *
 * The analogue of TanStack's `onlineManager`. `kwery-android` implements it
 * against `ConnectivityManager`, and must report a **validated** network rather
 * than merely a connected one: captive portals and connected-but-dead Wi-Fi
 * otherwise report online while every request fails.
 */
public interface OnlineManager {
    public val isOnline: StateFlow<Boolean>

    public companion object {
        /** Always online — the JVM default. */
        public val AlwaysOnline: OnlineManager = object : OnlineManager {
            override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
        }
    }
}

/**
 * How a query behaves without connectivity.
 *
 * @see FetchStatus.Paused
 */
public enum class NetworkMode {
    /**
     * Do not fetch without a connection; report [FetchStatus.Paused] instead.
     * Retries pause too, and **continue** from where they left off on
     * reconnect rather than restarting. The default.
     */
    Online,

    /**
     * Ignore connectivity entirely. Never pauses. Correct for query functions
     * that do not need the network — reading from a local database, for
     * instance — which would otherwise be wrongly paused.
     */
    Always,

    /**
     * Run the query function once, then pause retries. For setups where the
     * first attempt may be served from an HTTP cache or interceptor.
     */
    OfflineFirst,
}
