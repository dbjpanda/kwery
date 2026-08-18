package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class EventKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("event", id)
}

/**
 * The inspection surface devtools will be built on.
 *
 * The reason attached to each fetch is the part that has to exist now. "This
 * refetched because the app foregrounded and its staleTime had elapsed" cannot
 * be reconstructed later from state, so if it is not recorded at the transition
 * it is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueryEventTest {

    private fun TestQueryClient.record(into: MutableList<QueryEvent>, scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch { client.events.collect { into += it } }
    }

    @Test
    fun `a full lifecycle emits its transitions in order, with reasons`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<QueryEvent>()
        kwery.record(events, backgroundScope)
        kwery.settle()

        val key = EventKey("1")
        val options = QueryOptions(staleTime = StaleTime.of(1.minutes), gcTime = 1.minutes)

        // Mount
        val job = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        kwery.settle()

        // Invalidate, which refetches because something is watching
        kwery.client.invalidateQueries(key)
        kwery.settle()

        // Leave, then let the grace window and gcTime run out
        job.cancel()
        kwery.settle(10.seconds)
        kwery.settle(2.minutes)

        val kinds = events.map { it::class.simpleName }
        assertTrue("ObserverAttached" in kinds, "saw $kinds")
        assertTrue("FetchStarted" in kinds)
        assertTrue("FetchSucceeded" in kinds)
        assertTrue("Invalidated" in kinds)
        assertTrue("ObserverDetached" in kinds)
        assertTrue("Evicted" in kinds, "the whole lifecycle should be visible, saw $kinds")

        val reasons = events.filterIsInstance<QueryEvent.FetchStarted>().map { it.reason }
        assertEquals(
            listOf(FetchReason.Mount, FetchReason.Invalidated),
            reasons,
            "each fetch says why it happened",
        )
        assertEquals(
            EvictReason.GarbageCollected,
            events.filterIsInstance<QueryEvent.Evicted>().single().reason,
        )
    }

    @Test
    fun `a focus refetch is distinguishable from a mount`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<QueryEvent>()
        kwery.record(events, backgroundScope)
        kwery.settle()

        val job = backgroundScope.launch { kwery.query(EventKey("1")) { "v" }.collect { } }
        kwery.settle()

        kwery.setFocused(false)
        kwery.settle(1.minutes)
        kwery.setFocused(true)
        kwery.settle()

        assertEquals(
            listOf(FetchReason.Mount, FetchReason.FocusRegained),
            events.filterIsInstance<QueryEvent.FetchStarted>().map { it.reason },
            "otherwise 'why did this refetch' has no answer",
        )
        job.cancel()
    }

    @Test
    fun `a poll is distinguishable from everything else`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<QueryEvent>()
        kwery.record(events, backgroundScope)
        kwery.settle()

        val job = backgroundScope.launch {
            kwery.query(
                EventKey("1"),
                QueryOptions(staleTime = StaleTime.of(1.minutes), refetchInterval = { 10.seconds }),
            ) { "v" }.collect { }
        }
        kwery.settle()
        kwery.settle(25.seconds)

        val reasons = events.filterIsInstance<QueryEvent.FetchStarted>().map { it.reason }
        assertEquals(FetchReason.Mount, reasons.first())
        assertTrue(reasons.drop(1).all { it == FetchReason.Interval }, "saw $reasons")
        assertTrue(reasons.count { it == FetchReason.Interval } >= 2, "saw $reasons")
        job.cancel()
    }

    @Test
    fun `a prefetch is reported as a prefetch, not a mount`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<QueryEvent>()
        kwery.record(events, backgroundScope)
        kwery.settle()

        kwery.client.prefetchQuery(EventKey("1"), QueryOptions(staleTime = StaleTime.of(1.minutes))) { "v" }
        kwery.settle()

        assertEquals(
            listOf(FetchReason.Prefetch),
            events.filterIsInstance<QueryEvent.FetchStarted>().map { it.reason },
        )
    }

    @Test
    fun `a failure reports the error and how many attempts it took`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<QueryEvent>()
        kwery.record(events, backgroundScope)
        kwery.settle()

        val job = backgroundScope.launch {
            kwery.query(
                EventKey("1"),
                QueryOptions(retry = RetryPolicy.Times(2), retryDelay = RetryDelay.constant(1.seconds)),
            ) { error("boom") }.collect { }
        }
        kwery.settle(10.seconds)

        val failure = events.filterIsInstance<QueryEvent.FetchFailed>().single()
        assertEquals("boom", failure.error.message)
        assertEquals(3, failure.attempts, "a retry storm should be visible, not inferred")
        job.cancel()
    }

    @Test
    fun `offline pauses and resumes are visible`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<QueryEvent>()
        kwery.record(events, backgroundScope)
        kwery.settle()
        kwery.setOnline(false)

        val job = backgroundScope.launch { kwery.query(EventKey("1")) { "v" }.collect { } }
        kwery.settle()
        assertTrue(events.any { it is QueryEvent.Paused }, "paused should be observable")

        kwery.setOnline(true)
        kwery.settle()
        assertTrue(events.any { it is QueryEvent.Resumed })
        job.cancel()
    }

    @Test
    fun `an invalidation with nobody watching says it did not refetch`() = runTest {
        val kwery = TestQueryClient(this)
        val events = mutableListOf<QueryEvent>()
        kwery.record(events, backgroundScope)
        kwery.settle()

        kwery.client.setQueryData(EventKey("1"), "seeded")
        kwery.client.invalidateQueries(EventKey("1"))
        kwery.settle()

        val invalidated = events.filterIsInstance<QueryEvent.Invalidated>().single()
        assertEquals(false, invalidated.refetching, "stale, but nothing asked for new data")
        assertTrue(events.none { it is QueryEvent.FetchStarted })
    }

    @Test
    fun `a slow collector cannot stall the cache`() = runTest {
        val kwery = TestQueryClient(this)
        // Nobody collects events at all. The cache must not care: diagnostics
        // are not worth backpressure on real work.
        var calls = 0
        val job = backgroundScope.launch {
            kwery.query(EventKey("1")) { calls++; "v" }.collect { }
        }
        kwery.settle()
        repeat(400) { kwery.client.setQueryData(EventKey("1"), "v$it") }
        kwery.settle()

        assertEquals(1, calls)
        assertEquals("v399", kwery.client.getQueryData(EventKey("1")))
        job.cancel()
    }
}
