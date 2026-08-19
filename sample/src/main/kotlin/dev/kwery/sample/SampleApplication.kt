package dev.kwery.sample

import android.app.Application
import dev.kwery.QueryClient
import dev.kwery.QueryClientConfig
import dev.kwery.QueryOptions
import dev.kwery.StaleTime
import dev.kwery.android.AndroidFocusManager
import dev.kwery.android.AndroidOnlineManager
import dev.kwery.persist.FileMutationQueueStore
import dev.kwery.persist.FilePersister
import dev.kwery.persist.OfflineQueue
import dev.kwery.persist.OfflineQueueOptions
import dev.kwery.persist.PersistOptions
import dev.kwery.persist.persist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.hours
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

    private val appScope = CoroutineScope(SupervisorJob())

    lateinit var queryClient: QueryClient
        private set

    lateinit var queue: OfflineQueue
        private set

    private lateinit var onlineManager: AndroidOnlineManager

    /** How many entries came back from disk on this launch. Shown on screen. */
    private val restored = MutableStateFlow<Int?>(null)
    val restoredEntryCount: StateFlow<Int?> get() = restored.asStateFlow()

    override fun onCreate() {
        super.onCreate()

        onlineManager = AndroidOnlineManager(this)

        queryClient = QueryClient(
            scope = appScope,
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
                    // Must be at least the persistence maxAge below, or
                    // persist() refuses to start: entries would be evicted
                    // from memory long before the stored copy expired, so the
                    // persisted cache would be written and almost never read.
                    gcTime = 1.hours,
                ),
            ),
        )

        queue = OfflineQueue(
            scope = appScope,
            options = OfflineQueueOptions(
                store = FileMutationQueueStore(File(filesDir, "kwery-queue.json")),
            ),
            onlineManager = onlineManager,
            timeSource = queryClient.config.timeSource,
        ) {
            // Registered here, at construction, not on a screen. A write
            // replayed after a cold start has no composable left to ask.
            register(ToggleDoneKey) { input -> api.setDone(input.id, input.done) }
        }

        appScope.launch {
            // Order matters and is enforced by the type system rather than a
            // comment: persist() suspends until the restore has finished, so
            // resume() cannot replay a write onto an empty cache.
            val cache = queryClient.persist(
                scope = appScope,
                options = PersistOptions(
                    persister = FilePersister(File(filesDir, "kwery-cache.json")),
                    // Only keys listed here can be decoded on restore.
                    keys = listOf(TodoListKey(onlyOpen = false), TodoListKey(onlyOpen = true)),
                    maxAge = 1.hours,
                    buster = "sample-1",
                ),
            )
            restored.value = cache.restoredEntryCount

            queue.resume()
        }
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
