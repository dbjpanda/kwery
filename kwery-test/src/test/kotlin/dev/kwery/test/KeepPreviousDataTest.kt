package dev.kwery.test

import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryState
import dev.kwery.QueryStatus
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import dev.kwery.keepPreviousData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private data class PageKey(val page: Int) : QueryKey<String> {
    override val parts get() = listOf("page", page)
}

/** Feature 09 — placeholder data for paginated lists. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepPreviousDataTest {

    private val fresh = QueryOptions(staleTime = StaleTime.of(5.minutes), retry = RetryPolicy.Never)

    @Test
    fun `the previous page stays on screen while the next one loads`() = runTest {
        val kwery = TestQueryClient(this)
        val page = MutableStateFlow(1)
        val seen = mutableListOf<QueryState<String>>()

        val job = backgroundScope.launch {
            page.flatMapLatest { p ->
                kwery.query(PageKey(p), fresh) { delay(200); "page $p" }
            }.keepPreviousData().collect { seen += it }
        }
        kwery.settle(300.milliseconds)
        assertEquals("page 1", seen.last().data)

        page.value = 2
        kwery.settle(50.milliseconds) // page 2 in flight

        val whileLoading = seen.last()
        assertEquals("page 1", whileLoading.data, "the list must not flash empty")
        assertTrue(whileLoading.isPlaceholderData)
        assertEquals(QueryStatus.Success, whileLoading.status, "there IS something on screen")
        assertTrue(whileLoading.isFetching)

        kwery.settle(300.milliseconds)
        val settled = seen.last()
        assertEquals("page 2", settled.data)
        assertFalse(settled.isPlaceholderData)
        job.cancel()
    }

    @Test
    fun `nothing is substituted on the very first load`() = runTest {
        val kwery = TestQueryClient(this)
        val seen = mutableListOf<QueryState<String>>()

        val job = backgroundScope.launch {
            kwery.query(PageKey(1), fresh) { delay(200); "page 1" }
                .keepPreviousData()
                .collect { seen += it }
        }
        kwery.settle(50.milliseconds)

        assertEquals(null, seen.last().data, "there is no previous page to show")
        assertFalse(seen.last().isPlaceholderData)
        job.cancel()
    }

    @Test
    fun `an error is surfaced rather than hidden behind a stale page`() = runTest {
        // A stale page concealing a failure is worse than a gap: the user would
        // believe they were looking at page 2.
        val kwery = TestQueryClient(this)
        val page = MutableStateFlow(1)
        val seen = mutableListOf<QueryState<String>>()

        val job = backgroundScope.launch {
            page.flatMapLatest { p ->
                kwery.query(PageKey(p), fresh) {
                    delay(100)
                    if (p == 2) throw IllegalStateException("page 2 is broken") else "page $p"
                }
            }.keepPreviousData().collect { seen += it }
        }
        kwery.settle(300.milliseconds)
        page.value = 2
        kwery.settle(500.milliseconds)

        val last = seen.last()
        assertTrue(last.isError, "the failure must reach the caller")
        assertFalse(last.isPlaceholderData)
        job.cancel()
    }

    @Test
    fun `going back to a cached page shows it immediately`() = runTest {
        val kwery = TestQueryClient(this)
        val page = MutableStateFlow(1)
        val seen = mutableListOf<QueryState<String>>()

        val job = backgroundScope.launch {
            page.flatMapLatest { p ->
                kwery.query(PageKey(p), fresh) { delay(200); "page $p" }
            }.keepPreviousData().collect { seen += it }
        }
        kwery.settle(300.milliseconds)
        page.value = 2
        kwery.settle(300.milliseconds)
        seen.clear()

        page.value = 1 // already cached and still fresh
        kwery.settle(10.milliseconds)

        assertEquals("page 1", seen.last().data)
        assertFalse(
            seen.last().isPlaceholderData,
            "cached data is real data, not a placeholder",
        )
        job.cancel()
    }

    @Test
    fun `the placeholder is never written to the cache`() = runTest {
        val kwery = TestQueryClient(this)
        val page = MutableStateFlow(1)

        val job = backgroundScope.launch {
            page.flatMapLatest { p ->
                kwery.query(PageKey(p), fresh) { delay(200); "page $p" }
            }.keepPreviousData().collect { }
        }
        kwery.settle(300.milliseconds)
        page.value = 2
        kwery.settle(50.milliseconds)

        assertEquals(
            null,
            kwery.client.getQueryData(PageKey(2)),
            "page 2's entry must not contain page 1's data",
        )
        job.cancel()
    }
}
