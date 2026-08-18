package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private data class ListKey(val id: String) : QueryKey<List<String>> {
    override val parts get() = listOf("list", id)
}

private data class PagesKey(val id: String) : QueryKey<InfiniteData<Int, List<String>>> {
    override val parts get() = listOf("pages", id)
}

/**
 * Two behaviours the roadmap listed as done-but-untested.
 *
 * Found by comparing the status table against the checkboxes in each feature
 * file rather than trusting the table — which is the whole reason the table is
 * not the source of truth.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoadmapGapTest {

    // ---- Feature 12: the raw callback form -------------------------------

    @Test
    fun `the raw callback form gives the same rollback as the helper`() = runTest {
        val kwery = TestQueryClient(this)
        val key = ListKey("1")
        kwery.client.setQueryData(key) { listOf("a") }

        // `optimisticMutation` is sugar. Someone with a shape it does not fit
        // writes onMutate/onError by hand, and that path has to work — it is
        // the one every unusual case falls back to.
        val mutation = kwery.client.mutation(
            MutationOptions<String, Unit, List<String>?>(
                mutationFn = { error("server said no") },
                onMutate = { value ->
                    val snapshot = kwery.client.getQueryData(key)
                    kwery.client.setQueryData(key) { it.orEmpty() + value }
                    snapshot
                },
                onError = { _, _, context ->
                    kwery.client.setQueryData(key) { context }
                },
                retry = RetryPolicy.Never,
            ),
        )

        val job = mutation.mutate("b")
        kwery.settle()

        assertEquals(MutationStatus.Error, mutation.state.value.status)
        assertEquals(
            listOf("a"),
            kwery.client.getQueryData(key),
            "the hand-written rollback must restore exactly what was there",
        )
        job.cancel()
    }

    @Test
    fun `the raw callback form sees its onMutate context in onSettled`() = runTest {
        val kwery = TestQueryClient(this)
        val seen = mutableListOf<String?>()

        val mutation = kwery.client.mutation(
            MutationOptions<Unit, String, String>(
                mutationFn = { "done" },
                onMutate = { "context-value" },
                onSettled = { _, _, _, context -> seen += context },
                retry = RetryPolicy.Never,
            ),
        )
        mutation.mutate(Unit)
        kwery.settle()

        assertEquals<List<String?>>(
            listOf("context-value"),
            seen,
            "the context is typed and reaches onSettled",
        )
    }

    // ---- Feature 16: pages and params stay aligned -----------------------

    @Test
    fun `setQueryData on an infinite query keeps pages and params aligned`() = runTest {
        val kwery = TestQueryClient(this)
        val key = PagesKey("1")

        val query = kwery.client.infiniteQuery(
            key,
            InfiniteQueryOptions(
                initialPageParam = 1,
                getNextPageParam = { _, _, last -> if (last >= 3) null else last + 1 },
            ),
        ) { page -> listOf("p$page") }

        val job = backgroundScope.launch { query.state.collect { } }
        kwery.settle()
        repeat(2) { query.fetchNextPage(); kwery.settle() }

        val before = assertNotNull(kwery.client.getQueryData(key))
        assertEquals(3, before.pages.size)
        assertEquals(before.pages.size, before.pageParams.size, "aligned to begin with")

        // Editing a page in place — marking an item read, say — must not
        // disturb the params, or the next fetchNextPage asks for the wrong one.
        kwery.client.setQueryData(key) { current ->
            current?.copy(pages = current.pages.map { page -> page.map { it.uppercase() } })
        }
        kwery.settle()

        val after = assertNotNull(kwery.client.getQueryData(key))
        assertEquals(listOf(listOf("P1"), listOf("P2"), listOf("P3")), after.pages)
        assertEquals(
            before.pageParams,
            after.pageParams,
            "params untouched, so paging continues from where it was",
        )
        assertEquals(after.pages.size, after.pageParams.size, "and still aligned")

        job.cancel()
    }

    @Test
    fun `dropping a page without its param is caught rather than silently paging wrong`() =
        runTest {
            val kwery = TestQueryClient(this)
            val key = PagesKey("2")

            val query = kwery.client.infiniteQuery(
                key,
                InfiniteQueryOptions(
                    initialPageParam = 1,
                    getNextPageParam = { _, _, last -> if (last >= 3) null else last + 1 },
                ),
            ) { page -> listOf("p$page") }

            val job = backgroundScope.launch { query.state.collect { } }
            kwery.settle()
            query.fetchNextPage()
            kwery.settle()

            // A misaligned InfiniteData is a programming error, not a state the
            // library should try to interpret: pages[i] is only meaningful
            // alongside pageParams[i].
            assertFailsWith<IllegalArgumentException> {
                InfiniteData(pages = listOf(listOf("a"), listOf("b")), pageParams = listOf(1))
            }

            job.cancel()
        }
}
