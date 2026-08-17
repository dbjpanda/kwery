package dev.kwery.test

import dev.kwery.FetchStatus
import dev.kwery.QueryFilters
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryStatus
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class TodoKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("todo", id)
}

private const val FETCH_MS = 100L

/**
 * Behavioural tests for the cache and observer machinery.
 *
 * These live in `kwery-test` rather than `kwery-core` so they exercise
 * [TestQueryClient] — dogfooding it is how we find out whether it is any good.
 *
 * Scenario IDs (S1, S9, S11…) refer to the spike harness recorded in
 * `docs/roadmap/05-deduplication-observers.md`; the numbers those produced are
 * the expected values here.
 */
class QueryCacheBehaviourTest {

    private fun CoroutineScope.observe(
        kwery: TestQueryClient,
        key: TodoKey = TodoKey("1"),
        options: QueryOptions = kwery.client.config.defaultQueryOptions,
        fetchMs: Long = FETCH_MS,
        value: String = "data",
    ): Job = launch {
        kwery.query(key, options) { delay(fetchMs); value }.collect { }
    }

    // ---- Deduplication (S1, S2) ------------------------------------------

    @Test
    fun `two concurrent observers issue one request`() = runTest {
        val kwery = TestQueryClient(this)
        val a = backgroundScope.observe(kwery)
        val b = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount, "observers of one key must share a request")
        a.cancel(); b.cancel()
    }

    @Test
    fun `an observer attaching mid-flight joins the existing request`() = runTest {
        val kwery = TestQueryClient(this)
        val a = backgroundScope.observe(kwery)
        kwery.settle(50.milliseconds)
        val b = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount)
        a.cancel(); b.cancel()
    }

    // ---- Approach C-prime: rotation (S11, S12, S13) ----------------------

    @Test
    fun `rotation inside the grace window causes no refetch`() = runTest {
        // THE test for approach C'. With staleTime = 0 (the default), a naive
        // implementation refetches on every rotation. Measured in the spike:
        // 1 extra request without reattach suppression, 0 with it.
        val kwery = TestQueryClient(this)
        val first = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)
        assertEquals(1, kwery.requestCount)

        first.cancel()
        kwery.settle(50.milliseconds) // recreated 50ms later, inside grace
        val second = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount, "rotation must not refetch")
        second.cancel()
    }

    @Test
    fun `returning after the grace window does refetch`() = runTest {
        val kwery = TestQueryClient(this)
        val first = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)

        first.cancel()
        kwery.settle(30.seconds) // long gone
        val second = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)

        assertEquals(2, kwery.requestCount, "stale data must refresh on a real return")
        second.cancel()
    }

    @Test
    fun `the grace boundary is sharp`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 5.seconds)
        val first = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)

        first.cancel()
        kwery.settle(5100.milliseconds) // 100ms past grace
        val second = backgroundScope.observe(kwery)
        kwery.settle(200.milliseconds)

        assertEquals(2, kwery.requestCount)
        second.cancel()
    }

    @Test
    fun `a fresh query does not refetch even after the grace window`() = runTest {
        val kwery = TestQueryClient(this)
        val options = QueryOptions(staleTime = StaleTime.of(5.minutes), retry = RetryPolicy.Never)
        val first = backgroundScope.observe(kwery, options = options)
        kwery.settle(200.milliseconds)

        first.cancel()
        kwery.settle(30.seconds)
        val second = backgroundScope.observe(kwery, options = options)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount, "data is still fresh; staleTime governs")
        second.cancel()
    }

    // ---- In-flight requests across detach (S9, S10) ----------------------

    @Test
    fun `leaving and returning inside grace joins the in-flight request`() = runTest {
        val kwery = TestQueryClient(this)
        val first = backgroundScope.observe(kwery, fetchMs = 10_000)
        kwery.settle(1.seconds)

        first.cancel()
        kwery.settle(50.milliseconds)
        val second = backgroundScope.observe(kwery, fetchMs = 10_000)
        kwery.settle(20.seconds)

        assertEquals(1, kwery.requestCount, "the in-flight request must survive, not restart")
        second.cancel()
    }

    @Test
    fun `abandoning a request cancels it at grace expiry`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 5.seconds)
        val key = TodoKey("1")
        val job = backgroundScope.observe(kwery, key, fetchMs = 30_000)
        kwery.settle(1.seconds)
        job.cancel()
        kwery.settle(6.seconds) // past grace

        val state = kwery.client.getQueryState(key)
        assertNotNull(state)
        assertEquals(FetchStatus.Idle, state.fetchStatus)
        // Cancellation is not failure: no error, and status is untouched.
        assertNull(state.error)
        assertEquals(QueryStatus.Pending, state.status)
    }

    // ---- Eviction (S5) ---------------------------------------------------

    @Test
    fun `an entry is evicted grace plus gcTime after the last observer leaves`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 5.seconds)
        val key = TodoKey("1")
        val options = QueryOptions(gcTime = 1.minutes, retry = RetryPolicy.Never)
        val job = backgroundScope.observe(kwery, key, options)
        kwery.settle(200.milliseconds)
        job.cancel()

        kwery.settle(50.seconds)
        assertTrue(kwery.client.cacheSnapshot().isNotEmpty(), "still cached before gcTime")

        kwery.settle(20.seconds) // now past grace + gcTime
        assertTrue(kwery.client.cacheSnapshot().isEmpty(), "evicted after grace + gcTime")
    }

    @Test
    fun `an observed entry is never evicted`() = runTest {
        val kwery = TestQueryClient(this)
        val options = QueryOptions(gcTime = 1.seconds, retry = RetryPolicy.Never)
        val job = backgroundScope.observe(kwery, options = options)
        kwery.settle(200.milliseconds)

        kwery.settle(10.minutes)
        assertEquals(1, kwery.client.cacheSnapshot().size, "an observed entry must survive")
        job.cancel()
    }

    /** Ported: `should use the longest garbage collection time it has seen`. */
    @Test
    fun `the longest gcTime seen for a key wins`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 1.milliseconds)
        val key = TodoKey("1")

        val long = backgroundScope.observe(kwery, key, QueryOptions(gcTime = 10.minutes, retry = RetryPolicy.Never))
        kwery.settle(200.milliseconds)
        long.cancel()

        // A second, shorter-lived observer must not shorten the retention the
        // first one asked for.
        val short = backgroundScope.observe(kwery, key, QueryOptions(gcTime = 1.seconds, retry = RetryPolicy.Never))
        kwery.settle(200.milliseconds)
        short.cancel()

        kwery.settle(1.minutes)
        assertTrue(
            kwery.client.cacheSnapshot().isNotEmpty(),
            "the 10-minute gcTime must still apply, not the 1-second one",
        )
    }

    // ---- Status preservation (ported) ------------------------------------

    /** Ported: `the previous query status should be kept when refetching`. */
    @Test
    fun `the previous status is kept while refetching`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")
        val job = backgroundScope.observe(kwery, key)
        kwery.settle(200.milliseconds)
        assertEquals(QueryStatus.Success, kwery.client.getQueryState(key)?.status)

        kwery.client.invalidateQueries(key)
        kwery.settle(10.milliseconds) // mid-flight

        val midFlight = kwery.client.getQueryState(key)
        assertNotNull(midFlight)
        assertEquals(FetchStatus.Fetching, midFlight.fetchStatus)
        assertEquals(
            QueryStatus.Success,
            midFlight.status,
            "status must not revert to Pending during a background refetch",
        )
        assertEquals("data", midFlight.data, "data stays on screen while refreshing")
        job.cancel()
    }

    @Test
    fun `data is retained when a background refetch fails`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")
        var shouldFail = false

        val job = backgroundScope.launch {
            kwery.query(key) {
                delay(FETCH_MS)
                if (shouldFail) throw RuntimeException("boom") else "good"
            }.collect { }
        }
        kwery.settle(200.milliseconds)
        assertEquals("good", kwery.client.getQueryData(key))

        shouldFail = true
        kwery.client.invalidateQueries(key)
        kwery.settle(200.milliseconds)

        val state = kwery.client.getQueryState(key)
        assertNotNull(state)
        assertEquals(QueryStatus.Error, state.status)
        assertEquals("good", state.data, "a failed refetch must not blank the screen")
        job.cancel()
    }

    // ---- Invalidation ----------------------------------------------------

    /** Ported: `should not change state on invalidate() if already invalidated`. */
    @Test
    fun `invalidating twice does not fetch twice`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")
        val job = backgroundScope.observe(kwery, key, fetchMs = 10_000)
        kwery.settle(1.seconds) // still in flight

        kwery.client.invalidateQueries(key)
        kwery.client.invalidateQueries(key)
        kwery.settle(20.seconds)

        assertEquals(1, kwery.requestCount, "invalidate must be idempotent")
        job.cancel()
    }

    @Test
    fun `a disabled query ignores invalidation`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")
        val options = QueryOptions(enabled = false, retry = RetryPolicy.Never)
        val job = backgroundScope.observe(kwery, key, options)
        kwery.settle(200.milliseconds)
        assertEquals(0, kwery.requestCount, "a disabled query never fetches on attach")

        kwery.client.invalidateQueries(QueryFilters.All)
        kwery.settle(200.milliseconds)
        assertEquals(0, kwery.requestCount, "and it ignores invalidation")
        job.cancel()
    }

    @Test
    fun `a Static query ignores invalidation but an Infinite one does not`() = runTest {
        val kwery = TestQueryClient(this)
        val staticKey = TodoKey("static")
        val infiniteKey = TodoKey("infinite")

        val a = backgroundScope.observe(
            kwery, staticKey, QueryOptions(staleTime = StaleTime.Static, retry = RetryPolicy.Never),
        )
        val b = backgroundScope.observe(
            kwery, infiniteKey, QueryOptions(staleTime = StaleTime.Infinite, retry = RetryPolicy.Never),
        )
        kwery.settle(200.milliseconds)
        assertEquals(1, kwery.requestCountFor(staticKey))
        assertEquals(1, kwery.requestCountFor(infiniteKey))

        kwery.client.invalidateQueries(QueryFilters.All)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCountFor(staticKey), "Static ignores invalidation")
        assertEquals(2, kwery.requestCountFor(infiniteKey), "Infinite yields to invalidation")
        a.cancel(); b.cancel()
    }

    @Test
    fun `prefix invalidation refetches only matching active queries`() = runTest {
        val kwery = TestQueryClient(this)
        val todo = TodoKey("1")
        val job = backgroundScope.observe(kwery, todo)
        kwery.settle(200.milliseconds)

        kwery.client.invalidateQueries("other")
        kwery.settle(200.milliseconds)
        assertEquals(1, kwery.requestCount, "non-matching prefix must not refetch")

        kwery.client.invalidateQueries("todo")
        kwery.settle(200.milliseconds)
        assertEquals(2, kwery.requestCount)
        job.cancel()
    }

    // ---- Manual cache access ---------------------------------------------

    @Test
    fun `setQueryData seeds an entry that later adopts a fetcher`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")

        kwery.client.setQueryData(key, "seeded")
        assertEquals("seeded", kwery.client.getQueryData(key))
        assertEquals(0, kwery.requestCount, "seeding is not a fetch")

        // Observing with a real fetcher adopts it; the seeded value is fresh
        // enough that nothing refetches under a non-zero staleTime.
        val job = backgroundScope.observe(
            kwery, key, QueryOptions(staleTime = StaleTime.of(5.minutes), retry = RetryPolicy.Never),
        )
        kwery.settle(200.milliseconds)
        assertEquals("seeded", kwery.client.getQueryData(key))
        assertEquals(0, kwery.requestCount)

        // ...and once invalidated it can actually refetch, unlike TanStack's
        // permanently-frozen seeded entries.
        kwery.client.invalidateQueries(key)
        kwery.settle(200.milliseconds)
        assertEquals(1, kwery.requestCount, "an adopted entry must be able to refetch")
        assertEquals("data", kwery.client.getQueryData(key))
        job.cancel()
    }

    @Test
    fun `updateQueryData only runs when an entry exists`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")

        kwery.client.updateQueryData(key) { "$it!" }
        assertNull(kwery.client.getQueryData(key), "no entry, no write")

        kwery.client.setQueryData(key, "value")
        kwery.client.updateQueryData(key) { "$it!" }
        assertEquals("value!", kwery.client.getQueryData(key))
    }

    // ---- Cancellation (ported) -------------------------------------------

    /** Ported: `cancelling a resolved query should not have any effect`. */
    @Test
    fun `cancelling a resolved query has no effect`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")
        val job = backgroundScope.observe(kwery, key)
        kwery.settle(200.milliseconds)

        val before = kwery.client.getQueryState(key)
        kwery.client.cancelQueries(QueryFilters.All)
        kwery.settle()

        assertEquals(before, kwery.client.getQueryState(key))
        assertEquals("data", kwery.client.getQueryData(key))
        job.cancel()
    }

    /** Ported: `cancelling a rejected query should not have any effect`. */
    @Test
    fun `cancelling a rejected query preserves the original error`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoKey("1")
        val boom = RuntimeException("boom")
        val job = backgroundScope.launch {
            kwery.query(key) { delay(FETCH_MS); throw boom }.collect { }
        }
        kwery.settle(200.milliseconds)
        assertSame(boom, kwery.client.getQueryState(key)?.error)

        kwery.client.cancelQueries(QueryFilters.All)
        kwery.settle()

        assertSame(
            boom,
            kwery.client.getQueryState(key)?.error,
            "the original error must not be replaced by a cancellation",
        )
        job.cancel()
    }

    // ---- Memory bounds ---------------------------------------------------

    @Test
    fun `maxEntries evicts least recently used inactive entries`() = runTest {
        val kwery = TestQueryClient(this, maxEntries = 3)

        repeat(5) { index ->
            val job = backgroundScope.observe(kwery, TodoKey("$index"))
            kwery.settle(200.milliseconds)
            job.cancel()
            kwery.settle(10.milliseconds)
        }
        kwery.settle(100.milliseconds)

        val cached = kwery.client.cacheSnapshot()
        assertTrue(cached.size <= 3, "maxEntries must bound the cache, was ${cached.size}")
        val ids = cached.map { (it.key as TodoKey).id }.toSet()
        assertTrue(ids.contains("4"), "the most recent entry must survive; kept $ids")
    }

    @Test
    fun `an observed entry survives maxEntries pressure`() = runTest {
        val kwery = TestQueryClient(this, maxEntries = 2)
        val pinned = TodoKey("pinned")
        val job = backgroundScope.observe(kwery, pinned)
        kwery.settle(200.milliseconds)

        repeat(5) { index ->
            val other = backgroundScope.observe(kwery, TodoKey("$index"))
            kwery.settle(200.milliseconds)
            other.cancel()
            kwery.settle(10.milliseconds)
        }

        val ids = kwery.client.cacheSnapshot().map { (it.key as TodoKey).id }
        assertTrue(ids.contains("pinned"), "an observed entry is never evicted; kept $ids")
        job.cancel()
    }
}
