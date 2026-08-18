package dev.kwery.test

import dev.kwery.FocusManager
import dev.kwery.OnlineManager
import dev.kwery.QueryClient
import dev.kwery.QueryClientConfig
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryState
import dev.kwery.RetryPolicy
import dev.kwery.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A [QueryClient] configured so tests are deterministic by default.
 *
 * TanStack's testing guide is four things you must remember to configure —
 * disable retries, use a fresh client, neutralise timers, silence logging.
 * Forgetting any of them produces a suite that hangs or flakes. Kwery ships the
 * correct configuration instead:
 *
 * - **no retries**, so asserting an error state does not wait through three
 *   exponential backoffs
 * - a **virtual clock** driven by the [TestScope]'s scheduler, so `staleTime`
 *   and `gcTime` advance without real `delay()`
 * - **controllable connectivity and focus**
 * - **request recording**, because nearly every meaningful assertion about a
 *   caching library is "how many requests actually went out?"
 *
 * ```kotlin
 * @Test fun example() = runTest {
 *     val kwery = TestQueryClient(this)
 *     val job = backgroundScope.launch {
 *         kwery.query(TodoKey("1")) { "data" }.collect { }
 *     }
 *     kwery.settle()
 *     assertEquals(1, kwery.requestCount)
 * }
 * ```
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
public class TestQueryClient(
    private val testScope: TestScope,
    gracePeriod: Duration = 5.seconds,
    maxEntries: Int = 500,
    defaultQueryOptions: QueryOptions = QueryOptions(retry = RetryPolicy.Never),
) {
    private val focusFlow = MutableStateFlow(true)
    private val onlineFlow = MutableStateFlow(true)
    private val requests = CopyOnWriteArrayList<QueryKey<*>>()

    /** The real client under test. Nothing here is a fake but the environment. */
    public val client: QueryClient = QueryClient(
        scope = testScope.backgroundScope,
        config = QueryClientConfig(
            timeSource = TimeSource { testScope.testScheduler.currentTime },
            focusManager = object : FocusManager {
                override val isFocused: StateFlow<Boolean> = focusFlow.asStateFlow()
            },
            onlineManager = object : OnlineManager {
                override val isOnline: StateFlow<Boolean> = onlineFlow.asStateFlow()
            },
            gracePeriod = gracePeriod,
            maxEntries = maxEntries,
            defaultQueryOptions = defaultQueryOptions,
        ),
    )

    // ---- Request recording -----------------------------------------------

    /** Every fetcher invocation so far, in order, including retries. */
    public val recordedRequests: List<QueryKey<*>> get() = requests.toList()

    public val requestCount: Int get() = requests.size

    /** Requests recorded for one key. */
    public fun requestCountFor(key: QueryKey<*>): Int = requests.count { it == key }

    public fun clearRecordedRequests(): Unit = requests.clear()

    /**
     * Observe [key], recording every fetcher invocation.
     *
     * Prefer this over [client].query in tests — request counts are the whole
     * point of most assertions here.
     */
    public fun <T> query(
        key: QueryKey<T>,
        options: QueryOptions = client.config.defaultQueryOptions,
        initialData: dev.kwery.InitialData<T>? = null,
        fetcher: suspend () -> T,
    ): Flow<QueryState<T>> = client.query(key, options, initialData) {
        requests += key
        fetcher()
    }

    // ---- Environment control ---------------------------------------------

    public fun setOnline(online: Boolean) {
        onlineFlow.value = online
    }

    public fun setFocused(focused: Boolean) {
        focusFlow.value = focused
    }

    // ---- Time control ----------------------------------------------------

    /**
     * Advance virtual time and run everything that becomes due.
     *
     * Note `advanceUntilIdle()` does **not** dispatch coroutines launched in
     * `backgroundScope`, which is where observers live. Use this instead —
     * getting it wrong silently reports zero requests for work that never ran.
     */
    public fun settle(duration: Duration = Duration.ZERO) {
        testScope.testScheduler.runCurrent()
        if (duration > Duration.ZERO) {
            testScope.testScheduler.advanceTimeBy(duration.inWholeMilliseconds)
        }
        testScope.testScheduler.runCurrent()
    }

    /**
     * Suspend until nothing is fetching or mutating.
     *
     * The replacement for the `waitFor` / `eventually` polling that makes async
     * tests flaky. Because time here is virtual, this does not wait — it
     * *advances*, so a query sleeping through a retry backoff settles instantly.
     *
     * ```kotlin
     * val job = backgroundScope.launch { kwery.query(key) { api.todo() }.collect { } }
     * kwery.awaitIdle()
     * assertEquals(1, kwery.requestCount)
     * ```
     *
     * A polling query does **not** block this: between ticks it is genuinely
     * idle, so `awaitIdle` returns in the gap. What does block it is a fetcher
     * that never completes — most often a `CompletableDeferred` the test forgot
     * to complete. That throws with a message saying so, rather than hanging
     * until the framework's timeout with no clue why.
     */
    public suspend fun awaitIdle(limit: Duration = 10.minutes) {
        val scheduler = testScope.testScheduler
        val deadline = scheduler.currentTime + limit.inWholeMilliseconds
        var step = 1L
        scheduler.runCurrent()
        while (client.isFetching.value > 0 || client.isMutating.value > 0) {
            if (scheduler.currentTime >= deadline) {
                error(
                    "awaitIdle: still busy after $limit of virtual time " +
                        "(${client.isFetching.value} fetching, " +
                        "${client.isMutating.value} mutating). " +
                        "The usual cause is a fetcher that never completes — a " +
                        "CompletableDeferred the test never completed, or a " +
                        "suspending call with no virtual-time equivalent.",
                )
            }
            // The step grows so that fine-grained ordering is resolved first
            // and long waits are then crossed quickly: a 30-second retry
            // backoff costs a handful of iterations, not thirty thousand.
            val before = scheduler.currentTime
            scheduler.advanceTimeBy(step)
            scheduler.runCurrent()
            if (scheduler.currentTime == before) break
            step = (step * 2).coerceAtMost(1_000)
        }
        scheduler.runCurrent()
    }

    /** Current virtual time in millis. */
    public val currentTimeMillis: Long get() = testScope.testScheduler.currentTime
}
