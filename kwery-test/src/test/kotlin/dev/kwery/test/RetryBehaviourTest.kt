package dev.kwery.test

import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryStatus
import dev.kwery.RetryDelay
import dev.kwery.RetryPolicy
import dev.kwery.exceptWhen
import dev.kwery.StaleTime
import kotlinx.coroutines.CancellationException
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

private object FlakyKey : QueryKey<String> {
    override val parts get() = listOf("flaky")
}

/** Features 02 and 06 — how a failing query function behaves. */
class RetryBehaviourTest {

    private fun retrying(
        policy: RetryPolicy = RetryPolicy.Default,
    ) = QueryOptions(
        staleTime = StaleTime.of(5.minutes),
        retry = policy,
        retryDelay = RetryDelay.constant(10.milliseconds),
    )

    @Test
    fun `the default policy retries three times, then reports Error`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(FlakyKey, retrying()) {
                attempts++
                delay(10)
                throw IllegalStateException("always broken")
            }.collect { }
        }
        kwery.settle(10.seconds)

        assertEquals(4, attempts, "the initial attempt plus three retries")
        assertEquals(QueryStatus.Error, kwery.client.getQueryState(FlakyKey)!!.status)
        job.cancel()
    }

    @Test
    fun `the thrown exception reaches QueryState error unchanged`() = runTest {
        val kwery = TestQueryClient(this)
        val boom = IllegalStateException("the real failure")

        val job = backgroundScope.launch {
            kwery.query(FlakyKey, retrying(RetryPolicy.Never)) { delay(10); throw boom }
                .collect { }
        }
        kwery.settle(1.seconds)

        assertSame(
            boom,
            kwery.client.getQueryState(FlakyKey)!!.error,
            "callers must get their own exception, not a copy",
        )
        job.cancel()
    }

    @Test
    fun `failureReason is populated during retries while error stays null`() = runTest {
        // Lets a UI say "retrying — last error was X" without entering an error
        // state the user would read as final.
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(
                FlakyKey,
                QueryOptions(
                    staleTime = StaleTime.of(5.minutes),
                    retry = RetryPolicy.Times(3),
                    retryDelay = RetryDelay.constant(1.seconds),
                ),
            ) {
                attempts++
                delay(10)
                throw IllegalStateException("attempt $attempts")
            }.collect { }
        }
        kwery.settle(500.milliseconds) // one failure in, still retrying

        val midRetry = kwery.client.getQueryState(FlakyKey)!!
        assertTrue(midRetry.failureCount > 0, "failures are counted as they happen")
        assertNotNull(midRetry.failureReason, "and the reason is visible")
        assertNull(midRetry.error, "but error stays null until the last attempt fails")
        assertEquals(QueryStatus.Pending, midRetry.status)

        kwery.settle(10.seconds)
        assertNotNull(kwery.client.getQueryState(FlakyKey)!!.error, "final failure promotes it")
        job.cancel()
    }

    @Test
    fun `a CancellationException is never retried and never counts as a failure`() = runTest {
        // Under RetryPolicy.Forever, treating cancellation as a failure would
        // retry for ever — every screen exit becoming a retry storm.
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(
                FlakyKey,
                QueryOptions(
                    staleTime = StaleTime.of(5.minutes),
                    retry = RetryPolicy.Forever,
                    retryDelay = RetryDelay.constant(10.milliseconds),
                ),
            ) {
                attempts++
                delay(10)
                throw CancellationException("navigated away")
            }.collect { }
        }
        kwery.settle(5.seconds)

        assertEquals(1, attempts, "cancellation must not be retried, even Forever")
        val state = kwery.client.getQueryState(FlakyKey)!!
        assertNull(state.error, "cancellation is not a failure")
        assertEquals(0, state.failureCount)
        job.cancel()
    }

    @Test
    fun `a non-retryable error skips retries entirely`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        val policy = RetryPolicy.Times(5).exceptWhen { it is IllegalArgumentException }
        val job = backgroundScope.launch {
            kwery.query(FlakyKey, retrying(policy)) {
                attempts++
                delay(10)
                throw IllegalArgumentException("400, and it will stay 400")
            }.collect { }
        }
        kwery.settle(5.seconds)

        assertEquals(1, attempts, "retrying a 4xx just fails more slowly")
        job.cancel()
    }

    @Test
    fun `a query that recovers mid-retry succeeds`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(FlakyKey, retrying()) {
                attempts++
                delay(10)
                if (attempts < 3) throw IllegalStateException("flaky") else "recovered"
            }.collect { }
        }
        kwery.settle(5.seconds)

        val state = kwery.client.getQueryState(FlakyKey)!!
        assertEquals(QueryStatus.Success, state.status)
        assertEquals("recovered", state.data)
        assertEquals(0, state.failureCount, "a successful attempt clears the failure count")
        assertNull(state.failureReason)
        job.cancel()
    }
}
