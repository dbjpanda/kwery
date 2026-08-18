package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class BoardKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("board", id)
}

/**
 * Two screens watching one key.
 *
 * Almost every other test here uses a single observer, and a whole class of
 * bug lives in the difference: work that should happen once per *entry* but
 * accidentally happens once per *observer*, and teardown that should wait for
 * the last observer but runs on the first one to leave.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultipleObserverTest {

    @Test
    fun `two observers of a polling query share one polling loop`() = runTest {
        val kwery = TestQueryClient(this)
        val key = BoardKey("1")
        // staleTime keeps mount-refetches out of the count. A second observer
        // mounting on a *stale* query legitimately refetches — that is
        // refetchOnMount doing its job, and it would mask what this test is
        // actually about, which is how many polling loops exist.
        val options = QueryOptions(
            staleTime = StaleTime.of(5.minutes),
            refetchInterval = { 10.seconds },
        )

        val first = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        kwery.settle()
        val second = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        kwery.settle()

        assertEquals(1, kwery.requestCount, "the initial fetch is shared")

        kwery.settle(30.seconds)

        // Three ticks. Per-observer polling loops would make it six — a second
        // screen silently doubling the request rate against the server.
        assertEquals(4, kwery.requestCount, "one loop, not one per observer")

        first.cancel()
        second.cancel()
    }

    @Test
    fun `a second observer does not start a second polling loop`() = runTest {
        val kwery = TestQueryClient(this)
        val key = BoardKey("1")
        var evaluations = 0
        val options = QueryOptions(
            staleTime = StaleTime.of(5.minutes),
            refetchInterval = { evaluations++; 10.seconds },
        )

        val first = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        kwery.settle()
        val second = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        kwery.settle(60.seconds)

        // Request counts CANNOT see this. Two polling loops tick at the same
        // instants, so their fetches are deduplicated into one request and the
        // count looks perfect while a whole extra coroutine wakes up for ever.
        // Counting interval evaluations is what makes the duplicate visible —
        // the same technique the polling-stop leak needed in feature 07.
        // One loop over 60s at 10s evaluates 13 times: twice per tick, because
        // the interval is deliberately re-read after the delay so that turning
        // polling off takes effect immediately rather than one tick late. Two
        // loops make it 26.
        assertTrue(
            evaluations <= 15,
            "one polling loop should evaluate about 13 times, saw $evaluations",
        )

        first.cancel()
        second.cancel()
    }

    @Test
    fun `a third and fourth observer still do not add polling loops`() = runTest {
        val kwery = TestQueryClient(this)
        val key = BoardKey("1")
        // staleTime keeps mount-refetches out of the count. A second observer
        // mounting on a *stale* query legitimately refetches — that is
        // refetchOnMount doing its job, and it would mask what this test is
        // actually about, which is how many polling loops exist.
        val options = QueryOptions(
            staleTime = StaleTime.of(5.minutes),
            refetchInterval = { 10.seconds },
        )

        val jobs = List(4) {
            backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
                .also { kwery.settle() }
        }
        val afterMount = kwery.requestCount
        kwery.settle(30.seconds)

        assertEquals(afterMount + 3, kwery.requestCount, "still three ticks with four observers")
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `one observer leaving does not stop polling for the other`() = runTest {
        val kwery = TestQueryClient(this)
        val key = BoardKey("1")
        // staleTime keeps mount-refetches out of the count. A second observer
        // mounting on a *stale* query legitimately refetches — that is
        // refetchOnMount doing its job, and it would mask what this test is
        // actually about, which is how many polling loops exist.
        val options = QueryOptions(
            staleTime = StaleTime.of(5.minutes),
            refetchInterval = { 10.seconds },
        )

        val first = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        val second = backgroundScope.launch { kwery.query(key, options) { "v" }.collect { } }
        kwery.settle()
        val afterMount = kwery.requestCount

        // One screen closes; the other is still on screen and still expects
        // fresh data.
        first.cancel()
        kwery.settle(30.seconds)

        assertEquals(
            afterMount + 3,
            kwery.requestCount,
            "polling belongs to the entry, not to whichever observer arrived first",
        )
        second.cancel()
    }

    @Test
    fun `one observer leaving does not schedule eviction`() = runTest {
        val kwery = TestQueryClient(this)
        val key = BoardKey("1")

        val first = backgroundScope.launch { kwery.query(key) { "v" }.collect { } }
        val second = backgroundScope.launch { kwery.query(key) { "v" }.collect { } }
        kwery.settle()

        first.cancel()
        // Well past grace + gcTime. The entry must survive, because something
        // is still watching it.
        kwery.settle(10.minutes)

        val entry = kwery.client.cacheSnapshot().firstOrNull { it.key == key }
        assertNotNull(entry, "an observed entry must never be evicted")
        assertEquals(1, entry.observerCount)
        assertTrue(entry.isActive)

        second.cancel()
    }

    @Test
    fun `the last observer leaving does evict`() = runTest {
        val kwery = TestQueryClient(this)
        val key = BoardKey("1")

        val first = backgroundScope.launch { kwery.query(key) { "v" }.collect { } }
        val second = backgroundScope.launch { kwery.query(key) { "v" }.collect { } }
        kwery.settle()

        first.cancel()
        kwery.settle(1.seconds)
        second.cancel()
        kwery.settle(10.minutes)

        assertNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == key },
            "once nobody is watching, the normal lifecycle applies",
        )
    }

    @Test
    fun `observerCount tracks arrivals and departures`() = runTest {
        val kwery = TestQueryClient(this)
        val key = BoardKey("1")

        fun count() = kwery.client.cacheSnapshotBlocking(key)?.observerCount

        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        repeat(3) {
            jobs += backgroundScope.launch { kwery.query(key) { "v" }.collect { } }
            kwery.settle()
        }
        assertEquals(3, count())

        jobs.removeAt(0).cancel()
        kwery.settle()
        assertEquals(2, count())

        jobs.forEach { it.cancel() }
        kwery.settle()
        assertEquals(0, count())
    }

    private fun QueryClient.cacheSnapshotBlocking(key: QueryKey<*>) =
        kotlinx.coroutines.runBlocking { cacheSnapshot() }.firstOrNull { it.key == key }
}
