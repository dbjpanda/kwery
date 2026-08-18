package dev.kwery

import dev.kwery.test.TestQueryClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private data class CombineUserKey(val id: String) : QueryKey<String> {
    override val parts get() = listOf("user", id)
}

private data class CombineSettingsKey(val id: String) : QueryKey<Int> {
    override val parts get() = listOf("settings", id)
}

private data class CombineUnreadKey(val id: String) : QueryKey<Boolean> {
    override val parts get() = listOf("unread", id)
}

private data class CombinedScreen(val name: String?, val fontSize: Int?, val hasUnread: Boolean? = null)

/**
 * Combining queries of different types into one screen state.
 *
 * `aggregate()` covers many queries of the same type. This covers the commoner
 * case — a screen needing a user *and* their settings *and* a count, each a
 * different type — which otherwise costs a cast per query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CombineQueriesTest {

    @Test
    fun `it produces one typed state from two queries`() = runTest {
        val kwery = TestQueryClient(this)
        var last: QueryState<CombinedScreen>? = null

        val job = backgroundScope.launch {
            combineQueries(
                kwery.query(CombineUserKey("1")) { "Ada" },
                kwery.query(CombineSettingsKey("1")) { 14 },
            ) { name, size -> CombinedScreen(name, size) }.collect { last = it }
        }
        kwery.settle()

        assertEquals(CombinedScreen("Ada", 14), last?.data)
        assertTrue(last!!.isSuccess, "both succeeded, so the screen is ready")
        job.cancel()
    }

    @Test
    fun `partial data is preserved so a screen can render what it has`() = runTest {
        val kwery = TestQueryClient(this)
        val slow = CompletableDeferred<Int>()
        var last: QueryState<CombinedScreen>? = null

        val job = backgroundScope.launch {
            combineQueries(
                kwery.query(CombineUserKey("1")) { "Ada" },
                kwery.query(CombineSettingsKey("1")) { slow.await() },
            ) { name, size -> CombinedScreen(name, size) }.collect { last = it }
        }
        kwery.settle()

        // The transform takes nullables on purpose: a screen with two of its
        // three pieces should render what it has rather than blank.
        assertEquals("Ada", last?.data?.name)
        assertNull(last?.data?.fontSize)
        assertTrue(last!!.isPending, "but it is not ready until everything arrives")

        slow.complete(14)
        kwery.settle()
        assertEquals(CombinedScreen("Ada", 14), last?.data)
        assertTrue(last!!.isSuccess)
        job.cancel()
    }

    @Test
    fun `one failure makes the whole combination an error, first error winning`() = runTest {
        val kwery = TestQueryClient(this)
        var last: QueryState<CombinedScreen>? = null

        val job = backgroundScope.launch {
            combineQueries(
                kwery.query(CombineUserKey("1")) { "Ada" },
                kwery.query(CombineSettingsKey("1")) { error("settings are down") },
            ) { name, size -> CombinedScreen(name, size) }.collect { last = it }
        }
        kwery.settle()

        assertTrue(last!!.isError, "a screen cannot claim to be ready while part of it failed")
        assertEquals("settings are down", last?.error?.message)
        assertEquals("Ada", last?.data?.name, "and what did arrive is still there")
        job.cancel()
    }

    @Test
    fun `fetching outranks paused, because something is happening`() = runTest {
        val kwery = TestQueryClient(this)
        val slow = CompletableDeferred<String>()
        var last: QueryState<CombinedScreen>? = null

        kwery.setOnline(false)
        val job = backgroundScope.launch {
            combineQueries(
                kwery.query(CombineUserKey("1")) { slow.await() },
                kwery.query(CombineSettingsKey("1")) { 14 },
            ) { name, size -> CombinedScreen(name, size) }.collect { last = it }
        }
        kwery.settle()
        assertEquals(FetchStatus.Paused, last?.fetchStatus, "offline, nothing can run")

        kwery.setOnline(true)
        kwery.settle()
        assertEquals(FetchStatus.Fetching, last?.fetchStatus)

        slow.complete("Ada")
        kwery.settle()
        job.cancel()
    }

    @Test
    fun `three queries combine, and the arities agree with each other`() = runTest {
        val kwery = TestQueryClient(this)
        var last: QueryState<CombinedScreen>? = null

        val job = backgroundScope.launch {
            combineQueries(
                kwery.query(CombineUserKey("1")) { "Ada" },
                kwery.query(CombineSettingsKey("1")) { 14 },
                kwery.query(CombineUnreadKey("1")) { true },
            ) { name, size, unread -> CombinedScreen(name, size, unread) }.collect { last = it }
        }
        kwery.settle()

        assertEquals(CombinedScreen("Ada", 14, true), last?.data)
        assertTrue(last!!.isSuccess)
        job.cancel()
    }

    @Test
    fun `a disabled query does not hold the screen pending for ever`() = runTest {
        val kwery = TestQueryClient(this)
        var last: QueryState<CombinedScreen>? = null

        val job = backgroundScope.launch {
            combineQueries(
                kwery.query(CombineUserKey("1")) { "Ada" },
                // Disabled — it will never resolve. Counting it as pending
                // would leave this screen loading until the process dies.
                kwery.query(CombineSettingsKey("1"), QueryOptions(enabled = false)) { 14 },
            ) { name, size -> CombinedScreen(name, size) }.collect { last = it }
        }
        kwery.settle()

        assertTrue(
            last!!.isSuccess,
            "a disabled source is excluded, exactly as aggregate() excludes it",
        )
        assertEquals("Ada", last?.data?.name)
        assertNull(last?.data?.fontSize, "and its slot is simply empty")
        job.cancel()
    }

    @Test
    fun `dataUpdatedAt is the most recent of the sources`() = runTest {
        val kwery = TestQueryClient(this)
        val slow = CompletableDeferred<Int>()
        var last: QueryState<CombinedScreen>? = null

        val job = backgroundScope.launch {
            combineQueries(
                kwery.query(CombineUserKey("1")) { "Ada" },
                kwery.query(CombineSettingsKey("1")) { slow.await() },
            ) { name, size -> CombinedScreen(name, size) }.collect { last = it }
        }
        kwery.settle()
        val afterFirst = last?.dataUpdatedAt

        kwery.settle(5.seconds)
        slow.complete(14)
        kwery.settle()

        assertTrue(
            last!!.dataUpdatedAt!! > afterFirst!!,
            "the combined state is as fresh as its freshest part, not its stalest",
        )
        job.cancel()
    }
}
