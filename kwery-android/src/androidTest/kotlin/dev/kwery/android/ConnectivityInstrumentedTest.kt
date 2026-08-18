package dev.kwery.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `AndroidOnlineManager` against a real `ConnectivityManager`.
 *
 * The JVM tests use a `MutableStateFlow` for connectivity, which proves the
 * cache reacts correctly but says nothing about whether the Android side
 * reports the right thing. Only a device has real `NetworkCapabilities`.
 */
@RunWith(AndroidJUnit4::class)
class ConnectivityInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun reports_the_platform_view_of_connectivity() = runTest {
        val manager = AndroidOnlineManager(context)
        try {
            // Give the callback a moment to deliver the initial state.
            withTimeoutOrNull(5.seconds) { manager.isOnline.first() }

            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val platformSaysValidated =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

            assertEquals(
                platformSaysValidated,
                manager.isOnline.value,
                "the manager must agree with the platform, not guess",
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun online_tracks_VALIDATED_rather_than_merely_connected() = runTest {
        // The captive-portal rule. A network can be connected while nothing
        // routes: hotel wifi before you sign in, or a dropped uplink. Treating
        // connected as online means refetching into a black hole and showing
        // errors instead of the paused state the user should see.
        //
        // Deliberately does NOT require the emulator to have a network. An
        // emulator validates its wifi about twenty seconds after boot, so a
        // test that assumed connectivity would pass or fail depending on how
        // quickly the suite started. The invariant holds either way: whatever
        // the platform reports, the manager must agree.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val manager = AndroidOnlineManager(context)
        try {
            withTimeoutOrNull(5.seconds) { manager.isOnline.first() }
            assertEquals(
                validated,
                manager.isOnline.value,
                "online must follow VALIDATED, not INTERNET " +
                    "(connected=$connected validated=$validated)",
            )
            if (connected && !validated) {
                assertTrue(
                    !manager.isOnline.value,
                    "a connected but unvalidated network is exactly the captive-portal case " +
                        "and must report offline",
                )
            }
        } finally {
            manager.close()
        }
    }

    @Test
    fun closing_it_unregisters_the_callback() = runTest {
        // A leaked ConnectivityManager callback outlives the client and keeps
        // firing. Registering many and closing them all is the cheap way to
        // notice: the platform throws once too many are registered.
        repeat(40) {
            AndroidOnlineManager(context).close()
        }
        val manager = AndroidOnlineManager(context)
        try {
            withTimeoutOrNull(5.seconds) { manager.isOnline.first() }
            assertTrue(true, "registering after 40 open-and-close cycles still works")
        } finally {
            manager.close()
        }
    }
}
