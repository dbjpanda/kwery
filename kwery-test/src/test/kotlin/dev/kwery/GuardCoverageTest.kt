package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class ItemKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("item", id)
}

private data class PagedKey(val id: String) : QueryKey<InfiniteData<Int, List<String>>> {
    override val parts get() = listOf("paged", id)
}

/**
 * Guards that no test could kill.
 *
 * Each of these was found by mutating it and watching the whole suite stay
 * green. A guard nothing can kill is either dead code or protecting behaviour
 * nobody checks, and both are worth resolving rather than leaving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardCoverageTest {

    // ---- Eviction scheduling ---------------------------------------------

    @Test
    fun `a second unobserved fetch does not restart the eviction countdown`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ItemKey("1")
        val options = QueryOptions(gcTime = 5.minutes, retry = RetryPolicy.Never)

        // Two prefetches, two minutes apart. Both are unobserved, so both reach
        // the eviction scheduler.
        kwery.client.fetchQuery(key, options) { "v" }
        kwery.settle()
        kwery.settle(2.minutes)
        kwery.client.fetchQuery(key, options) { "v" }
        kwery.settle()

        // Just under gcTime measured from the FIRST scheduling.
        kwery.settle(2.minutes + 50.seconds)
        assertNotNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == key },
            "still inside the window that started when the entry first went idle",
        )

        kwery.settle(30.seconds)
        assertNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == key },
            "the countdown runs from when the entry went idle, not from the last touch — " +
                "re-arming on every unobserved fetch would keep a hot prefetch key alive for ever",
        )
    }

    // ---- Focus and reconnect triggers ------------------------------------

    @Test
    fun `an unobserved but still cached query does not refetch on focus`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ItemKey("1")
        var calls = 0

        val job = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.settle()
        assertEquals(1, calls)

        job.cancel()
        // Past the grace window, so the entry is inactive — but well inside
        // gcTime, so it is still in the cache and could be refetched.
        kwery.settle(30.seconds)

        kwery.setFocused(false)
        kwery.settle(1.minutes)
        kwery.setFocused(true)
        kwery.settle()

        assertEquals(
            1,
            calls,
            "refetching every cached entry on every foreground would be an enormous burst " +
                "for data nothing is showing",
        )
    }

    @Test
    fun `an unobserved query does not refetch on reconnect either`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ItemKey("1")
        var calls = 0

        val job = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.settle()
        job.cancel()
        kwery.settle(30.seconds)

        kwery.setOnline(false)
        kwery.settle(1.minutes)
        kwery.setOnline(true)
        kwery.settle()

        assertEquals(1, calls, "same rule, same reason")
    }

    @Test
    fun `a brief app switch right after a rotation does not refetch`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ItemKey("1")
        var calls = 0

        val first = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.settle()
        assertEquals(1, calls)

        // Rotation: detach and reattach inside the grace window. That marks the
        // entry as continuing rather than freshly mounted.
        first.cancel()
        kwery.settle(1.seconds)
        val second = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.settle()
        assertEquals(1, calls, "the rotation itself refetches nothing")

        // A notification pulls the user away and back immediately. Without the
        // continuation window this fires a request for data that is seconds old.
        kwery.setFocused(false)
        kwery.settle(1.seconds)
        kwery.setFocused(true)
        kwery.settle()

        assertEquals(1, calls, "a brief app switch inside the window refetches nothing")

        second.cancel()
    }

    @Test
    fun `a brief app switch does not refetch, with or without a rotation`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ItemKey("2")
        var calls = 0

        val job = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.settle()

        // Away for one second: a notification, a glance at the app switcher.
        // The suppression lives in QueryClient.observeReturns, which does not
        // even notify entries unless the app was away longer than the grace
        // period — so this holds with no rotation involved.
        kwery.setFocused(false)
        kwery.settle(1.seconds)
        kwery.setFocused(true)
        kwery.settle()

        assertEquals(1, calls, "a one-second absence is not a return")
        job.cancel()
    }

    @Test
    fun `an Activity recreated while away still refetches on return`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ItemKey("3")
        var calls = 0

        val first = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.settle()
        assertEquals(1, calls)

        // Backgrounded for two minutes, and the Activity is recreated as the
        // app comes back — the reattach lands at the moment of return.
        kwery.setFocused(false)
        kwery.settle(2.minutes)
        first.cancel()
        val second = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.setFocused(true)
        kwery.settle()

        assertEquals(
            2,
            calls,
            "a genuine two-minute absence must refetch even though a rotation " +
                "happened to land at the same moment",
        )
        second.cancel()
    }

    @Test
    fun `a real return to the app does refetch`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ItemKey("1")
        var calls = 0

        val job = backgroundScope.launch { kwery.query(key) { calls++; "v" }.collect { } }
        kwery.settle()

        // The other half of the rule: suppression must not become "never".
        kwery.setFocused(false)
        kwery.settle(2.minutes)
        kwery.setFocused(true)
        kwery.settle()

        assertEquals(2, calls, "minutes away is a real return, and the data is stale")
        job.cancel()
    }

    // ---- Infinite query page trimming ------------------------------------

    @Test
    fun `maxPages evicts from the end the user is moving away from`() = runTest {
        val kwery = TestQueryClient(this)
        val query = kwery.client.infiniteQuery(
            PagedKey("1"),
            InfiniteQueryOptions(
                initialPageParam = 1,
                getNextPageParam = { _, _, last -> if (last >= 5) null else last + 1 },
                maxPages = 3,
            ),
        ) { page -> listOf("p$page") }

        val job = backgroundScope.launch { query.state.collect { } }
        kwery.settle()

        repeat(4) { query.fetchNextPage(); kwery.settle() }

        val data = kwery.client.getQueryData(PagedKey("1"))
        assertNotNull(data)
        assertEquals(3, data.pages.size, "capped at maxPages")
        assertEquals(
            listOf(listOf("p3"), listOf("p4"), listOf("p5")),
            data.pages,
            "paging forward drops the oldest pages, keeping what the user is scrolling towards",
        )

        job.cancel()
    }

    @Test
    fun `a page count under maxPages is left alone`() = runTest {
        val kwery = TestQueryClient(this)
        val query = kwery.client.infiniteQuery(
            PagedKey("2"),
            InfiniteQueryOptions(
                initialPageParam = 1,
                getNextPageParam = { _, _, last -> if (last >= 5) null else last + 1 },
                maxPages = 10,
            ),
        ) { page -> listOf("p$page") }

        val job = backgroundScope.launch { query.state.collect { } }
        kwery.settle()
        repeat(2) { query.fetchNextPage(); kwery.settle() }

        val data = kwery.client.getQueryData(PagedKey("2"))
        assertEquals(3, data?.pages?.size, "nothing to trim, nothing trimmed")
        job.cancel()
    }
}
