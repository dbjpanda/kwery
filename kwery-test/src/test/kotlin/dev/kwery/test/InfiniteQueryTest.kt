package dev.kwery.test

import dev.kwery.InfiniteData
import dev.kwery.InfiniteQueryOptions
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.RefetchStrategy
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import dev.kwery.flatten
import dev.kwery.infiniteQuery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class Page(val items: List<String>, val nextCursor: Int?, val prevCursor: Int? = null)

private object PagedFeedKey : QueryKey<InfiniteData<Int, Page>> {
    override val parts get() = listOf("feed")
}

/** Feature 16 — infinite queries. */
class InfiniteQueryTest {

    private val fresh = QueryOptions(staleTime = StaleTime.of(5.minutes), retry = RetryPolicy.Never)

    /** Three pages of two items, then the end. */
    private fun page(cursor: Int): Page = Page(
        items = listOf("item${cursor * 2}", "item${cursor * 2 + 1}"),
        nextCursor = if (cursor < 2) cursor + 1 else null,
        prevCursor = if (cursor > 0) cursor - 1 else null,
    )

    private fun options(
        maxPages: Int? = null,
        strategy: RefetchStrategy = RefetchStrategy.AllPages,
        bidirectional: Boolean = false,
    ) = InfiniteQueryOptions<Int, Page>(
        initialPageParam = 0,
        getNextPageParam = { last, _, _ -> last.nextCursor },
        getPreviousPageParam = if (bidirectional) { first, _, _ -> first.prevCursor } else null,
        maxPages = maxPages,
        refetchStrategy = strategy,
    )

    private fun TestQueryClient.feed(
        opts: InfiniteQueryOptions<Int, Page> = options(),
        fetchMs: Long = 50,
        onFetch: (Int) -> Unit = {},
    ) = client.infiniteQuery(PagedFeedKey, opts, fresh) { cursor ->
        onFetch(cursor)
        delay(fetchMs)
        page(cursor)
    }

    // ---- Accumulation -----------------------------------------------------

    @Test
    fun `pages accumulate under one key with aligned params`() = runTest {
        val kwery = TestQueryClient(this)
        val feed = kwery.feed()
        val job: Job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)

        assertEquals(listOf(0), kwery.client.getQueryData(PagedFeedKey)!!.pageParams)

        feed.fetchNextPage()
        kwery.settle(200.milliseconds)
        feed.fetchNextPage()
        kwery.settle(200.milliseconds)

        val data = kwery.client.getQueryData(PagedFeedKey)!!
        assertEquals(3, data.pages.size)
        assertEquals(listOf(0, 1, 2), data.pageParams)
        assertEquals(
            listOf("item0", "item1", "item2", "item3", "item4", "item5"),
            data.flatten { it.items },
        )
        job.cancel()
    }

    @Test
    fun `a null next page param ends the list`() = runTest {
        val kwery = TestQueryClient(this)
        val feed = kwery.feed()
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)

        assertTrue(feed.hasNextPage())
        repeat(2) { feed.fetchNextPage(); kwery.settle(200.milliseconds) }

        assertFalse(feed.hasNextPage(), "the last page returned a null cursor")

        // Asking again is a no-op rather than an error.
        feed.fetchNextPage()
        kwery.settle(200.milliseconds)
        assertEquals(3, kwery.client.getQueryData(PagedFeedKey)!!.pages.size)
        job.cancel()
    }

    @Test
    fun `bidirectional paging prepends and keeps params aligned`() = runTest {
        val kwery = TestQueryClient(this)
        val opts = InfiniteQueryOptions<Int, Page>(
            initialPageParam = 2,
            getNextPageParam = { last, _, _ -> last.nextCursor },
            getPreviousPageParam = { first, _, _ -> first.prevCursor },
        )
        val feed = kwery.feed(opts)
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)

        assertTrue(feed.hasPreviousPage())
        feed.fetchPreviousPage()
        kwery.settle(200.milliseconds)

        val data = kwery.client.getQueryData(PagedFeedKey)!!
        assertEquals(listOf(1, 2), data.pageParams, "the earlier page goes in front")
        assertEquals(listOf("item2", "item3", "item4", "item5"), data.flatten { it.items })
        job.cancel()
    }

    @Test
    fun `hasPreviousPage is false without a previous-page function`() = runTest {
        val kwery = TestQueryClient(this)
        val feed = kwery.feed()
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)

        assertFalse(feed.hasPreviousPage())
        job.cancel()
    }

    // ---- Concurrency guard ------------------------------------------------

    @Test
    fun `overlapping page fetches are conflated by the shared cache entry`() = runTest {
        // All pages live in ONE entry, and the entry deduplicates in-flight
        // fetches — so a scroll listener firing three times costs one request.
        // The safety here is structural, not a guard that could be forgotten.
        val kwery = TestQueryClient(this)
        val fetched = mutableListOf<Int>()
        val feed = kwery.feed(fetchMs = 500) { fetched += it }
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(1.seconds)
        fetched.clear()

        feed.fetchNextPage()
        kwery.settle(50.milliseconds) // still fetching
        feed.fetchNextPage()
        feed.fetchNextPage()
        kwery.settle(2.seconds)

        assertEquals(listOf(1), fetched, "only one page request should have gone out")
        assertEquals(2, kwery.client.getQueryData(PagedFeedKey)!!.pages.size)
        job.cancel()
    }

    // ---- Refetch strategies -----------------------------------------------

    @Test
    fun `AllPages refetches every page sequentially from the first`() = runTest {
        val kwery = TestQueryClient(this)
        val fetched = mutableListOf<Int>()
        val feed = kwery.feed(options(strategy = RefetchStrategy.AllPages)) { fetched += it }
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)
        repeat(2) { feed.fetchNextPage(); kwery.settle(200.milliseconds) }
        fetched.clear()

        kwery.client.invalidateQueries(PagedFeedKey)
        kwery.settle(2.seconds)

        assertEquals(listOf(0, 1, 2), fetched, "in order, from the first page")
        assertEquals(3, kwery.client.getQueryData(PagedFeedKey)!!.pages.size)
        job.cancel()
    }

    @Test
    fun `FirstPageOnly refetches one page and keeps the rest`() = runTest {
        // The reason this exists: forty scrolled pages would otherwise mean
        // forty sequential requests on every refresh.
        val kwery = TestQueryClient(this)
        val fetched = mutableListOf<Int>()
        val feed = kwery.feed(options(strategy = RefetchStrategy.FirstPageOnly)) { fetched += it }
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)
        repeat(2) { feed.fetchNextPage(); kwery.settle(200.milliseconds) }
        fetched.clear()

        kwery.client.invalidateQueries(PagedFeedKey)
        kwery.settle(2.seconds)

        assertEquals(listOf(0), fetched, "one request, not three")
        val data = kwery.client.getQueryData(PagedFeedKey)!!
        assertEquals(3, data.pages.size, "the pages already scrolled past are kept")
        assertEquals(listOf(0, 1, 2), data.pageParams)
        job.cancel()
    }

    @Test
    fun `Windowed refetches the first n pages`() = runTest {
        val kwery = TestQueryClient(this)
        val fetched = mutableListOf<Int>()
        val feed = kwery.feed(options(strategy = RefetchStrategy.Windowed(2))) { fetched += it }
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)
        repeat(2) { feed.fetchNextPage(); kwery.settle(200.milliseconds) }
        fetched.clear()

        kwery.client.invalidateQueries(PagedFeedKey)
        kwery.settle(2.seconds)

        assertEquals(listOf(0, 1), fetched)
        assertEquals(3, kwery.client.getQueryData(PagedFeedKey)!!.pages.size)
        job.cancel()
    }

    // ---- maxPages ---------------------------------------------------------

    @Test
    fun `maxPages evicts from the front when paging forward`() = runTest {
        val kwery = TestQueryClient(this)
        val feed = kwery.feed(options(maxPages = 2))
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)

        feed.fetchNextPage()
        kwery.settle(200.milliseconds)
        feed.fetchNextPage()
        kwery.settle(200.milliseconds)

        val data = kwery.client.getQueryData(PagedFeedKey)!!
        assertEquals(2, data.pages.size)
        assertEquals(
            listOf(1, 2),
            data.pageParams,
            "paging forward drops the oldest, not the newest",
        )
        assertEquals(data.pages.size, data.pageParams.size)
        job.cancel()
    }

    @Test
    fun `maxPages evicts from the back when paging backward`() = runTest {
        val kwery = TestQueryClient(this)
        val opts = InfiniteQueryOptions<Int, Page>(
            initialPageParam = 2,
            getNextPageParam = { last, _, _ -> last.nextCursor },
            getPreviousPageParam = { first, _, _ -> first.prevCursor },
            maxPages = 2,
        )
        val feed = kwery.feed(opts)
        val job = backgroundScope.launch { feed.state.collect { } }
        kwery.settle(200.milliseconds)

        feed.fetchPreviousPage()
        kwery.settle(200.milliseconds)
        feed.fetchPreviousPage()
        kwery.settle(200.milliseconds)

        val data = kwery.client.getQueryData(PagedFeedKey)!!
        assertEquals(2, data.pages.size)
        assertEquals(listOf(0, 1), data.pageParams, "paging back drops the newest")
        job.cancel()
    }

    // ---- Invariants -------------------------------------------------------

    @Test
    fun `pages and params can never be misaligned`() = runTest {
        // Misalignment silently refetches the wrong page, so it is rejected at
        // construction rather than discovered later.
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            InfiniteData(pages = listOf("a", "b"), pageParams = listOf(1))
        }
    }

    @Test
    fun `windowed refetch rejects a non-positive window`() = runTest {
        kotlin.test.assertFailsWith<IllegalArgumentException> { RefetchStrategy.Windowed(0) }
    }

    @Test
    fun `flatten reads items out of accumulated pages`() = runTest {
        val data = InfiniteData(
            pages = listOf(Page(listOf("a", "b"), 1), Page(listOf("c"), null)),
            pageParams = listOf(0, 1),
        )
        assertEquals(listOf("a", "b", "c"), data.flatten { it.items })
    }
}
