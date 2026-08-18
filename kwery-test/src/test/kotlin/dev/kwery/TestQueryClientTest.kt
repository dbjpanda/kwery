package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class ThingKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("thing", id)
}

/**
 * `kwery-test` tested through the same discipline it exists to enable.
 *
 * A test helper that is subtly wrong is worse than none: every suite built on
 * it inherits the flaw and reports green. So the controls are asserted here
 * rather than trusted because Kwery's own suite happens to pass.
 */
class TestQueryClientTest {

    @Test
    fun `virtual time drives staleness with no real delay`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ThingKey("1")
        val options = QueryOptions(staleTime = StaleTime.of(5.minutes))

        val job = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        kwery.settle()
        assertEquals(1, kwery.requestCount)

        // Four minutes: still fresh. The whole test runs in microseconds of
        // wall-clock time; a suite that took four real minutes would be skipped.
        kwery.settle(4.minutes)
        kwery.client.invalidateQueries(QueryFilters(exactKey = key, stale = true))
        kwery.settle()
        assertEquals(1, kwery.requestCount, "a fresh query is not stale")

        kwery.settle(2.minutes)
        kwery.client.invalidateQueries(QueryFilters(exactKey = key, stale = true))
        kwery.settle()
        assertEquals(2, kwery.requestCount, "past staleTime it is")

        job.cancel()
    }

    @Test
    fun `virtual time drives gc, so eviction is assertable`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ThingKey("1")

        val job = backgroundScope.launch { kwery.query(key) { "v" }.collect { } }
        kwery.settle()
        job.cancel()

        kwery.settle(1.seconds)
        assertTrue(kwery.client.cacheSnapshot().any { it.key == key }, "grace window holds it")

        kwery.settle(6.minutes)
        assertTrue(
            kwery.client.cacheSnapshot().none { it.key == key },
            "past gcTime it is evicted, with no real waiting",
        )
    }

    @Test
    fun `setOnline false produces Paused rather than an error`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.setOnline(false)

        var last: QueryState<String>? = null
        val job = backgroundScope.launch {
            kwery.query(ThingKey("1")) { "v" }.collect { last = it }
        }
        kwery.settle()

        assertEquals(FetchStatus.Paused, last?.fetchStatus, "offline pauses, it does not fail")
        assertEquals(0, kwery.requestCount, "and issues no request")

        kwery.setOnline(true)
        kwery.settle()
        assertEquals(1, kwery.requestCount, "reconnecting releases it")

        job.cancel()
    }

    @Test
    fun `setFocused controls focus refetching`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch { kwery.query(ThingKey("1")) { "v" }.collect { } }
        kwery.settle()
        assertEquals(1, kwery.requestCount)

        // Past the grace window, so the return counts as a real one rather than
        // a brief app switch.
        kwery.setFocused(false)
        kwery.settle(30.seconds)
        kwery.setFocused(true)
        kwery.settle()

        assertEquals(2, kwery.requestCount, "returning to a stale query refetches")
        job.cancel()
    }

    @Test
    fun `recordedRequests counts a deduplicated request once`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ThingKey("1")
        val gate = CompletableDeferred<String>()

        // Ten screens open the same key at once. That is one request, and the
        // recorder must say one — a recorder that counted collectors would make
        // every dedup test in every downstream suite pass vacuously.
        val jobs = List(10) {
            backgroundScope.launch { kwery.query(key) { gate.await() }.collect { } }
        }
        kwery.settle()

        assertEquals(1, kwery.requestCount)
        assertEquals(listOf(key), kwery.recordedRequests)

        gate.complete("v")
        kwery.settle()
        assertEquals(1, kwery.requestCount, "and still one after it resolves")

        jobs.forEach { it.cancel() }
    }

    @Test
    fun `recordedRequests counts retries individually`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        val job = backgroundScope.launch {
            kwery.query(
                ThingKey("1"),
                QueryOptions(retry = RetryPolicy.Times(2), retryDelay = RetryDelay.constant(1.seconds)),
            ) {
                attempts++
                error("boom")
            }.collect { }
        }
        kwery.settle(10.seconds)

        // Deduplication collapses concurrent *observers*; it must not collapse
        // sequential attempts, or a retry-storm bug would be invisible.
        assertEquals(3, attempts, "initial attempt plus two retries")
        assertEquals(3, kwery.requestCount, "the recorder sees each one")

        job.cancel()
    }

    @Test
    fun `awaitIdle returns only once every query has settled`() = runTest {
        val kwery = TestQueryClient(this)
        val slow = CompletableDeferred<String>()

        val jobs = listOf(
            backgroundScope.launch { kwery.query(ThingKey("1")) { "fast" }.collect { } },
            backgroundScope.launch {
                kwery.query(ThingKey("2")) {
                    // Resolves only after virtual time moves, which is exactly
                    // what awaitIdle has to drive rather than wait for.
                    kotlinx.coroutines.delay(3.seconds)
                    "slow"
                }.collect { }
            },
        )

        kwery.awaitIdle()

        assertEquals(0, kwery.client.isFetching.value, "nothing may still be fetching")
        assertEquals(2, kwery.requestCount)
        assertTrue(
            kwery.currentTimeMillis >= 3.seconds.inWholeMilliseconds,
            "and it advanced time to get there, was ${kwery.currentTimeMillis}ms",
        )

        slow.complete("unused")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `awaitIdle covers mutations too`() = runTest {
        val kwery = TestQueryClient(this)
        val m = kwery.client.mutation(
            MutationOptions<Unit, String, Unit>(
                mutationFn = {
                    kotlinx.coroutines.delay(2.seconds)
                    "done"
                },
            ),
        )
        m.mutate(Unit)

        kwery.awaitIdle()

        assertEquals(0, kwery.client.isMutating.value)
        assertEquals(MutationStatus.Success, m.state.value.status)
    }

    @Test
    fun `awaitIdle fails loudly on a fetcher that never completes`() = runTest {
        val kwery = TestQueryClient(this)
        val neverCompletes = CompletableDeferred<String>()
        val job = backgroundScope.launch {
            kwery.query(ThingKey("1")) { neverCompletes.await() }.collect { }
        }
        kwery.settle()

        // Forgetting to complete a gate is the commonest way to write a test
        // that hangs. Hanging until the framework's timeout gives no clue why;
        // this names the cause.
        val failure = assertFailsWith<IllegalStateException> { kwery.awaitIdle(limit = 5.seconds) }
        assertTrue(
            failure.message!!.contains("never completes"),
            "the message should name the likely cause, was: ${failure.message}",
        )

        job.cancel()
    }

    @Test
    fun `awaitIdle returns between polling ticks rather than blocking`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(
                ThingKey("1"),
                QueryOptions(refetchInterval = { 1.seconds }),
            ) { "v" }.collect { }
        }
        kwery.settle()

        // A polling query is idle between ticks, so awaitIdle has something to
        // return to. Treating polling as "never idle" would make it unusable
        // for exactly the tests that need it most.
        kwery.awaitIdle(limit = 5.seconds)
        assertEquals(0, kwery.client.isFetching.value)

        job.cancel()
    }

    @Test
    fun `each TestQueryClient has its own cache`() = runTest {
        val a = TestQueryClient(this)
        val b = TestQueryClient(this)
        val key = ThingKey("1")

        val job = backgroundScope.launch { a.query(key) { "from-a" }.collect { } }
        a.settle()

        assertEquals(1, a.requestCount)
        assertEquals(0, b.requestCount, "a second client must not see the first's work")
        assertEquals(null, b.client.getQueryData(key), "nor its data")

        job.cancel()
    }

    @Test
    fun `retries are off by default, so an error test does not wait through backoffs`() = runTest {
        val kwery = TestQueryClient(this)
        var attempts = 0

        var last: QueryState<String>? = null
        val job = backgroundScope.launch {
            kwery.query(ThingKey("1")) {
                attempts++
                error("boom")
            }.collect { last = it }
        }
        kwery.settle()

        assertEquals(1, attempts, "one attempt — the default is RetryPolicy.Never")
        assertEquals(QueryStatus.Error, last?.status, "and the error state is reachable immediately")

        job.cancel()
    }
}
