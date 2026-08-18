package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private data class FeedKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("feed", id)
}

/**
 * `NetworkMode` — what a query does when there is no connection.
 *
 * The distinction that matters: **paused is not failed**. A phone in a lift has
 * not encountered an error, and showing one is both wrong and unhelpful. Every
 * test here asserts the status axes and the request count, because "did not
 * fire a doomed request" is the whole point.
 */
class NetworkModeTest {

    @Test
    fun `offline reports pending and paused, never error`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        var last: QueryState<String>? = null
        val job = backgroundScope.launch {
            kwery.query(FeedKey("1")) { "v" }.collect { last = it }
        }
        kwery.settle()

        val state = requireNotNull(last)
        assertEquals(QueryStatus.Pending, state.status)
        assertEquals(FetchStatus.Paused, state.fetchStatus)
        assertNull(state.error, "there is no error — nothing was attempted")
        assertEquals(0, kwery.requestCount)

        job.cancel()
    }

    @Test
    fun `cached data offline reports success and paused`() = runTest {
        val kwery = TestQueryClient(this)
        val key = FeedKey("1")

        val warm = backgroundScope.launch { kwery.query(key) { "cached" }.collect { } }
        kwery.settle()
        warm.cancel()

        // Past the grace window: a reattach inside it is a continuation and
        // deliberately skips the refetch-on-mount check, so the query would
        // never try to fetch and never pause. That is correct behaviour, and it
        // is not what this test is about.
        kwery.settle(10.seconds)
        kwery.setOnline(false)
        var last: QueryState<String>? = null
        val job = backgroundScope.launch { kwery.query(key) { "fresh" }.collect { last = it } }
        kwery.settle()

        val state = requireNotNull(last)
        assertEquals("cached", state.data, "the cached value stays on screen")
        assertEquals(QueryStatus.Success, state.status)
        assertEquals(FetchStatus.Paused, state.fetchStatus, "…while the refresh waits")
        assertEquals(1, kwery.requestCount, "and no doomed request went out")

        job.cancel()
    }

    @Test
    fun `going offline mid-retry pauses, and reconnect resumes at the next attempt`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0
        var last: QueryState<String>? = null

        val job = backgroundScope.launch {
            kwery.query(
                FeedKey("1"),
                QueryOptions(
                    retry = RetryPolicy.Times(5),
                    retryDelay = RetryDelay.constant(10.seconds),
                ),
            ) {
                attempts++
                error("boom")
            }.collect { last = it }
        }

        // First attempt fails, second is scheduled 10s out.
        kwery.settle()
        assertEquals(1, attempts)

        kwery.setOnline(false)
        kwery.settle(30.seconds)

        assertEquals(1, attempts, "no attempts are burned while offline")
        assertEquals(FetchStatus.Paused, last?.fetchStatus)
        assertEquals(
            1,
            last?.failureCount,
            "and the retry sequence is preserved, not restarted",
        )

        kwery.setOnline(true)
        kwery.settle()

        assertEquals(2, attempts, "reconnect resumes at attempt 2")
        job.cancel()
    }

    @Test
    fun `a paused query cancelled before reconnect does not resume`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        val job = backgroundScope.launch { kwery.query(FeedKey("1")) { "v" }.collect { } }
        kwery.settle()
        assertEquals(0, kwery.requestCount)

        // The user left the screen while offline. Coming back online must not
        // fire a request for something nobody is waiting for.
        job.cancel()
        kwery.settle(10.seconds)
        kwery.setOnline(true)
        kwery.settle(10.seconds)

        assertEquals(0, kwery.requestCount, "a cancelled pause must not resume")
    }

    @Test
    fun `Always never pauses and errors normally while offline`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        var last: QueryState<String>? = null
        val job = backgroundScope.launch {
            kwery.query(
                FeedKey("1"),
                QueryOptions(networkMode = NetworkMode.Always, retry = RetryPolicy.Never),
            ) {
                error("no network")
            }.collect { last = it }
        }
        kwery.settle()

        // Always is for query functions that do not need the network — reading
        // a local database, say. Pausing those on connectivity would be wrong,
        // so it attempts and reports honestly.
        assertEquals(1, kwery.requestCount, "it attempts regardless")
        assertEquals(QueryStatus.Error, last?.status)
        assertEquals(FetchStatus.Idle, last?.fetchStatus, "errored, not paused")

        job.cancel()
    }

    @Test
    fun `Always succeeds offline when the fetcher does not need the network`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        var last: QueryState<String>? = null
        val job = backgroundScope.launch {
            kwery.query(
                FeedKey("1"),
                QueryOptions(networkMode = NetworkMode.Always),
            ) { "from-disk" }.collect { last = it }
        }
        kwery.settle()

        assertEquals("from-disk", last?.data)
        assertEquals(QueryStatus.Success, last?.status)

        job.cancel()
    }

    @Test
    fun `OfflineFirst runs the fetcher once offline, then pauses retries`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)
        var attempts = 0
        var last: QueryState<String>? = null

        val job = backgroundScope.launch {
            kwery.query(
                FeedKey("1"),
                QueryOptions(
                    networkMode = NetworkMode.OfflineFirst,
                    retry = RetryPolicy.Times(3),
                    retryDelay = RetryDelay.constant(1.seconds),
                ),
            ) {
                attempts++
                error("boom")
            }.collect { last = it }
        }
        kwery.settle(30.seconds)

        // The first attempt may be served by an HTTP cache or an interceptor,
        // so it is worth making even with no connectivity. Retrying it is not:
        // if the cache did not have it, hammering the same dead network will
        // not help.
        assertEquals(1, attempts, "exactly one attempt while offline")
        assertEquals(FetchStatus.Paused, last?.fetchStatus, "then it pauses rather than retrying")

        kwery.setOnline(true)
        kwery.settle(5.seconds)
        assertTrue(attempts > 1, "and resumes retrying once there is a network")

        job.cancel()
    }
}

@kotlinx.coroutines.ExperimentalCoroutinesApi
class TestManagersTest {

    @Test
    fun `TestOnlineManager drives a client built by hand`() = runTest {
        val online = dev.kwery.test.TestOnlineManager(initiallyOnline = false)
        val client = QueryClient(
            scope = backgroundScope,
            config = QueryClientConfig(
                timeSource = TimeSource { testScheduler.currentTime },
                onlineManager = online,
                defaultQueryOptions = QueryOptions(retry = RetryPolicy.Never),
            ),
        )

        var attempts = 0
        var last: QueryState<String>? = null
        val job = backgroundScope.launch {
            client.query(FeedKey("1")) { attempts++; "v" }.collect { last = it }
        }
        testScheduler.runCurrent()

        assertEquals(FetchStatus.Paused, last?.fetchStatus)
        assertEquals(0, attempts)

        online.setOnline(true)
        testScheduler.runCurrent()
        assertEquals(1, attempts)

        job.cancel()
        client.close()
    }

    @Test
    fun `TestFocusManager drives focus refetching on a client built by hand`() = runTest {
        val focus = dev.kwery.test.TestFocusManager()
        val client = QueryClient(
            scope = backgroundScope,
            config = QueryClientConfig(
                timeSource = TimeSource { testScheduler.currentTime },
                focusManager = focus,
                defaultQueryOptions = QueryOptions(retry = RetryPolicy.Never),
            ),
        )

        var attempts = 0
        val job = backgroundScope.launch { client.query(FeedKey("1")) { attempts++; "v" }.collect { } }
        testScheduler.runCurrent()
        assertEquals(1, attempts)

        focus.setFocused(false)
        testScheduler.advanceTimeBy(30.seconds.inWholeMilliseconds)
        testScheduler.runCurrent()
        focus.setFocused(true)
        testScheduler.runCurrent()

        assertEquals(2, attempts, "returning to a stale query refetches")

        job.cancel()
        client.close()
    }

    @Test
    fun `under Always, reconnect still refetches unless you say otherwise`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(
                FeedKey("1"),
                QueryOptions(networkMode = NetworkMode.Always),
            ) { attempts++; "v" }.collect { }
        }
        kwery.settle()
        assertEquals(1, attempts)

        kwery.setOnline(false)
        kwery.settle(30.seconds)
        kwery.setOnline(true)
        kwery.settle()

        // TanStack derives refetchOnReconnect = false from networkMode: 'always'.
        // Kwery does not, and cannot cheaply: a data class default cannot
        // distinguish "unset" from "explicitly IfStale". Documented rather than
        // guessed at — see docs/offline.md.
        assertEquals(2, attempts, "the default still refetches; set RefetchOn.Never to opt out")

        job.cancel()
    }

    @Test
    fun `Always with refetchOnReconnect Never does not refetch on reconnect`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(
                FeedKey("1"),
                QueryOptions(
                    networkMode = NetworkMode.Always,
                    refetchOnReconnect = RefetchOn.Never,
                ),
            ) { attempts++; "v" }.collect { }
        }
        kwery.settle()

        kwery.setOnline(false)
        kwery.settle(30.seconds)
        kwery.setOnline(true)
        kwery.settle()

        assertEquals(1, attempts, "opting out works")
        job.cancel()
    }
}
