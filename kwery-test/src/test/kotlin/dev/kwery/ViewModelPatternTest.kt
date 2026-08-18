package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class TodoListKey(val filter: String) : QueryKey<List<String>> {
    override val parts get() = listOf("todos", mapOf("filter" to filter))
}

/**
 * The canonical Android ViewModel pattern, asserted rather than assumed.
 *
 * This feature has no TanStack counterpart. `stateIn(scope, WhileSubscribed(5s))`
 * and Kwery's own 5-second observer grace window are two independent timeouts
 * stacked on each other, and the whole question is whether that stack behaves
 * the way the docs claim on rotation, on backstack navigation, and when the key
 * changes.
 *
 * Every assertion here is a request count or an eviction, because those are the
 * only things a user actually pays for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelPatternTest {

    @Test
    fun `rotation under WhileSubscribed causes no refetch and no eviction`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoListKey("all")

        // The ViewModel outlives the Activity: it creates the shared flow once.
        val todos = kwery.query(key) { listOf("a") }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), QueryState())
        val firstCollector = backgroundScope.launch { todos.collect { } }
        kwery.settle()
        assertEquals(1, kwery.requestCount)

        // Rotation: the Activity's collector goes away and a new one arrives.
        repeat(5) {
            firstCollector.cancel()
            kwery.settle(200.milliseconds)
            val next = backgroundScope.launch { todos.collect { } }
            kwery.settle()
            next.cancel()
            kwery.settle(200.milliseconds)
        }
        val finalCollector = backgroundScope.launch { todos.collect { } }
        kwery.settle()

        assertEquals(
            1,
            kwery.requestCount,
            "five rotations must cost nothing — WhileSubscribed holds the upstream, " +
                "so the cache never even sees a detach",
        )
        assertNotNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == key },
            "and the entry must still be cached",
        )
        finalCollector.cancel()
    }

    @Test
    fun `leaving for the backstack evicts exactly once, after both timers`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoListKey("all")

        val todos = kwery.query(key) { listOf("a") }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), QueryState())
        val collector = backgroundScope.launch { todos.collect { } }
        kwery.settle()
        assertEquals(1, kwery.requestCount)

        collector.cancel()

        // Three timers in sequence: WhileSubscribed's 5s, Kwery's 5s grace,
        // then the 5-minute gcTime. Still present partway through.
        kwery.settle(20.seconds)
        assertNotNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == key },
            "20s in, only the sharing and grace timeouts have elapsed",
        )

        kwery.settle(6.minutes)
        assertNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == key },
            "past gcTime the entry is gone",
        )
        assertEquals(1, kwery.requestCount, "eviction is not a refetch")
    }

    @Test
    fun `a changing key cancels the old query and issues no duplicate requests`() = runTest {
        val kwery = TestQueryClient(this)
        val filter = MutableStateFlow("all")

        val todos = filter
            .flatMapLatest { f -> kwery.query(TodoListKey(f)) { listOf(f) } }
            .stateIn(backgroundScope, SharingStarted.WhileSubscribed(5_000), QueryState())
        val collector = backgroundScope.launch { todos.collect { } }
        kwery.settle()
        assertEquals(1, kwery.requestCount)

        filter.value = "done"
        kwery.settle()

        assertEquals(
            listOf(TodoListKey("all"), TodoListKey("done")),
            kwery.recordedRequests,
            "one request per key, in order, with nothing repeated",
        )

        // Switching back inside the grace window is a continuation: the first
        // entry never went inactive long enough to be a fresh mount.
        filter.value = "all"
        kwery.settle()
        assertEquals(2, kwery.requestCount, "returning to a warm key costs nothing")

        collector.cancel()
    }

    @Test
    fun `a never-completing collector holds the entry for ever, and the snapshot says so`() =
        runTest {
            val kwery = TestQueryClient(this)
            val key = TodoListKey("all")

            // SharingStarted.Lazily: once started, the upstream is never
            // stopped. Kwery therefore never sees a detach, gcTime never
            // starts, and the entry lives as long as the ViewModel.
            val todos = kwery.query(key) { listOf("a") }
                .stateIn(backgroundScope, SharingStarted.Lazily, QueryState())
            val collector = backgroundScope.launch { todos.collect { } }
            kwery.settle()
            collector.cancel()
            kwery.settle(30.minutes)

            val entry = kwery.client.cacheSnapshot().firstOrNull { it.key == key }
            assertNotNull(entry, "Lazily leaks the entry — this is the documented hazard")
            assertTrue(entry.isActive, "and it is still counted as observed")

            // The cache cannot tell this from a screen left open all afternoon,
            // so it reports rather than warns. Thirty minutes of continuous
            // observation is what a developer needs to see to make the call.
            val observedFor = kwery.currentTimeMillis - assertNotNull(entry.observedSinceMillis)
            assertTrue(
                observedFor >= 30.minutes.inWholeMilliseconds,
                "observedSinceMillis should show the full duration, was ${observedFor}ms",
            )
        }

    @Test
    fun `observedSinceMillis tracks the first observer, not the latest`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoListKey("all")

        val first = backgroundScope.launch { kwery.query(key) { listOf("a") }.collect { } }
        kwery.settle()
        val startedAt = assertNotNull(
            kwery.client.cacheSnapshot().first { it.key == key }.observedSinceMillis,
        )

        // A second screen opens on the same key an hour later. The entry has
        // been continuously observed the whole time, and that is the number
        // worth reporting — resetting it here would hide exactly the leak this
        // field exists to reveal.
        kwery.settle(1.hours)
        val second = backgroundScope.launch { kwery.query(key) { listOf("a") }.collect { } }
        kwery.settle()

        assertEquals(
            startedAt,
            kwery.client.cacheSnapshot().first { it.key == key }.observedSinceMillis,
            "a later observer must not restart the clock",
        )

        first.cancel()
        second.cancel()
    }

    @Test
    fun `observedSinceMillis is null once nothing is observing`() = runTest {
        val kwery = TestQueryClient(this)
        val key = TodoListKey("all")
        val collector = backgroundScope.launch { kwery.query(key) { listOf("a") }.collect { } }
        kwery.settle()

        assertNotNull(
            kwery.client.cacheSnapshot().first { it.key == key }.observedSinceMillis,
        )

        collector.cancel()
        kwery.settle(10.seconds)

        assertNull(
            kwery.client.cacheSnapshot().first { it.key == key }.observedSinceMillis,
            "a released entry must not look like a long-lived observer",
        )
    }
}
