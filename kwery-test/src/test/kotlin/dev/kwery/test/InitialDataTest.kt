package dev.kwery.test

import dev.kwery.InitialData
import dev.kwery.QueryKey
import dev.kwery.QueryOptions
import dev.kwery.QueryStatus
import dev.kwery.RetryPolicy
import dev.kwery.StaleTime
import dev.kwery.dehydrate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private data class DetailKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("detail", id)
}

/** Feature 09 — seeding a query with data you already have. */
class InitialDataTest {

    private fun options(stale: kotlin.time.Duration = 5.minutes) =
        QueryOptions(staleTime = StaleTime.of(stale), retry = RetryPolicy.Never)

    @Test
    fun `seed data renders immediately without a fetch`() = runTest {
        val kwery = TestQueryClient(this)
        val key = DetailKey("1")

        val job = backgroundScope.launch {
            kwery.query(key, options(), InitialData({ "from the list" })) {
                delay(100); "from the server"
            }.collect { }
        }
        kwery.settle(10.milliseconds)

        val state = kwery.client.getQueryState(key)!!
        assertEquals("from the list", state.data)
        assertEquals(QueryStatus.Success, state.status)
        assertEquals(0, kwery.requestCount, "seeding is not a fetch")
        job.cancel()
    }

    @Test
    fun `fresh seed data suppresses the first fetch entirely`() = runTest {
        val kwery = TestQueryClient(this)
        val job = backgroundScope.launch {
            kwery.query(DetailKey("1"), options(), InitialData({ "seeded" })) {
                delay(100); "fetched"
            }.collect { }
        }
        kwery.settle(1.minutes)

        assertEquals(0, kwery.requestCount, "within staleTime, nothing should fetch")
        job.cancel()
    }

    @Test
    fun `stale seed data renders at once and refetches`() = runTest {
        // The reason updatedAt exists. Data lifted from a list loaded an hour
        // ago is an hour old; claiming it is new would leave the screen showing
        // stale content for the whole staleTime window.
        val kwery = TestQueryClient(this)
        val key = DetailKey("1")
        kwery.settle(10.minutes) // let the clock advance

        val loadedLongAgo = kwery.currentTimeMillis - 9.minutes.inWholeMilliseconds
        val job = backgroundScope.launch {
            kwery.query(
                key,
                options(stale = 5.minutes),
                InitialData({ "from an old list" }, updatedAt = loadedLongAgo),
            ) { delay(100); "fresh from the server" }.collect { }
        }
        kwery.settle(10.milliseconds)

        assertEquals(
            "from an old list",
            kwery.client.getQueryData(key),
            "it still renders immediately",
        )

        kwery.settle(300.milliseconds)
        assertEquals(1, kwery.requestCount, "and refetches, because it was already stale")
        assertEquals("fresh from the server", kwery.client.getQueryData(key))
        job.cancel()
    }

    @Test
    fun `seed data never overwrites data already in the cache`() = runTest {
        // Overwriting a real response with a guess would be a silent
        // regression, so seeding applies only to a genuinely new entry.
        val kwery = TestQueryClient(this)
        val key = DetailKey("1")
        kwery.client.setQueryData(key, "already fetched")

        val job = backgroundScope.launch {
            kwery.query(key, options(), InitialData({ "a guess" })) {
                delay(100); "from the server"
            }.collect { }
        }
        kwery.settle(200.milliseconds)

        assertEquals("already fetched", kwery.client.getQueryData(key))
        job.cancel()
    }

    @Test
    fun `a null seed value seeds nothing`() = runTest {
        val kwery = TestQueryClient(this)
        val key = DetailKey("missing")

        val job = backgroundScope.launch {
            kwery.query(key, options(), InitialData({ null })) {
                delay(100); "from the server"
            }.collect { }
        }
        kwery.settle(10.milliseconds)
        assertEquals(null, kwery.client.getQueryData(key), "nothing to seed with")

        kwery.settle(300.milliseconds)
        assertEquals(1, kwery.requestCount, "so it fetches normally")
        job.cancel()
    }

    @Test
    fun `the seed producer is not called when the entry already exists`() = runTest {
        // Seeding is often an expensive lookup through a list. It must cost
        // nothing on the common path where the entry is already there.
        val kwery = TestQueryClient(this)
        val key = DetailKey("1")
        kwery.client.setQueryData(key, "already fetched")
        var produced = 0

        val job = backgroundScope.launch {
            kwery.query(key, options(), InitialData({ produced++; "a guess" })) {
                delay(100); "server"
            }.collect { }
        }
        kwery.settle(200.milliseconds)

        assertEquals(0, produced, "the producer must not run for an existing entry")
        job.cancel()
    }

    @Test
    fun `seed data enters the cache and is therefore persistable`() = runTest {
        // The defining difference from placeholder data, which never does.
        val kwery = TestQueryClient(this)
        val key = DetailKey("1")

        val job = backgroundScope.launch {
            kwery.query(key, options(), InitialData({ "seeded" })) {
                delay(100); "server"
            }.collect { }
        }
        kwery.settle(10.milliseconds)

        val dehydrated = kwery.client.dehydrate()
        assertTrue(
            dehydrated.any { it.key == key && it.data == "seeded" },
            "seed data must appear in the dehydrated cache",
        )
        assertFalse(kwery.client.getQueryState(key)!!.isPlaceholderData)
        job.cancel()
    }
}
