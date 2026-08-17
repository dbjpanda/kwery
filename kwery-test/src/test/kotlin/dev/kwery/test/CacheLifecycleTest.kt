package dev.kwery.test

import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryStatus
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class LifecycleKey(val id: String = "todos") : QueryKey<String> {
    override val parts get() = listOf("lifecycle", id)
}

/** Features 04 and 05 — the cache's lifecycle and observer accounting. */
class CacheLifecycleTest {

    private fun options(
        gcTime: Duration = 5.minutes,
        stale: StaleTime = StaleTime.Zero,
    ) = QueryOptions(staleTime = stale, gcTime = gcTime, retry = RetryPolicy.Never)

    @Test
    fun `the documented five-step lifecycle`() = runTest {
        // Reproduces the sequence in TanStack's caching guide end to end, since
        // each step is individually plausible and it is the ORDER that is easy
        // to get wrong.
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val key = LifecycleKey()
        val opts = options(gcTime = 30.seconds)

        // 1. First observer: hard loading state, one request, result cached.
        val first = backgroundScope.launch {
            kwery.query(key, opts) { delay(100); "data" }.collect { }
        }
        kwery.settle(20.milliseconds)
        assertTrue(kwery.client.getQueryState(key)!!.isLoading, "a genuine first load")

        kwery.settle(200.milliseconds)
        assertEquals("data", kwery.client.getQueryData(key))
        assertEquals(1, kwery.requestCount)

        // 2. Second observer: served from cache immediately, and because
        //    staleTime is 0 it also triggers a background refetch. Both
        //    observers share one entry, so both see the same status.
        val second = backgroundScope.launch {
            kwery.query(key, opts) { delay(100); "data" }.collect { }
        }
        kwery.settle(20.milliseconds)
        val duringRefresh = kwery.client.getQueryState(key)!!
        assertEquals("data", duringRefresh.data, "cached data served at once")
        assertTrue(duringRefresh.isRefreshing, "and refreshed underneath")
        kwery.settle(200.milliseconds)
        assertEquals(2, kwery.requestCount)

        // 3. All observers gone: the entry becomes inactive and the gc timer
        //    starts. It is NOT evicted yet.
        first.cancel()
        second.cancel()
        kwery.settle(2.seconds) // past the grace window
        assertTrue(kwery.client.cacheSnapshot().isNotEmpty(), "still cached, just inactive")

        // 4. A new observer before the timer fires: cached data immediately,
        //    background refetch, and the gc timer is cancelled.
        val third = backgroundScope.launch {
            kwery.query(key, opts) { delay(100); "data" }.collect { }
        }
        kwery.settle(20.milliseconds)
        assertEquals("data", kwery.client.getQueryData(key), "served from cache, not refetched")
        kwery.settle(60.seconds)
        assertTrue(
            kwery.client.cacheSnapshot().isNotEmpty(),
            "the gc timer must have been cancelled by the reattach",
        )

        // 5. Last observer leaves and the timer runs out: the entry is deleted.
        third.cancel()
        kwery.settle(2.seconds + 31.seconds)
        assertTrue(kwery.client.cacheSnapshot().isEmpty(), "evicted after grace + gcTime")
    }

    @Test
    fun `the gc timer restarts on each detach rather than accumulating`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val key = LifecycleKey()
        val opts = options(gcTime = 20.seconds)

        repeat(3) {
            val job = backgroundScope.launch {
                kwery.query(key, opts) { delay(50); "data" }.collect { }
            }
            kwery.settle(200.milliseconds)
            job.cancel()
            kwery.settle(5.seconds) // past grace, part-way through gcTime
            assertTrue(
                kwery.client.cacheSnapshot().isNotEmpty(),
                "a fresh detach restarts the clock; it must not have expired yet",
            )
        }

        kwery.settle(30.seconds)
        assertTrue(kwery.client.cacheSnapshot().isEmpty(), "and it does expire eventually")
    }

    @Test
    fun `gcTime INFINITE never evicts and does not overflow`() = runTest {
        // TanStack caps gcTime at about 24 days because setTimeout overflows a
        // 32-bit delay. delay() takes a Long, so "never evict" is expressible
        // rather than approximated.
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val key = LifecycleKey()

        val job = backgroundScope.launch {
            kwery.query(key, options(gcTime = Duration.INFINITE)) { delay(50); "data" }
                .collect { }
        }
        kwery.settle(200.milliseconds)
        job.cancel()

        kwery.settle(365.days)
        assertTrue(
            kwery.client.cacheSnapshot().isNotEmpty(),
            "an infinite gcTime must survive a year of virtual time",
        )
    }

    @Test
    fun `an entry observed by a second screen is not evicted when the first leaves`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val key = LifecycleKey()
        val opts = options(gcTime = 5.seconds)

        val first = backgroundScope.launch {
            kwery.query(key, opts) { delay(50); "data" }.collect { }
        }
        val second = backgroundScope.launch {
            kwery.query(key, opts) { delay(50); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)

        first.cancel()
        kwery.settle(30.seconds)

        assertTrue(
            kwery.client.cacheSnapshot().isNotEmpty(),
            "one observer leaving must not evict data another is still watching",
        )
        second.cancel()
    }

    // ---- select (feature 05) ---------------------------------------------

    @Test
    fun `select emits only when the projection changes`() = runTest {
        // The Compose-relevant case: a screen watching a count should not
        // recompose when an unrelated field of the list changes.
        val kwery = TestQueryClient(this)
        val key = LifecycleKey("select")
        val emissions = mutableListOf<Int>()
        var payload = "aaa"

        val job = backgroundScope.launch {
            kwery.client.query(
                key,
                options(stale = StaleTime.of(5.minutes)),
                select = { it?.length ?: 0 },
            ) { delay(50); payload }.collect { emissions += it }
        }
        kwery.settle(200.milliseconds)
        assertEquals(listOf(0, 3), emissions)

        // Same length, different content: the projection is unchanged.
        payload = "bbb"
        kwery.client.invalidateQueries(key)
        kwery.settle(300.milliseconds)
        assertEquals(listOf(0, 3), emissions, "an unchanged projection must not re-emit")

        payload = "cccc"
        kwery.client.invalidateQueries(key)
        kwery.settle(300.milliseconds)
        assertEquals(listOf(0, 3, 4), emissions, "a changed projection does")
        job.cancel()
    }

    // ---- Leak check (feature 05) -----------------------------------------

    @Test
    fun `many observers across many keys leave nothing behind`() = runTest {
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds, maxEntries = 500)
        val opts = options(gcTime = 5.seconds)

        val jobs = (1..100).flatMap { keyIndex ->
            (1..5).map {
                backgroundScope.launch {
                    kwery.query(LifecycleKey("key$keyIndex"), opts) { delay(50); "data" }
                        .collect { }
                }
            }
        }
        kwery.settle(500.milliseconds)
        assertEquals(100, kwery.client.cacheSnapshot().size, "one entry per key, not per observer")
        assertEquals(100, kwery.requestCount, "five observers per key share one request")

        jobs.forEach { it.cancel() }
        kwery.settle(30.seconds)

        assertTrue(
            kwery.client.cacheSnapshot().isEmpty(),
            "every entry must be evicted once its observers are gone",
        )
    }

    @Test
    fun `a disabled query neither fetches nor is reported stale`() = runTest {
        val kwery = TestQueryClient(this)
        val key = LifecycleKey("disabled")
        val disabled = QueryOptions(enabled = false, retry = RetryPolicy.Never)

        val job = backgroundScope.launch {
            kwery.query(key, disabled) { delay(50); "data" }.collect { }
        }
        kwery.settle(500.milliseconds)

        assertEquals(0, kwery.requestCount)
        val snapshot = kwery.client.cacheSnapshot().single()
        assertFalse(snapshot.isStale, "a disabled query has opted out of staleness")
        assertNotNull(kwery.client.getQueryState(key))
        assertEquals(QueryStatus.Pending, kwery.client.getQueryState(key)!!.status)
        job.cancel()
    }
}
