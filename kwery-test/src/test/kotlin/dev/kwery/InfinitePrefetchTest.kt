package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class PagedFeedKey(val id: String) : QueryKey<InfiniteData<Int, List<String>>> {
    override val parts get() = listOf("feed", id)
}

@OptIn(ExperimentalCoroutinesApi::class)
class InfinitePrefetchTest {

    private val options = InfiniteQueryOptions<Int, List<String>>(
        initialPageParam = 1,
        getNextPageParam = { _, _, last -> if (last >= 5) null else last + 1 },
    )
    private val fresh = QueryOptions(staleTime = StaleTime.of(5.minutes), retry = RetryPolicy.Never)

    @Test
    fun `prefetching an infinite query loads one page by default`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("1")
        var calls = 0

        kwery.client.prefetchInfiniteQuery(key, options, fresh) { page -> calls++; listOf("p$page") }
        kwery.settle()

        val data = assertNotNull(kwery.client.getQueryData(key))
        assertEquals(listOf(listOf("p1")), data.pages)
        assertEquals(listOf(1), data.pageParams)
        assertEquals(
            1,
            calls,
            "one page by default — every extra page is a request nobody has looked at",
        )
    }

    @Test
    fun `pages asks for exactly that many, walking getNextPageParam`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("1")
        var calls = 0

        kwery.client.prefetchInfiniteQuery(key, options, fresh, pages = 3) { page ->
            calls++
            listOf("p$page")
        }
        kwery.settle()

        val data = assertNotNull(kwery.client.getQueryData(key))
        assertEquals(listOf(listOf("p1"), listOf("p2"), listOf("p3")), data.pages)
        assertEquals(listOf(1, 2, 3), data.pageParams, "params stay aligned with pages")
        assertEquals(3, calls)
    }

    @Test
    fun `it stops early when there are no more pages`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("1")
        var calls = 0

        // Asking for ten when the source has five must not loop or pad.
        kwery.client.prefetchInfiniteQuery(key, options, fresh, pages = 10) { page ->
            calls++
            listOf("p$page")
        }
        kwery.settle()

        assertEquals(5, calls, "getNextPageParam returning null ends the walk")
        assertEquals(5, kwery.client.getQueryData(key)?.pages?.size)
    }

    @Test
    fun `prefetching fresh pages issues no request`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("1")
        var calls = 0

        kwery.client.prefetchInfiniteQuery(key, options, fresh) { page -> calls++; listOf("p$page") }
        kwery.settle()
        repeat(5) {
            kwery.client.prefetchInfiniteQuery(key, options, fresh) { page ->
                calls++
                listOf("p$page")
            }
        }
        kwery.settle()

        assertEquals(1, calls, "safe on a scroll tick, same as prefetchQuery")
    }

    @Test
    fun `a failing prefetch never throws, but is recorded`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("broken")

        kwery.client.prefetchInfiniteQuery(key, options, fresh) { error("no") }
        kwery.settle()

        assertEquals(QueryStatus.Error, kwery.client.getQueryState(key)?.status)
    }

    @Test
    fun `ensureInfiniteQueryData throws where prefetch swallows`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("broken")

        assertFailsWith<IllegalStateException> {
            kwery.client.ensureInfiniteQueryData(key, options, fresh) { error("no") }
        }
    }

    @Test
    fun `ensureInfiniteQueryData serves a warm cache without fetching`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("1")
        var calls = 0

        val first = kwery.client.ensureInfiniteQueryData(key, options, fresh) { page ->
            calls++
            listOf("p$page")
        }
        kwery.settle()
        val second = kwery.client.ensureInfiniteQueryData(key, options, fresh) { page ->
            calls++
            listOf("other$page")
        }

        assertEquals(first, second)
        assertEquals(1, calls, "read-through means read")
    }

    @Test
    fun `retry is applied per page, not to the whole walk`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("flaky")
        val attempts = mutableListOf<Int>()

        // Page 2 fails once. A whole-walk retry would refetch page 1 as well,
        // which is the bug the real infinite query avoids by retrying per page.
        var page2Failures = 0
        kwery.client.prefetchInfiniteQuery(
            key,
            options,
            QueryOptions(
                staleTime = StaleTime.of(5.minutes),
                retry = RetryPolicy.Times(2),
                retryDelay = RetryDelay.constant(1.seconds),
            ),
            pages = 3,
        ) { page ->
            attempts += page
            if (page == 2 && page2Failures++ < 1) error("flaky page")
            listOf("p$page")
        }
        kwery.settle(10.seconds)

        assertEquals(
            listOf(1, 2, 2, 3),
            attempts,
            "page 1 is fetched once — the retry is scoped to the page that failed",
        )
    }

    @Test
    fun `a prefetched infinite entry is inactive and starts its gcTime`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagedFeedKey("1")

        kwery.client.prefetchInfiniteQuery(
            key,
            options,
            QueryOptions(staleTime = StaleTime.of(5.minutes), gcTime = 1.minutes),
        ) { page -> listOf("p$page") }
        kwery.settle()
        assertNotNull(kwery.client.getQueryData(key))

        kwery.settle(2.minutes)
        assertNull(
            kwery.client.cacheSnapshot().firstOrNull { it.key == key },
            "nothing observes a prefetch, so its countdown starts immediately",
        )
    }

    @Test
    fun `asking for zero pages is rejected rather than caching nothing`() = runTest {
        val kwery = TestQueryClient(this)
        assertFailsWith<IllegalArgumentException> {
            kwery.client.ensureInfiniteQueryData(PagedFeedKey("1"), options, fresh, pages = 0) {
                listOf("p")
            }
        }
    }
}
