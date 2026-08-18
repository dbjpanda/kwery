package dev.kwery.test

import dev.kwery.FetchStatus
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryStatus
import dev.kwery.QueryState
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import dev.kwery.aggregate
import dev.kwery.ensureQueryData
import dev.kwery.fetchQuery
import dev.kwery.prefetchQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class DetailPageKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("detail", id)
}

/** Features 20 (prefetching) and 19 (aggregating several queries). */
class PrefetchTest {

    private val fresh = QueryOptions(staleTime = StaleTime.of(5.minutes), retry = RetryPolicy.Never)

    @Test
    fun `prefetching warms the cache so the screen renders with no request`() = runTest {
        val kwery = TestQueryClient(this)
        val key = DetailPageKey("1")

        kwery.client.prefetchQuery(key, fresh) { delay(100); "warmed" }
        kwery.settle(300.milliseconds)
        assertEquals("warmed", kwery.client.getQueryData(key))

        val requestsAfterPrefetch = kwery.requestCount
        val job = backgroundScope.launch {
            kwery.query(key, fresh) { delay(100); "from the screen" }.collect { }
        }
        kwery.settle(300.milliseconds)

        assertEquals(
            requestsAfterPrefetch,
            kwery.requestCount,
            "the screen must find the data already there",
        )
        assertEquals("warmed", kwery.client.getQueryData(key))
        job.cancel()
    }

    @Test
    fun `prefetching fresh data issues no request`() = runTest {
        // What makes it safe to call on every scroll tick.
        val kwery = TestQueryClient(this)
        val key = DetailPageKey("1")

        kwery.client.prefetchQuery(key, fresh) { delay(50); "data" }
        kwery.settle(200.milliseconds)
        val after = kwery.requestCount

        repeat(5) { kwery.client.prefetchQuery(key, fresh) { delay(50); "data" } }
        kwery.settle(200.milliseconds)

        assertEquals(after, kwery.requestCount, "already fresh; nothing to do")
    }

    @Test
    fun `a failing prefetch never throws`() = runTest {
        // Nothing is waiting on a speculative fetch, so a failure must not
        // escape into a scroll handler.
        val kwery = TestQueryClient(this)
        val key = DetailPageKey("broken")

        kwery.client.prefetchQuery(key, fresh) { delay(50); throw IllegalStateException("no") }
        kwery.settle(300.milliseconds)

        // ...but it is still recorded, so a screen that later observes the key
        // finds out what happened.
        assertEquals(QueryStatus.Error, kwery.client.getQueryState(key)!!.status)
    }

    @Test
    fun `fetchQuery returns the data and propagates failures`() = runTest {
        val kwery = TestQueryClient(this)
        val boom = IllegalStateException("deep link is broken")

        var result: String? = null
        backgroundScope.launch {
            result = kwery.client.fetchQuery(DetailPageKey("ok"), fresh) { delay(50); "data" }
        }
        kwery.settle(200.milliseconds)
        assertEquals("data", result)

        var caught: Throwable? = null
        backgroundScope.launch {
            caught = assertFailsWith<IllegalStateException> {
                kwery.client.fetchQuery(DetailPageKey("bad"), fresh) { delay(50); throw boom }
            }
        }
        kwery.settle(200.milliseconds)
        assertSame(boom, caught, "unlike prefetch, this is awaited and must throw")
    }

    @Test
    fun `ensureQueryData reads through the cache`() = runTest {
        val kwery = TestQueryClient(this)
        val key = DetailPageKey("1")

        var first: String? = null
        backgroundScope.launch {
            first = kwery.client.ensureQueryData(key, fresh) { delay(50); "fetched" }
        }
        kwery.settle(200.milliseconds)
        assertEquals("fetched", first)
        val afterFirst = kwery.requestCount

        var second: String? = null
        backgroundScope.launch {
            second = kwery.client.ensureQueryData(key, fresh) { delay(50); "should not run" }
        }
        kwery.settle(200.milliseconds)

        assertEquals("fetched", second, "served from cache")
        assertEquals(afterFirst, kwery.requestCount, "and no second request")
    }

    @Test
    fun `a prefetched entry is inactive, so its gcTime starts immediately`() = runTest {
        // The footgun worth knowing: prefetching far ahead of use can be
        // collected before the user arrives.
        val kwery = TestQueryClient(this, gracePeriod = 1.seconds)
        val key = DetailPageKey("1")
        val shortLived = QueryOptions(
            staleTime = StaleTime.of(5.minutes),
            gcTime = 2.seconds,
            retry = RetryPolicy.Never,
        )

        kwery.client.prefetchQuery(key, shortLived) { delay(50); "data" }
        kwery.settle(200.milliseconds)
        assertTrue(kwery.client.cacheSnapshot().isNotEmpty())

        kwery.settle(10.seconds)
        assertTrue(
            kwery.client.cacheSnapshot().isEmpty(),
            "nothing observed it, so it was collected",
        )
    }

    // ---- Aggregating several queries (feature 19) ------------------------

    @Test
    fun `aggregate reports Success only when every query has data`() = runTest {
        val states = listOf(
            QueryState(data = "a", status = QueryStatus.Success),
            QueryState(data = "b", status = QueryStatus.Success),
        )
        assertEquals(QueryStatus.Success, states.aggregate().status)

        val partial = listOf(
            QueryState(data = "a", status = QueryStatus.Success),
            QueryState<String>(status = QueryStatus.Pending, fetchStatus = FetchStatus.Fetching),
        )
        assertEquals(QueryStatus.Pending, partial.aggregate().status)
        assertTrue(partial.aggregate().isLoading)
    }

    @Test
    fun `aggregate surfaces the first error but keeps partial data`() = runTest {
        val boom = IllegalStateException("one failed")
        val states = listOf(
            QueryState(data = "a", status = QueryStatus.Success),
            QueryState<String>(error = boom, status = QueryStatus.Error),
        )
        val combined = states.aggregate()

        assertEquals(QueryStatus.Error, combined.status)
        assertSame(boom, combined.error)
        assertEquals(listOf("a", null), combined.data, "render what you have")
    }

    @Test
    fun `a disabled query does not hold the screen pending for ever`() = runTest {
        // The trap: one query that will never resolve would otherwise make the
        // whole screen load indefinitely.
        val states = listOf(
            QueryState(data = "a", status = QueryStatus.Success),
            QueryState<String>(status = QueryStatus.Pending, fetchStatus = FetchStatus.Idle),
        )

        assertEquals(QueryStatus.Success, states.aggregate().status)
        assertFalse(states.aggregate().isLoading)
        assertEquals(
            QueryStatus.Pending,
            states.aggregate(skipDisabled = false).status,
            "and it can be opted out of",
        )
    }

    @Test
    fun `aggregate prefers fetching over paused`() = runTest {
        val states = listOf(
            QueryState<String>(status = QueryStatus.Pending, fetchStatus = FetchStatus.Paused),
            QueryState<String>(status = QueryStatus.Pending, fetchStatus = FetchStatus.Fetching),
        )
        assertEquals(
            FetchStatus.Fetching,
            states.aggregate().fetchStatus,
            "something IS happening, so say so",
        )
    }

    @Test
    fun `an empty list aggregates to Success rather than hanging`() = runTest {
        val combined = emptyList<QueryState<String>>().aggregate()
        assertEquals(QueryStatus.Success, combined.status)
        assertNull(combined.error)
    }
}
