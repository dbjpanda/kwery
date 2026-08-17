package dev.kwery.sample

import android.app.Application
import dev.kwery.QueryClient
import dev.kwery.QueryClientConfig
import dev.kwery.QueryOptions
import dev.kwery.StaleTime
import dev.kwery.android.AndroidFocusManager
import dev.kwery.android.AndroidOnlineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration.Companion.seconds

/**
 * One [QueryClient] per application.
 *
 * Two clients hold entirely separate caches, so creating one per screen would
 * quietly defeat the whole point — two screens asking for the same data would
 * each fetch it.
 */
class SampleApplication : Application() {

    val api: FakeApi = FakeApi()

    lateinit var queryClient: QueryClient
        private set

    private lateinit var onlineManager: AndroidOnlineManager

    override fun onCreate() {
        super.onCreate()

        onlineManager = AndroidOnlineManager(this)

        queryClient = QueryClient(
            scope = CoroutineScope(SupervisorJob()),
            config = QueryClientConfig(
                // Foreground detection and validated connectivity. Without
                // these the core's JVM defaults assume "always focused, always
                // online", which is right for tests and wrong on a device.
                focusManager = AndroidFocusManager(),
                onlineManager = onlineManager,
                defaultQueryOptions = QueryOptions(
                    // Deliberately non-zero, unlike the library default. The
                    // library matches TanStack (stale immediately); an app
                    // usually wants a few seconds of freshness so navigating
                    // back and forth does not refetch every time.
                    staleTime = StaleTime.of(10.seconds),
                ),
            ),
        )
    }

    override fun onTerminate() {
        // Not reliably called on a real device — shown for completeness, since
        // AndroidOnlineManager registers a system callback that should be
        // released when the client genuinely goes away.
        onlineManager.close()
        queryClient.close()
        super.onTerminate()
    }
}
