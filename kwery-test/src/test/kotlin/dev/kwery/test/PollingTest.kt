package dev.kwery.test

import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryState
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private object StatusKey : QueryKey<String> {
    override val parts get() = listOf("status")
}

/** Feature 07 — polling. */
class PollingTest {

    private fun polling(
        interval: ((QueryState<*>) -> Duration?)? = { 1.seconds },
        inBackground: Boolean = false,
    ) = QueryOptions(
        // A non-zero staleTime proves polling is independent of staleness.
        staleTime = StaleTime.of(1.minutes),
        retry = RetryPolicy.Never,
        refetchInterval = interval,
        refetchIntervalInBackground = inBackground,
    )

    @Test
    fun `a polling query refetches on its interval`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(StatusKey, polling()) { delay(10); "data" }.collect { }
        }
        kwery.settle(100.milliseconds)
        assertEquals(1, kwery.requestCount, "the initial load")

        kwery.settle(3500.milliseconds)
        assertEquals(4, kwery.requestCount, "three more ticks at one second")
        job.cancel()
    }

    @Test
    fun `polling ignores staleTime`() = runTest {
        // Polling exists to detect server-side change the client cannot
        // predict, so freshness is beside the point.
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(
                StatusKey,
                polling().copy(staleTime = StaleTime.of(1.minutes)),
            ) { delay(10); "data" }.collect { }
        }
        kwery.settle(100.milliseconds)
        kwery.settle(2500.milliseconds)

        assertTrue(kwery.requestCount >= 3, "polled despite the data being fresh")
        job.cancel()
    }

    @Test
    fun `polling stops when the last observer leaves`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(StatusKey, polling()) { delay(10); "data" }.collect { }
        }
        kwery.settle(2500.milliseconds)
        val whileWatched = kwery.requestCount
        assertTrue(whileWatched > 1)

        job.cancel()
        kwery.settle(10.seconds)

        assertEquals(
            whileWatched,
            kwery.requestCount,
            "polling a screen nobody is watching is pure waste",
        )
    }

    @Test
    fun `the polling loop itself stops, not just the requests`() = runTest {
        // Request counts alone cannot see this: the loop's inner observer check
        // suppresses the fetch either way. What it cannot suppress is a
        // coroutine waking every interval for the rest of gcTime, which is a
        // battery cost with nothing to show for it. Counting interval
        // evaluations makes the loop itself observable.
        val kwery = TestQueryClient(this)
        var intervalEvaluations = 0

        val job = backgroundScope.launch {
            kwery.query(
                StatusKey,
                polling(interval = { intervalEvaluations++; 500.milliseconds }),
            ) { delay(10); "data" }.collect { }
        }
        kwery.settle(2.seconds)
        val whileWatched = intervalEvaluations
        assertTrue(whileWatched > 1, "the loop is running while observed")

        job.cancel()
        kwery.settle(30.seconds)

        assertEquals(
            whileWatched,
            intervalEvaluations,
            "the loop must be cancelled, not left spinning with its fetches suppressed",
        )
    }

    @Test
    fun `polling pauses in the background and resumes on return`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(StatusKey, polling()) { delay(10); "data" }.collect { }
        }
        kwery.settle(2500.milliseconds)
        val beforeBackground = kwery.requestCount

        kwery.setFocused(false)
        kwery.settle(5.seconds)
        assertEquals(beforeBackground, kwery.requestCount, "no polling while backgrounded")

        // Ticks are skipped, not the loop exited — returning resumes polling
        // without needing a reattach.
        kwery.setFocused(true)
        kwery.settle(2500.milliseconds)
        assertTrue(kwery.requestCount > beforeBackground, "and it resumes on return")
        job.cancel()
    }

    @Test
    fun `refetchIntervalInBackground keeps polling while backgrounded`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(StatusKey, polling(inBackground = true)) { delay(10); "data" }
                .collect { }
        }
        kwery.settle(1500.milliseconds)
        val before = kwery.requestCount

        kwery.setFocused(false)
        kwery.settle(3.seconds)

        assertTrue(kwery.requestCount > before, "opted in, so it keeps polling")
        job.cancel()
    }

    @Test
    fun `the interval is re-read each tick, so it can adapt`() = runTest {
        // The common shape: poll fast while a job is running, slowly once it
        // has finished.
        val kwery = TestQueryClient(this)
        var finished = false

        val job = backgroundScope.launch {
            kwery.query(
                StatusKey,
                polling(interval = { if (finished) 10.seconds else 500.milliseconds }),
            ) { delay(10); if (finished) "done" else "running" }.collect { }
        }
        kwery.settle(2200.milliseconds)
        val whileFast = kwery.requestCount
        assertTrue(whileFast >= 4, "polling fast: $whileFast requests")

        finished = true
        kwery.settle(3.seconds)

        assertTrue(
            kwery.requestCount - whileFast <= 1,
            "and slows down once the state changes, without restarting the query",
        )
        job.cancel()
    }

    @Test
    fun `returning null from the interval stops polling`() = runTest {
        val kwery = TestQueryClient(this)
        var keepPolling = true

        val job = backgroundScope.launch {
            kwery.query(
                StatusKey,
                polling(interval = { if (keepPolling) 500.milliseconds else null }),
            ) { delay(10); "data" }.collect { }
        }
        kwery.settle(1200.milliseconds)
        val before = kwery.requestCount

        keepPolling = false
        kwery.settle(10.seconds)

        assertEquals(before, kwery.requestCount, "a null interval ends the loop")
        job.cancel()
    }

    @Test
    fun `a query with no interval never polls`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(StatusKey, polling(interval = null)) { delay(10); "data" }.collect { }
        }
        kwery.settle(30.seconds)

        assertEquals(1, kwery.requestCount)
        job.cancel()
    }
}
