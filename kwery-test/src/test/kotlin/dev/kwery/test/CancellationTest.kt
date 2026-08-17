package dev.kwery.test

import dev.kwery.QueryFilters
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private object SlowKey : QueryKey<String> {
    override val parts get() = listOf("slow")
}

/** Feature 10 — cancellation. */
class CancellationTest {

    private val options = QueryOptions(
        staleTime = StaleTime.of(5.minutes),
        retry = RetryPolicy.Never,
    )

    @Test
    fun `a cancelled query does not enter Error`() = runTest {
        // Cancellation is not failure. Showing an error every time a user
        // navigates away mid-request would be wrong and very visible.
        val kwery = TestQueryClient(this)

        val job = backgroundScope.launch {
            kwery.query(SlowKey, options) { delay(5_000); "data" }.collect { }
        }
        kwery.settle(100.milliseconds)

        kwery.client.cancelQueries(QueryFilters(exactKey = SlowKey))
        kwery.settle(200.milliseconds)

        val state = kwery.client.getQueryState(SlowKey)!!
        assertNull(state.error, "a cancelled request produced no error")
        assertEquals(QueryStatus.Pending, state.status, "and the status is unchanged")
        assertTrue(!state.isFetching)
        job.cancel()
    }

    @Test
    fun `a query function that swallows CancellationException is still cancelled`() = runTest {
        // The Kotlin analogue of TanStack's "you must forward the AbortSignal"
        // footgun, and worse because it is silent: `catch (e: Exception)` also
        // catches CancellationException. The engine re-checks isActive after
        // the function returns, so a swallowed cancellation cannot produce a
        // bogus success.
        val kwery = TestQueryClient(this)

        val job = backgroundScope.launch {
            kwery.query(SlowKey, options) {
                @Suppress("TooGenericExceptionCaught", "SwallowedException")
                try {
                    delay(5_000)
                    "real data"
                } catch (swallowed: Exception) {
                    "a value invented after being cancelled"
                }
            }.collect { }
        }
        kwery.settle(100.milliseconds)

        kwery.client.cancelQueries(QueryFilters(exactKey = SlowKey))
        kwery.settle(500.milliseconds)

        val state = kwery.client.getQueryState(SlowKey)!!
        assertNull(
            state.data,
            "the invented value must not be cached as though it were a response",
        )
        assertEquals(QueryStatus.Pending, state.status)
        job.cancel()
    }

    @Test
    fun `cancelling one of two observers leaves the shared request running`() = runTest {
        val kwery = TestQueryClient(this)

        val first = backgroundScope.launch {
            kwery.query(SlowKey, options) { delay(1_000); "data" }.collect { }
        }
        val second = backgroundScope.launch {
            kwery.query(SlowKey, options) { delay(1_000); "data" }.collect { }
        }
        kwery.settle(100.milliseconds)
        assertEquals(1, kwery.requestCount, "one shared request")

        first.cancel()
        kwery.settle(2.seconds)

        assertEquals(
            "data",
            kwery.client.getQueryData(SlowKey),
            "the remaining observer must still get its data",
        )
        assertEquals(1, kwery.requestCount, "and nothing was restarted")
        second.cancel()
    }

    @Test
    fun `cancelling an already-settled query is a no-op`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(SlowKey, options) { delay(50); "data" }.collect { }
        }
        kwery.settle(300.milliseconds)
        val before = kwery.client.getQueryState(SlowKey)

        kwery.client.cancelQueries(QueryFilters.All)
        kwery.settle(100.milliseconds)

        assertEquals(before, kwery.client.getQueryState(SlowKey))
        job.cancel()
    }

    @Test
    fun `a refetch supersedes the in-flight fetch rather than racing it`() = runTest {
        val kwery = TestQueryClient(this)
        var served = "first"

        val job = backgroundScope.launch {
            kwery.query(SlowKey, options) { delay(500); served }.collect { }
        }
        kwery.settle(100.milliseconds) // first fetch in flight

        served = "second"
        kwery.client.refetchQueries(QueryFilters(exactKey = SlowKey))
        kwery.settle(2.seconds)

        assertEquals(
            "second",
            kwery.client.getQueryData(SlowKey),
            "the superseded fetch must not win the race and write stale data",
        )
        job.cancel()
    }

    @Test
    fun `abandoning a query mid-flight leaves no error behind for the next observer`() = runTest {
        val kwery = TestQueryClient(this)

        val first = backgroundScope.launch {
            kwery.query(SlowKey, options) { delay(5_000); "data" }.collect { }
        }
        kwery.settle(100.milliseconds)
        first.cancel()
        kwery.settle(10.seconds) // past the grace window; the request is cancelled

        // A later observer starts clean rather than inheriting a phantom error.
        val second = backgroundScope.launch {
            kwery.query(SlowKey, options) { delay(50); "data" }.collect { }
        }
        kwery.settle(300.milliseconds)

        val state = kwery.client.getQueryState(SlowKey)!!
        assertNull(state.error)
        assertEquals("data", state.data)
        second.cancel()
    }
}
