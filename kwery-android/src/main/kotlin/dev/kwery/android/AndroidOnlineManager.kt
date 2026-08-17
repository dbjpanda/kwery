package dev.kwery.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.kwery.OnlineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports connectivity, driven by [ConnectivityManager].
 *
 * Crucially this requires a **validated** network, not merely a connected one.
 * A captive portal — hotel Wi-Fi, an airport, a corporate guest network — and
 * connected-but-dead Wi-Fi both report a perfectly good network to which
 * nothing can actually be sent. Treating those as online means every request
 * fails while the library insists it is connected, and queries never enter the
 * paused state that would let them resume correctly.
 *
 * `NET_CAPABILITY_VALIDATED` is Android telling you it has actually reached the
 * internet through that network. TanStack has no equivalent problem, because
 * `navigator.onLine` is browser-managed.
 *
 * Requires `ACCESS_NETWORK_STATE`, which this module's manifest contributes
 * automatically.
 *
 * ```kotlin
 * val onlineManager = AndroidOnlineManager(context)
 * val client = QueryClient(config = QueryClientConfig(onlineManager = onlineManager))
 * // when the client is disposed:
 * onlineManager.close()
 * ```
 */
public class AndroidOnlineManager(context: Context) : OnlineManager, AutoCloseable {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private val state = MutableStateFlow(currentlyUsable())

    override val isOnline: StateFlow<Boolean> = state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            // Deliberately keyed off capabilities rather than onAvailable:
            // a captive portal fires onAvailable long before — or without ever
            // — becoming validated.
            state.value = networkCapabilities.isUsable()
        }

        override fun onLost(network: Network) {
            state.value = false
        }

        override fun onUnavailable() {
            state.value = false
        }
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    /** Stop listening. Safe to call more than once. */
    override fun close() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (ignored: IllegalArgumentException) {
            // Already unregistered; the platform throws rather than no-oping.
        }
    }

    private fun currentlyUsable(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(active)?.isUsable() ?: false
    }
}

private fun NetworkCapabilities.isUsable(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
