package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private data class InboxKey(val folder: String, val page: Int) : QueryKey<List<String>> {
    override val parts get() = listOf("inbox", folder, page)
}

private data class ProfileKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("profile", id)
}

/**
 * Writing across a family of keys at once.
 *
 * The point is the keys you do not know: a paginated inbox has as many entries
 * as the user has scrolled, and "mark everything read" cannot enumerate them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetQueriesDataTest {

    @Test
    fun `it updates every matching entry and leaves the rest alone`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.client.setQueryData(InboxKey("in", 1), listOf("a", "b"))
        kwery.client.setQueryData(InboxKey("in", 2), listOf("c"))
        kwery.client.setQueryData(InboxKey("archive", 1), listOf("z"))
        kwery.client.setQueryData(ProfileKey("me"), "unrelated")

        kwery.client.setQueriesData<List<String>>(
            QueryFilters(keyPrefix = listOf("inbox", "in")),
        ) { current -> current?.map { it.uppercase() } }

        assertEquals(listOf("A", "B"), kwery.client.getQueryData(InboxKey("in", 1)))
        assertEquals(listOf("C"), kwery.client.getQueryData(InboxKey("in", 2)))
        assertEquals(
            listOf("z"),
            kwery.client.getQueryData(InboxKey("archive", 1)),
            "a sibling folder is not a match",
        )
        assertEquals(
            "unrelated",
            kwery.client.getQueryData(ProfileKey("me")),
            "and an unrelated family certainly is not",
        )
    }

    @Test
    fun `matching nothing is a no-op rather than an error`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.client.setQueryData(ProfileKey("me"), "kept")

        kwery.client.setQueriesData<List<String>>(
            QueryFilters(keyPrefix = listOf("nothing-here")),
        ) { listOf("written") }

        assertEquals("kept", kwery.client.getQueryData(ProfileKey("me")))
    }

    @Test
    fun `returning null clears the data`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.client.setQueryData(InboxKey("in", 1), listOf("a"))

        kwery.client.setQueriesData<List<String>>(
            QueryFilters(keyPrefix = listOf("inbox")),
        ) { null }

        assertNull(kwery.client.getQueryData(InboxKey("in", 1)))
    }

    @Test
    fun `an observing screen sees the bulk write`() = runTest {
        val kwery = TestQueryClient(this)
        val key = InboxKey("in", 1)
        var last: QueryState<List<String>>? = null

        val job = backgroundScope.launch {
            kwery.query(key) { listOf("from server") }.collect { last = it }
        }
        kwery.settle()
        assertEquals(listOf("from server"), last?.data)

        kwery.client.setQueriesData<List<String>>(
            QueryFilters(keyPrefix = listOf("inbox")),
        ) { current -> current?.plus("appended") }
        kwery.settle()

        assertEquals(
            listOf("from server", "appended"),
            last?.data,
            "a bulk write reaches observers like any other write",
        )
        job.cancel()
    }

    @Test
    fun `the filter can select by more than a key prefix`() = runTest {
        val kwery = TestQueryClient(this)
        kwery.client.setQueryData(InboxKey("in", 1), listOf("a"))
        kwery.client.setQueryData(InboxKey("in", 2), listOf("b"))

        kwery.client.setQueriesData<List<String>>(
            QueryFilters(predicate = { it.key == InboxKey("in", 2) }),
        ) { listOf("only me") }

        assertEquals(listOf("a"), kwery.client.getQueryData(InboxKey("in", 1)))
        assertEquals(listOf("only me"), kwery.client.getQueryData(InboxKey("in", 2)))
    }
}
