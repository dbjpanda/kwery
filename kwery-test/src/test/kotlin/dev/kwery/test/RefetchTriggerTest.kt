package dev.kwery.test

import dev.kwery.FetchStatus
import dev.kwery.NetworkMode
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryStatus
import dev.kwery.RefetchOn
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class FeedKey(val id: String = "feed") : QueryKey<String> {
    override val parts get() = listOf("feed", id)
}

/** Features 07 (refetch triggers) and 13 (network mode). */
class RefetchTriggerTest {

    private fun options(
        stale: StaleTime = StaleTime.Zero,
        focus: RefetchOn = RefetchOn.IfStale,
        reconnect: RefetchOn = RefetchOn.IfStale,
        mode: NetworkMode = NetworkMode.Online,
    ) = QueryOptions(
        staleTime = stale,
        retry = RetryPolicy.Never,
        refetchOnFocus = focus,
        refetchOnReconnect = reconnect,
        networkMode = mode,
    )

    // ---- Focus -----------------------------------------------------------

    @Test
    fun `regaining focus refetches a stale active query`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(FeedKey(), options()) { delay(100); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)
        assertEquals(1, kwery.requestCount)

        // Gone long enough that the grace window has expired.
        kwery.setFocused(false)
        kwery.settle(30.seconds)
        kwery.setFocused(true)
        kwery.settle(200.milliseconds)

        assertEquals(2, kwery.requestCount)
        job.cancel()
    }

    @Test
    fun `regaining focus does not refetch a fresh query`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(FeedKey(), options(stale = StaleTime.of(5.minutes))) {
                delay(100); "data"
            }.collect { }
        }
        kwery.settle(200.milliseconds)

        kwery.setFocused(false)
        kwery.settle(10.seconds)
        kwery.setFocused(true)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount, "fresh data must not refetch on focus")
        job.cancel()
    }

    @Test
    fun `a brief app switch does not refetch`() = runTest {
        // The refetch-storm case: backgrounding for two seconds and returning
        // lands inside the grace window, so nothing refetches even though
        // staleTime is 0.
        val kwery = TestQueryClient(this, gracePeriod = 5.seconds)
        val job = backgroundScope.launch {
            kwery.query(FeedKey(), options()) { delay(100); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)

        kwery.setFocused(false)
        kwery.settle(2.seconds)
        kwery.setFocused(true)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount, "a brief app switch must not refetch")
        job.cancel()
    }

    @Test
    fun `refetchOnFocus Never disables the trigger`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(FeedKey(), options(focus = RefetchOn.Never)) { delay(100); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)

        kwery.setFocused(false)
        kwery.settle(30.seconds)
        kwery.setFocused(true)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount)
        job.cancel()
    }

    @Test
    fun `refetchOnFocus Always refetches even fresh data`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(
                FeedKey(),
                options(stale = StaleTime.of(5.minutes), focus = RefetchOn.Always),
            ) { delay(100); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)

        kwery.setFocused(false)
        kwery.settle(30.seconds)
        kwery.setFocused(true)
        kwery.settle(200.milliseconds)

        assertEquals(2, kwery.requestCount)
        job.cancel()
    }

    @Test
    fun `Static blocks even refetchOnFocus Always`() = runTest {
        // The distinction between Infinite and Static: Static refuses every
        // automatic refetch, including one asked for explicitly.
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(
                FeedKey(),
                options(stale = StaleTime.Static, focus = RefetchOn.Always),
            ) { delay(100); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)

        kwery.setFocused(false)
        kwery.settle(30.seconds)
        kwery.setFocused(true)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount)
        job.cancel()
    }

    @Test
    fun `an inactive query does not refetch on focus`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(FeedKey(), options()) { delay(100); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)
        job.cancel()
        kwery.settle(10.seconds) // past grace; now inactive but still cached

        kwery.setFocused(false)
        kwery.settle(1.seconds)
        kwery.setFocused(true)
        kwery.settle(200.milliseconds)

        assertEquals(1, kwery.requestCount, "only observed queries refetch on focus")
    }

    // ---- Reconnect -------------------------------------------------------

    @Test
    fun `reconnecting refetches a stale active query`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(FeedKey(), options()) { delay(100); "data" }.collect { }
        }
        kwery.settle(200.milliseconds)

        kwery.setOnline(false)
        kwery.settle(30.seconds)
        kwery.setOnline(true)
        kwery.settle(200.milliseconds)

        assertEquals(2, kwery.requestCount)
        job.cancel()
    }

    // ---- Network mode (feature 13) ---------------------------------------

    @Test
    fun `an offline query is pending and paused, not errored`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        val key = FeedKey()
        val job = backgroundScope.launch {
            kwery.query(key, options()) { delay(100); "data" }.collect { }
        }
        kwery.settle(500.milliseconds)

        val state = kwery.client.getQueryState(key)
        assertNotNull(state)
        assertEquals(QueryStatus.Pending, state.status)
        assertEquals(FetchStatus.Paused, state.fetchStatus)
        // The combination one enum cannot express.
        assertEquals(false, state.isLoading, "paused is not loading")
        job.cancel()
    }

    @Test
    fun `a paused query resumes when connectivity returns`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        val key = FeedKey()
        val job = backgroundScope.launch {
            kwery.query(key, options()) { delay(100); "data" }.collect { }
        }
        kwery.settle(500.milliseconds)
        assertEquals(FetchStatus.Paused, kwery.client.getQueryState(key)?.fetchStatus)

        kwery.setOnline(true)
        kwery.settle(500.milliseconds)

        val state = kwery.client.getQueryState(key)
        assertNotNull(state)
        assertEquals(QueryStatus.Success, state.status)
        assertEquals("data", state.data)
        job.cancel()
    }

    @Test
    fun `NetworkMode Always ignores connectivity`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        val key = FeedKey()
        val job = backgroundScope.launch {
            kwery.query(key, options(mode = NetworkMode.Always)) { delay(100); "data" }.collect { }
        }
        kwery.settle(500.milliseconds)

        val state = kwery.client.getQueryState(key)
        assertNotNull(state)
        assertEquals(QueryStatus.Success, state.status, "Always must not pause when offline")
        assertEquals(1, kwery.requestCount)
        job.cancel()
    }
}
